package dev.lone.pocketmobs;

import dev.lone.pocketmobs.data.Ball;
import dev.lone.pocketmobs.data.BallEffectType;
import dev.lone.pocketmobs.utils.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class BallsEventsListener implements Listener
{
    private static final long CLEANUP_INTERVAL = 20L * 30;
    private static final long THROWN_BALL_TIMEOUT = 20L * 5;
    private static final long MISSED_BALL_RETURN_DELAY = 20L * 3;
    private static final int MAX_CACHE_SIZE = Constants.MAX_CACHE_SIZE;

    private final Map<Integer, Item> balls = new ConcurrentHashMap<>();
    private final Map<Integer, Player> ballsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> craftMessageCooldowns = new ConcurrentHashMap<>();

    // On Folia, event handlers run concurrently on per-region threads, so the
    // maps are genuinely shared state. ConcurrentHashMap keeps each single op
    // safe; this lock guards the compound sequences that touch several maps at
    // once (register/unregister, iterate-and-remove) so they stay atomic.
    private final Object ballOperationLock = new Object();

    private ScheduledTask cleanupTask;

    @EventHandler(priority = EventPriority.MONITOR)
    private void onPlayerJoin(PlayerJoinEvent e)
    {
        migrateBallFormatting(e.getPlayer().getInventory());
        migrateBallFormatting(e.getPlayer().getEnderChest());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onInventoryOpen(InventoryOpenEvent e)
    {
        migrateBallFormatting(e.getInventory());
    }

    private void migrateBallFormatting(Inventory inventory)
    {
        for (int slot = 0; slot < inventory.getSize(); slot++)
        {
            ItemStack item = inventory.getItem(slot);
            if (Ball.removeItalicFormatting(item))
            {
                inventory.setItem(slot, item);
            }
        }
    }

    public BallsEventsListener()
    {
        // Start periodic cleanup for stale references.
        startCleanupTask();
    }
    
    /**
     * Starts the periodic cleanup task for stale references.
     */
    private void startCleanupTask()
    {
        cleanupTask = Sched.runGlobalTimer(this::cleanupExpiredReferences, CLEANUP_INTERVAL, CLEANUP_INTERVAL);
    }

    /**
     * Removes dead or invalid references from the internal caches.
     * <p>
     * Entity liveness can only be read on the entity's owning thread, so each
     * check is dispatched to that entity's scheduler; the {@code retired}
     * callback fires when the entity is already gone. The maps themselves are
     * concurrent, so iterating them from this global task is safe. Enderman
     * tracking holds only UUIDs (no entity handle), so it relies entirely on
     * {@link #onEntityRemove} for pruning instead of an off-thread lookup.
     */
    private void cleanupExpiredReferences()
    {
        for (Map.Entry<Integer, Item> entry : balls.entrySet())
        {
            int entityId = entry.getKey();
            Item item = entry.getValue();
            if (item == null)
            {
                synchronized (ballOperationLock)
                {
                    balls.remove(entityId);
                    ballsByPlayer.remove(entityId);
                }
                continue;
            }
            Sched.runAtEntity(item, () -> {
                if (item.isDead() || !item.isValid())
                {
                    synchronized (ballOperationLock)
                    {
                        balls.remove(entityId);
                        ballsByPlayer.remove(entityId);
                    }
                }
            }, () -> {
                synchronized (ballOperationLock)
                {
                    balls.remove(entityId);
                    ballsByPlayer.remove(entityId);
                }
            });
        }
    }

    public void cleanupAll()
    {
        // 鍋滄瀹氭椂娓呯悊浠诲姟
        if (cleanupTask != null)
        {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        
        balls.clear();
        ballsByPlayer.clear();
        craftMessageCooldowns.clear();
        HologramUtil.clearAllCooldowns();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onEntityRemove(EntityRemoveEvent e)
    {
        if (e.getEntity() instanceof Item item)
        {
            int entityId = item.getEntityId();
            synchronized (ballOperationLock)
            {
                balls.remove(entityId);
                ballsByPlayer.remove(entityId);
            }
        }
    }
    
    /**
     * 鐜╁閫€鍑烘椂娓呯悊鎶曟幏涓殑鐞?     */
    @EventHandler(priority = EventPriority.MONITOR)
    private void onPlayerQuit(PlayerQuitEvent e)
    {
        Player player = e.getPlayer();
        craftMessageCooldowns.remove(player.getUniqueId());
        HologramUtil.clearPlayerCooldown(player.getUniqueId());
        Location target = player.getLocation();
        synchronized (ballOperationLock)
        {
            Iterator<Map.Entry<Integer, Player>> iterator = ballsByPlayer.entrySet().iterator();
            while (iterator.hasNext())
            {
                Map.Entry<Integer, Player> entry = iterator.next();
                if (entry.getValue().equals(player))
                {
                    int entityId = entry.getKey();
                    Item ballItem = balls.get(entityId);
                    iterator.remove();
                    balls.remove(entityId);
                    if (ballItem != null)
                    {
                        // 鐞冨彲鑳藉綊灞炲叾瀹冨尯鍩熺嚎绋嬶紝鎭㈠鎿嶄綔鍦ㄧ悆鑷韩绾跨▼涓婃墽琛屻€?                        recoverBallOnItsThread(ballItem, target);
                    }
                }
            }
        }
    }
    
    /**
     * 涓栫晫鍗歌浇鏃舵竻鐞嗚涓栫晫涓殑鐞?     */
    @EventHandler(priority = EventPriority.MONITOR)
    private void onWorldUnload(WorldUnloadEvent e)
    {
        World world = e.getWorld();

        // Pure tracking cleanup, no entity mutation: just drop map entries for
        // balls in the unloading world.
        synchronized (ballOperationLock)
        {
            Iterator<Map.Entry<Integer, Item>> iterator = balls.entrySet().iterator();
            while (iterator.hasNext())
            {
                Map.Entry<Integer, Item> entry = iterator.next();
                Item item = entry.getValue();
                if (item != null && item.getWorld().equals(world))
                {
                    iterator.remove();
                    ballsByPlayer.remove(entry.getKey());
                }
            }
        }
    }
    
    /**
     * 鐜╁姝讳骸鏃跺鐞嗚鐫€鐢熺墿鐨勭悆
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onPlayerDeath(PlayerDeathEvent e)
    {
        Player player = e.getEntity();
        Location target = player.getLocation();
        synchronized (ballOperationLock)
        {
            for (Map.Entry<Integer, Player> entry : ballsByPlayer.entrySet())
            {
                if (entry.getValue().equals(player))
                {
                    Item ballItem = balls.get(entry.getKey());
                    if (ballItem != null)
                    {
                        recoverBallOnItsThread(ballItem, target);
                    }
                }
            }
        }
    }
    
    /**
     * 鐜╁鍒囨崲涓栫晫鏃舵鏌ユ潈闄?     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onPlayerChangedWorld(PlayerChangedWorldEvent e)
    {
        Player player = e.getPlayer();
        Location target = player.getLocation();
        synchronized (ballOperationLock)
        {
            Iterator<Map.Entry<Integer, Player>> iterator = ballsByPlayer.entrySet().iterator();
            while (iterator.hasNext())
            {
                Map.Entry<Integer, Player> entry = iterator.next();
                if (entry.getValue().equals(player))
                {
                    int entityId = entry.getKey();
                    Item ballItem = balls.get(entityId);
                    iterator.remove();
                    balls.remove(entityId);
                    if (ballItem != null)
                    {
                        // 灏嗙悆杩斿洖缁欑帺瀹讹紙鍦ㄧ悆鑷韩绾跨▼涓婁紶閫侊級
                        recoverBallOnItsThread(ballItem, target);
                    }
                }
            }
        }
    }

    @EventHandler
    private void onPlayerInteract(PlayerInteractEvent e)
    {
        if (e.getHand() == EquipmentSlot.OFF_HAND)
            return;

        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        if (e.getItem() == null)
            return;

        Player player = e.getPlayer();
        ItemStack ball = e.getItem();

        if (!Ball.is(ball))
            return;

        if (Ball.hasMob(ball))
        {
            throwBallFreeMob(e, player, ball);
        }
        else
        {
            throwBallCatchMob(e, player, ball);
        }
    }

    private void throwBallCatchMob(Cancellable e, Player player, ItemStack ball)
    {
        e.setCancelled(true);

        ThrownBallResult result = createThrownBall(player, ball);
        if (result == null) return;

        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setMetadata("PBCatch", new FixedMetadataValue(Main.inst, result.dropEntityId));
        snowball.setShooter(player);
        Utils.hideEntity(snowball);

        result.drop.setVelocity(snowball.getVelocity());
    }
    
    /**
     * 鍒涘缓鎶曟幏鐨勭悆鐨勯€氱敤鏂规硶
     * @param player 鎶曟幏鑰?     * @param ball 鐞冪墿鍝?     * @return 鎶曟幏缁撴灉锛屽寘鍚帀钀界墿鍜屽疄浣揑D
     */
    private ThrownBallResult createThrownBall(Player player, ItemStack ball)
    {
        ItemStack thrown = ball.clone();
        thrown.setAmount(1);

        InvUtil.decrementAmountMainHand(player);

        Item drop = player.getWorld().dropItem(player.getEyeLocation(), thrown);
        drop.setCustomNameVisible(false);
        drop.setMetadata("PBIsBall", new FixedMetadataValue(Main.inst, true));
        drop.setPickupDelay(Integer.MAX_VALUE);
        // The ball is decremented from the hand up front (above), so if this visible
        // drop is destroyed while flying/resting the projectile-hit handler would find
        // it already gone and silently lose the ball. Make it invulnerable so lava,
        // fire, cactus and explosions cannot remove it. (Void still bypasses
        // invulnerability, but that only happens if the player throws it off the world.)
        drop.setInvulnerable(true);

        synchronized (ballOperationLock)
        {
            balls.put(drop.getEntityId(), drop);
            ballsByPlayer.put(drop.getEntityId(), player);

            if (balls.size() > MAX_CACHE_SIZE)
            {
                cleanupExpiredReferences();
            }
        }

        // Protection plugins can cancel or consume the projectile-hit event.
        // If that happens, the normal recovery path is never called and the item
        // would remain permanently unpickable. Recover it on its owning region
        // after a short timeout unless a hit handler already claimed it.
        drop.getScheduler().runDelayed(Main.inst, task -> {
            boolean stillTracked;
            synchronized (ballOperationLock)
            {
                stillTracked = balls.get(drop.getEntityId()) == drop;
                if (stillTracked)
                {
                    balls.remove(drop.getEntityId());
                    ballsByPlayer.remove(drop.getEntityId());
                }
            }
            if (stillTracked && !drop.isDead())
            {
                leaveBallRecoverable(drop, drop.getLocation());
            }
        }, null, THROWN_BALL_TIMEOUT);

        return new ThrownBallResult(drop, drop.getEntityId());
    }
    
    /**
     * 鎶曟幏鐞冪粨鏋滅殑鏁版嵁绫?     */
    private static class ThrownBallResult
    {
        final Item drop;
        final int dropEntityId;
        
        ThrownBallResult(Item drop, int dropEntityId)
        {
            this.drop = drop;
            this.dropEntityId = dropEntityId;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    private void onSnowballHit(ProjectileHitEvent e)
    {
        if (!(e.getEntity() instanceof Snowball))
            return;

        if (!e.getEntity().hasMetadata("PBCatch"))
            return;

        e.setCancelled(true);

        Snowball snowball = (Snowball) e.getEntity();
        int PBCatch = snowball.getMetadata("PBCatch").get(0).asInt();
        Item ballEntity = balls.get(PBCatch);
        Player player = ballsByPlayer.get(PBCatch);

        snowball.remove();

        if (ballEntity == null || player == null)
        {
            cleanupMaps(PBCatch);
            return;
        }

        Entity hitEntity = e.getHitEntity();
        if (hitEntity != null)
        {
            handleCatch(ballEntity, player, hitEntity, PBCatch);
        }
        else
        {
            handleMiss(ballEntity, player, PBCatch);
        }
    }

    /**
     * Returns the thrown ball to the player, tells them why the catch failed and
     * clears the tracking maps. Consolidates the reject-and-return sequence that
     * every eligibility guard in {@link #handleCatch} shares.
     */
    private void rejectCatch(Item ballEntity, Player player, int pbCatch, String langKey)
    {
        returnBallToPlayer(ballEntity, player);
        ActionBar.send(player, Settings.lang.getColored(langKey));
        cleanupMaps(pbCatch);
    }

    /**
     * Returns the lang key explaining why {@code mobEntity} cannot be caught with
     * {@code settings}, or {@code null} when it is eligible. Pure check with no
     * side effects, so it is safe to evaluate before any mutation.
     */
    private String catchRejectionKey(Ball settings, Entity mobEntity)
    {
        if (mobEntity.getType() == EntityType.UNKNOWN
                || mobEntity.getType() == EntityType.PLAYER
                || !settings.isCatchableMob(mobEntity)
                || !(mobEntity instanceof LivingEntity))
            return "cant-catch-with-this-ball";

        LivingEntity living = (LivingEntity) mobEntity;
        if (!living.hasAI())
            return "cant-catch-with-this-ball";

        // Boss entities are intentionally excluded from capture.
        if (isBossEntity(mobEntity.getType()))
            return "boss-entity-not-catchable";

        if (living.isInvulnerable() || living.isInvisible())
            return "cant-catch-with-this-ball";

        return null;
    }

    /**
     * Returns the lang key explaining why {@code mobEntity} is a pet that
     * {@code player} may not catch, or {@code null} when catching is allowed.
     * <p>
     * Vanilla tamed pets (wolf, cat, parrot, horse, ...) can still be caught by
     * their own owner. Mobs managed by pet plugins (MyPet & co.) can never be
     * caught: their data belongs to the plugin, not to the world, and storing
     * them in a ball would corrupt or duplicate the pet.
     */
    private String petCatchRejectionKey(Player player, Entity mobEntity)
    {
        if (Settings.blockOthersTamedPets)
        {
            if (mobEntity instanceof Tameable tameable && tameable.isTamed())
            {
                AnimalTamer tamer = tameable.getOwner();
                if (tamer == null || !tamer.getUniqueId().equals(player.getUniqueId()))
                    return "cant-catch-tamed-pet";
            }

            // A mob led on a leash by someone else is being walked by its
            // holder: treat it like a tamed pet.
            if (mobEntity instanceof LivingEntity living
                    && living.isLeashed()
                    && living.getLeashHolder() instanceof Player holder
                    && !holder.getUniqueId().equals(player.getUniqueId()))
            {
                return "cant-catch-tamed-pet";
            }
        }

        if (Settings.blockPetPluginPets && PetProtection.isPetPluginMob(mobEntity))
            return "cant-catch-plugin-pet";

        return null;
    }

    private void handleCatch(Item ballEntity, Player player, Entity mobEntity, int PBCatch)
    {
        Ball settings = Main.inst.ballsManager.byItemStack(ballEntity.getItemStack());
        if (settings == null)
        {
            Main.inst.getLogger().warning(Settings.lang.getColored("unknown-ball-type"));
            cleanupMaps(PBCatch);
            return;
        }

        if (!isWorldAllowed(player.getWorld().getName()))
        {
            rejectCatch(ballEntity, player, PBCatch, "world-not-allowed");
            return;
        }

        String rejectKey = catchRejectionKey(settings, mobEntity);
        if (rejectKey != null)
        {
            rejectCatch(ballEntity, player, PBCatch, rejectKey);
            return;
        }

        String petRejectKey = petCatchRejectionKey(player, mobEntity);
        if (petRejectKey != null)
        {
            rejectCatch(ballEntity, player, PBCatch, petRejectKey);
            return;
        }

        // Detach the entity from any vehicle/passenger relationship before capture.
        if (mobEntity.isInsideVehicle())
            mobEntity.leaveVehicle();
        if (!mobEntity.getPassengers().isEmpty())
            mobEntity.eject();

        // Respect claims and region-protection plugins.
        if (!ProtectionUtils.canCatchMob(player, mobEntity, mobEntity.getLocation()))
        {
            rejectCatch(ballEntity, player, PBCatch, "cant-catch-here");
            return;
        }

        if (!DominionProtectionUtils.canCatchMob(player, mobEntity, mobEntity.getLocation()))
        {
            rejectCatch(ballEntity, player, PBCatch, "cant-catch-other-dominion");
            return;
        }

        if (!PerfectSelfWorldProtectionUtils.canCatchMob(player, mobEntity))
        {
            rejectCatch(ballEntity, player, PBCatch, "cant-catch-here");
            return;
        }
        ItemStack ballItemStack = ballEntity.getItemStack();

        // A depleted limited-usage ball (0 left) must never be able to catch.
        // Guarded BEFORE reduceUsages so a spent ball can never reach catchMob.
        // Unlimited balls skip usage accounting and are exempt. Ball.getUsages()
        // returns 1 when the PBUsages tag is absent, so only a properly tagged,
        // genuinely spent ball reads 0 and is rejected here.
        if (!settings.unlimitedUsages && Ball.getUsages(ballItemStack) <= 0)
        {
            ballEntity.remove();
            showMessageFeedback(player, Settings.lang.getColored("no-usages-left"));
            cleanupMaps(PBCatch);
            return;
        }

        if (!settings.unlimitedUsages)
        {
            ballItemStack = Ball.reduceUsages(ballItemStack, 1);
            ballEntity.setItemStack(ballItemStack);
        }

        if (!settings.rollDice())
        {
            if (Ball.getUsages(ballItemStack) > 0)
            {
                returnBallToPlayer(ballEntity, player);
                ActionBar.send(player, Settings.lang.getColored("failed-to-catch"));
            }
            else
            {
                ballEntity.remove();
                showMessageFeedback(player, Settings.lang.getColored("no-usages-left"));
            }
        }
        else
        {
            // Prepare the captured ball first. Only remove the entity after that succeeds.
            ItemStack caughtBall = null;
            try
            {
                caughtBall = Ball.catchMob(ballItemStack, mobEntity);
            }
            catch (Exception e)
            {
                Main.inst.getLogger().warning("Failed to catch mob: " + e.getMessage());
                if (Settings.debug)
                {
                    Main.inst.getLogger().log(Level.WARNING, "Detailed catch failure stacktrace", e);
                }
                // Capture failed. Return the ball safely to the player.
                returnBallToPlayer(ballEntity, player);
                ActionBar.send(player, Settings.lang.getColored("catch-error-retry"));
                cleanupMaps(PBCatch);
                return;
            }
            
            if (caughtBall == null)
            {
                // Capture failed, for example because the serialized data was too large.
                returnBallToPlayer(ballEntity, player);
                ActionBar.send(player, Settings.lang.getColored("failed-to-catch"));
                cleanupMaps(PBCatch);
                return;
            }
            
            // The new ball is ready, so it is now safe to remove the captured entity.
            ballEntity.setItemStack(caughtBall);
            Location catchLocation = mobEntity.getLocation();
            mobEntity.remove();

            boolean returnToInv = Settings.returnToInvCatch;
            Location playerLocation = returnToInv ? player.getLocation() : null;

            // Ball and mob share the hit region here, but use teleportAsync so the
            // follow-up "return to player" move (potentially cross-region) is safe.
            ballEntity.teleportAsync(catchLocation).thenAccept(ok -> {
                if (ballEntity.isDead())
                    return;
                ballEntity.setVelocity(new Vector(0, 0.4f, 0));
                ballEntity.setGlowing(true);
                settleBall(ballEntity);

                if (returnToInv && playerLocation != null)
                {
                    ballEntity.teleportAsync(playerLocation).thenAccept(ok2 -> {
                        if (!ballEntity.isDead())
                        {
                            ballEntity.setVelocity(new Vector(0, 0, 0));
                            settleBall(ballEntity);
                        }
                    });
                }
            });

            settings.playEffect(BallEffectType.CATCH, catchLocation);

            ActionBar.send(player, Settings.lang.getColored("caugth-successfully"));
        }

        cleanupMaps(PBCatch);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onBallProjectileDamage(EntityDamageByEntityEvent e)
    {
        if (e.getDamager().hasMetadata("PBCatch") || e.getDamager().hasMetadata("PBSpawn"))
        {
            e.setCancelled(true);
        }
    }

    private void handleMiss(Item ballEntity, Player player, int PBCatch)
    {
        ballEntity.setVelocity(new Vector(0, 0.1f, 0));

        Ball ballSettings = Main.inst.ballsManager.byItemStack(ballEntity.getItemStack());
        if (ballSettings == null)
        {
            Main.inst.getLogger().warning(Settings.lang.getColored("unknown-ball-type"));
            cleanupMaps(PBCatch);
            return;
        }

        if (!ballSettings.unlimitedUsages)
        {
            if (Settings.reduceUsagesOnMiss)
                ballEntity.setItemStack(Ball.reduceUsages(ballEntity.getItemStack(), 1));
        }
        ballSettings.playEffect(BallEffectType.MISSED, ballEntity.getLocation());

        if (Ball.getUsages(ballEntity.getItemStack()) > 0)
        {
            ballEntity.setCustomNameVisible(true);
            ballEntity.setCustomName(Settings.lang.getColored("missed-catch"));
            if (Settings.returnToInvCatch)
            {
                Location playerLocation = player != null ? player.getLocation() : null;
                if (playerLocation != null)
                {
                    ballEntity.getScheduler().runDelayed(Main.inst, task -> {
                        if (!ballEntity.isDead())
                        {
                            returnBallToPlayer(ballEntity, player);
                        }
                    }, null, MISSED_BALL_RETURN_DELAY);
                }
                else
                {
                    settleBall(ballEntity);
                }
            }
            else
            {
                settleBall(ballEntity);
            }
        }
        else
        {
            if (player != null)
            {
                showMessageFeedback(player, Settings.lang.getColored("no-usages-left"));
            }
            ballEntity.remove();
        }

        cleanupMaps(PBCatch);
    }

    private void showMessageFeedback(Player player, String message)
    {
        if (player == null)
        {
            return;
        }

        HologramUtil.send(player, message, 20);
    }

    @EventHandler
    private void onPlayerInteractEntity(PlayerInteractEntityEvent e)
    {
        if (e.getHand() == EquipmentSlot.OFF_HAND)
            return;

        Player player = e.getPlayer();
        ItemStack ball = player.getInventory().getItemInMainHand();

        if (!Ball.is(ball))
            return;

        if (!Ball.hasMob(ball))
            return;

        e.setCancelled(true);

        throwBallFreeMob(e, player, ball);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void spawnMobOnHit(ProjectileHitEvent e)
    {
        if (!e.getEntity().hasMetadata("PBSpawn"))
            return;

        int PBSpawn = e.getEntity().getMetadata("PBSpawn").get(0).asInt();
        Item drop;
        Player player;
        synchronized (ballOperationLock)
        {
            // Claim this release exactly once. ProjectileHitEvent can be observed
            // more than once by concurrent region/event processing; leaving the
            // entries in the maps until the end allowed every callback to spawn
            // the stored mob again.
            drop = balls.remove(PBSpawn);
            player = ballsByPlayer.remove(PBSpawn);
        }

        if (drop == null)
        {
            e.getEntity().remove();
            Main.inst.getLogger().warning("Drop item not found for PBSpawn: " + PBSpawn);
            cleanupMaps(PBSpawn);
            return;
        }

        e.getEntity().remove();

        Location hitLocation = null;
        if (e.getHitEntity() != null)
        {
            hitLocation = e.getHitEntity().getLocation();
        }
        else if (e.getHitBlock() != null)
        {
            hitLocation = e.getHitBlock().getLocation().add(0.5, 1, 0.5);
        }
        else
        {
            hitLocation = e.getEntity().getLocation();
        }

        if (Ball.hasMob(drop.getItemStack()))
        {
            // 涓栫晫鐧藉悕鍗曟牎楠岋細涓庢崟鎹?handleCatch)鍜屽彂灏勫櫒(onDropperDispense)璺緞淇濇寔涓€鑷达紝
            if (!isWorldAllowed(hitLocation.getWorld().getName()))
            {
                if (player != null)
                {
                    returnBallToPlayer(drop, player);
                    showMessageFeedback(player, Settings.lang.getColored("world-not-allowed"));
                }
                else
                {
                    leaveBallRecoverable(drop, hitLocation);
                }
                cleanupMaps(PBSpawn);
                return;
            }

            // Check protection plugin permission.
            if (player == null)
            {
                leaveBallRecoverable(drop, hitLocation);
                cleanupMaps(PBSpawn);
                return;
            }

            if (!ProtectionUtils.canReleaseMob(player, hitLocation))
            {
                returnBallToPlayer(drop, player);
                showMessageFeedback(player, Settings.lang.getColored("cant-release-here"));
                cleanupMaps(PBSpawn);
                return;
            }

            EntityType storedMobType = Ball.getStoredMobType(drop.getItemStack());
            if (storedMobType != null
                    && storedMobType.getEntityClass() != null
                    && Monster.class.isAssignableFrom(storedMobType.getEntityClass())
                    && !PerfectSelfWorldProtectionUtils.canReleaseMonster(player, hitLocation))
            {
                returnBallToPlayer(drop, player);
                showMessageFeedback(player, Settings.lang.getColored("cant-release-here"));
                cleanupMaps(PBSpawn);
                return;
            }

            if (storedMobType != null
                    && !DominionProtectionUtils.canReleaseMob(player, storedMobType, hitLocation))
            {
                returnBallToPlayer(drop, player);
                showMessageFeedback(player, Settings.lang.getColored("cant-release-here"));
                cleanupMaps(PBSpawn);
                return;
            }

            // Release from a detached copy first. This keeps the live ball intact
            // until all release-protection checks have passed.
            ItemStack loadedBall = drop.getItemStack().clone();
            Entity spawnedMob = Ball.freeMob(loadedBall, hitLocation);

            if (spawnedMob != null)
            {
                if (!PerfectSelfWorldProtectionUtils.canReleaseMob(player, spawnedMob))
                {
                    spawnedMob.remove();
                    returnBallToPlayer(drop, player);
                    showMessageFeedback(player, Settings.lang.getColored("cant-release-here"));
                    cleanupMaps(PBSpawn);
                    return;
                }

                // Reuse the visible thrown item instead of deleting it and creating
                // another Item entity. This guarantees the entity the player sees has
                // its mob PDC/lore cleared and its infinite flight pickup delay reset.
                ItemStack emptyBall = Ball.removeMob(drop.getItemStack().clone());
                Ball newBallSettings = Main.inst.ballsManager.byItemStack(emptyBall);

                // A spent limited-usage ball (0 left) would leave a useless,
                // exploitable empty husk. Destroy it (don't drop it) instead.
                // Unlimited balls skip reduceUsages and are never 'depleted';
                // getUsages() returns 1 when the tag is absent, so untagged/legacy
                // balls read > 0 and drop normally.
                boolean depleted = newBallSettings != null
                        && !newBallSettings.unlimitedUsages
                        && Ball.getUsages(emptyBall) <= 0;

                // The mob WAS successfully freed, so play the FREE effect regardless.
                if (newBallSettings != null)
                {
                    newBallSettings.playEffect(BallEffectType.FREE, hitLocation);
                }

                if (!depleted)
                {
                    drop.setItemStack(emptyBall);
                    drop.setCustomName(null);
                    drop.setCustomNameVisible(false);
                    settleBall(drop);
                    Location recoveryLocation = Settings.returnToInvFree && player != null
                            ? player.getLocation()
                            : hitLocation;
                    leaveBallRecoverable(drop, recoveryLocation);
                }
                else
                {
                    drop.remove();
                }
            }
            else
            {
                leaveBallRecoverable(drop, hitLocation);
                Ball ballSettings = Main.inst.ballsManager.byItemStack(drop.getItemStack());
                if (ballSettings != null)
                {
                    ballSettings.playEffect(BallEffectType.NOT_SPAWNED, hitLocation);
                }

                if (player != null)
                {
                    showMessageFeedback(player, Settings.lang.getColored("failed-to-spawn-mob"));
                }
            }
        }
        else
        {
            leaveBallRecoverable(drop, hitLocation);
            Ball ballSettings = Main.inst.ballsManager.byItemStack(drop.getItemStack());
            if (ballSettings != null)
            {
                ballSettings.playEffect(BallEffectType.NOT_SPAWNED, hitLocation);
            }

            if (player != null)
            {
                showMessageFeedback(player, Settings.lang.getColored("no-mob-in-ball"));
            }
        }

        cleanupMaps(PBSpawn);
    }

    @EventHandler
    private void onPrepareItemCraft(PrepareItemCraftEvent e)
    {
        if (e.isRepair())
            return;

        if (e.getRecipe() == null)
            return;

        if (!Ball.is(e.getRecipe().getResult()))
            return;

        Ball ballConfig = Main.inst.ballsManager.byItemStack(e.getRecipe().getResult());
        if (ballConfig == null)
            return;

        if (!isWorldAllowed(e.getView().getPlayer().getLocation().getWorld().getName()) ||
            !e.getView().getPlayer().hasPermission("pocketmob.user.craft." + ballConfig.name))
        {
            e.getInventory().setResult(new ItemStack(Material.AIR, 1));
            notifyCraftPermission(e.getView().getPlayer());
        }
    }

    private void notifyCraftPermission(org.bukkit.entity.HumanEntity player)
    {
        if (!(player instanceof Player))
        {
            return;
        }

        Player bukkitPlayer = (Player) player;
        long now = System.currentTimeMillis();
        long lastSent = craftMessageCooldowns.getOrDefault(bukkitPlayer.getUniqueId(), 0L);
        if (now - lastSent < 1000L)
        {
            return;
        }

        craftMessageCooldowns.put(bukkitPlayer.getUniqueId(), now);
        ActionBar.send(bukkitPlayer, Settings.lang.getColored("no-craft-permission"));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    private void onHopperPickupItem(InventoryPickupItemEvent e)
    {
        if (e.getItem().hasMetadata("PBIsBall"))
        {
            e.getItem().setVelocity(new Vector(0.2, 0.2, 0.2));
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    private void onPlayerPickupBall(PlayerAttemptPickupItemEvent e)
    {
        Ball.removeItalicFormatting(e.getItem().getItemStack());
        if (Ball.is(e.getItem().getItemStack()) && e.isCancelled())
        {
            Player player = e.getPlayer();
            Item item = e.getItem();
            ItemStack stack = item.getItemStack().clone();
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            e.setCancelled(true);
            if (leftovers.isEmpty())
            {
                item.remove();
            }
            else
            {
                item.setItemStack(leftovers.values().iterator().next());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    private void onEntityPickupBall(EntityPickupItemEvent e)
    {
        // Some protection plugins cancel the parent entity-pickup event instead
        // of PlayerAttemptPickupItemEvent. Handle that path as well so a released
        // empty ball is not left permanently unpickable for visitors.
        Ball.removeItalicFormatting(e.getItem().getItemStack());
        if (e.getEntity() instanceof Player && Ball.is(e.getItem().getItemStack()) && e.isCancelled())
        {
            Player player = (Player) e.getEntity();
            Item item = e.getItem();
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.getItemStack().clone());
            e.setCancelled(true);
            if (leftovers.isEmpty())
            {
                item.remove();
            }
            else
            {
                item.setItemStack(leftovers.values().iterator().next());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    @SuppressWarnings("deprecation")
    private void onLegacyPlayerPickupBall(PlayerPickupItemEvent e)
    {
        // Keep compatibility with protection plugins that still cancel the legacy
        // PlayerPickupItemEvent instead of the modern parent/attempt events.
        if (Ball.is(e.getItem().getItemStack()) && e.isCancelled())
        {
            Map<Integer, ItemStack> leftovers = e.getPlayer().getInventory().addItem(e.getItem().getItemStack().clone());
            e.setCancelled(true);
            if (leftovers.isEmpty())
            {
                e.getItem().remove();
            }
            else
            {
                e.getItem().setItemStack(leftovers.values().iterator().next());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onBallPlace(BlockPlaceEvent e)
    {
        if (Ball.is(e.getItemInHand()))
        {
            // A PocketMobs ball is an interaction item, never a placeable block.
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    private void onDropperDispense(BlockDispenseEvent e)
    {
        if (!Settings.dropperSpawnMob)
        {
            return;
        }

        ItemStack item = e.getItem();
        if (!Ball.is(item) || !Ball.hasMob(item))
        {
            return;
        }

        final Block block = e.getBlock();
        final Location spawnLocation = block.getLocation().add(0.5, 1, 0.5);
        if (!isWorldAllowed(spawnLocation.getWorld().getName()))
        {
            return;
        }

        // Snapshot the exact ball vanilla was about to dispense. e.getItem() is
        // already a split-off clone per the BlockDispenseEvent contract; clone
        // again to fully detach it from the (mirror of the) source stack.
        final ItemStack ballTemplate = item.clone();
        ballTemplate.setAmount(1);

        // Cancel so vanilla ejects nothing. On cancel CraftBukkit does grow(1) and
        // DispenserBlock.dispenseFrom writes the restored FULL stack back into the
        // slot THIS tick, so the source ball is left intact once the write-back
        // settles. We therefore must NOT edit the inventory now: mid-dispense the
        // slot is still split (often already empty) and any edit is clobbered by
        // that write-back. Defer BOTH the consume and the spawn to next tick and do
        // them atomically on the owning region thread, so there is never a moment
        // where the freed mob exists while the source ball also still exists.
        e.setCancelled(true);

        Sched.runAtRegion(block.getLocation(), () ->
        {
            if (!(block.getState() instanceof Container container))
            {
                return;
            }

            // Live inventory of the placed block (NOT getSnapshotInventory()); edits
            // apply directly to the world, so no BlockState.update() call is needed.
            Inventory inv = container.getInventory();

            // Consume EXACTLY ONE matching ball. Mob-balls carry a unique
            // PBAntiStackRandom, so isSimilar() matches only this exact ball. If it
            // is gone (a hopper/dropper relocated it in the interim) removeItem
            // reports it as not-removed: abort with no spawn, so the moved ball
            // simply keeps its mob and nothing is duplicated.
            Map<Integer, ItemStack> notRemoved = inv.removeItem(ballTemplate.clone());
            if (!notRemoved.isEmpty())
            {
                return;
            }

            Entity spawned = Ball.freeMob(ballTemplate.clone(), spawnLocation);
            if (spawned == null)
            {
                // Spawn failed: put the still-loaded ball back so it stays
                // recoverable (net zero 鈥?one removed, one restored, no dupe).
                for (ItemStack leftover : inv.addItem(ballTemplate.clone()).values())
                {
                    spawnLocation.getWorld().dropItem(spawnLocation, leftover);
                }
                return;
            }

            // Mob freed: hand back the empty ball, unless it is a spent limited-usage
            // ball (0 left) 鈥?those would be a useless, exploitable 0-use husk, so
            // don't drop them. Unlimited balls skip usage accounting and are exempt;
            // getUsages() returns 1 when the tag is absent, so legacy balls drop.
            ItemStack emptyBall = Ball.removeMob(ballTemplate.clone());
            Ball newBallSettings = Main.inst.ballsManager.byItemStack(emptyBall);
            if (newBallSettings != null
                    && !newBallSettings.unlimitedUsages
                    && Ball.getUsages(emptyBall) <= 0)
            {
                return;
            }

            spawnLocation.getWorld().dropItem(spawnLocation, emptyBall);
        });
    }

    private void throwBallFreeMob(Cancellable e, Player player, ItemStack ball)
    {
        e.setCancelled(true);

        if (!Ball.hasMob(ball))
            return;

        ThrownBallResult result = createThrownBall(player, ball);
        if (result == null) return;

        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setMetadata("PBSpawn", new FixedMetadataValue(Main.inst, result.dropEntityId));
        snowball.setShooter(player);
        Utils.hideEntity(snowball);

        result.drop.setVelocity(snowball.getVelocity());
    }
    
    /**
     * 妫€鏌ユ槸鍚︿负Boss瀹炰綋
     * Boss瀹炰綋涓嶅簲璇ヨ鎹曟崏锛屼互淇濇寔娓告垙骞宠　
     */
    private boolean isBossEntity(EntityType type)
    {
        switch (type)
        {
            case ENDER_DRAGON:
            case WITHER:
            case ELDER_GUARDIAN:
            case RAVAGER:
            case WARDEN:
                return true;
            default:
                return false;
        }
    }

    private void cleanupMaps(int entityId)
    {
        synchronized (ballOperationLock)
        {
            balls.remove(entityId);
            ballsByPlayer.remove(entityId);
        }
    }

    private boolean isWorldAllowed(String worldName)
    {
        if (Settings.worldWhitelistEnabled && !Settings.worlds.contains(worldName))
            return false;
        return !Settings.worldBlacklistEnabled || !Settings.worldBlacklist.contains(worldName);
    }

    private void returnBallToPlayer(Item ballEntity, Player player)
    {
        recoverBallOnItsThread(ballEntity, player.getLocation());
    }

    /**
     * Marks a thrown ball as settled once it has landed: it becomes pickup-able
     * again and drops the in-flight invulnerability applied in
     * {@link #createThrownBall} so a resting ball behaves like a normal item
     * (it can once more be destroyed by lava/fire/cactus, etc.).
     */
    private void settleBall(Item ballEntity)
    {
        ballEntity.setPickupDelay(0);
        ballEntity.setInvulnerable(false);
        ballEntity.removeMetadata("PBIsBall", Main.inst);
    }

    /**
     * Recover a thrown ball at {@code location}. The ball entity may be owned by
     * a different region thread, so the actual teleport/velocity changes are
     * dispatched to its scheduler and the cross-region move uses teleportAsync.
     */
    private void recoverBallOnItsThread(Item ballEntity, Location location)
    {
        Sched.runAtEntity(ballEntity, () -> leaveBallRecoverable(ballEntity, location), null);
    }

    private void leaveBallRecoverable(Item ballEntity, Location location)
    {
        if (ballEntity.isDead())
            return;
        settleBall(ballEntity);
        ballEntity.teleportAsync(location).thenAccept(success -> {
            if (success && !ballEntity.isDead())
            {
                ballEntity.setVelocity(new Vector(0, 0, 0));
                settleBall(ballEntity);
            }
        });
    }
}


