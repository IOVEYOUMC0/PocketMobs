package dev.lone.pocketmobs.utils;

import dev.lone.pocketmobs.Main;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional PerfectSelfWorld integration using its existing damage-permission
 * listener. Calling the event does not apply damage; it only asks listeners
 * whether the attack would be allowed in the target home world/area.
 */
public final class PerfectSelfWorldProtectionUtils
{
    private static final String PLUGIN_NAME = "PerfectSelfWorld";

    private PerfectSelfWorldProtectionUtils()
    {
    }

    public static boolean canCatchMob(Player player, Entity target)
    {
        Plugin plugin = Main.inst.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled())
            return true;

        EntityDamageByEntityEvent permissionProbe = new EntityDamageByEntityEvent(
                player,
                target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK)
                        .withCausingEntity(player)
                        .withDirectEntity(player)
                        .withDamageLocation(target.getLocation())
                        .build(),
                // Use a normal positive damage amount so protection plugins that
                // ignore zero-damage probes still evaluate their damage permission.
                // The event is only dispatched for permission checking; no damage
                // is applied to the entity.
                1.0D);
        Main.inst.getServer().getPluginManager().callEvent(permissionProbe);
        return !permissionProbe.isCancelled();
    }

    public static boolean canReleaseMob(Player player, Entity spawnedMob)
    {
        return canCatchMob(player, spawnedMob);
    }

    public static boolean canReleaseMonster(Player player, Location location)
    {
        Plugin plugin = Main.inst.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled())
            return true;

        try
        {
            Class<?> nodeUtil = Class.forName("com.yxmax.perfectSelfWorld.util.NodeUtil");
            Field statusManagerField = nodeUtil.getField("statusManager");
            Object statusManager = statusManagerField.get(null);
            if (statusManager == null)
                return true;

            Field indexesField = statusManager.getClass().getDeclaredField("worldIndexes");
            indexesField.setAccessible(true);
            Object indexes = indexesField.get(statusManager);
            if (!(indexes instanceof java.util.Map<?, ?> indexMap))
                return true;

            String targetWorld = location.getWorld().getName();
            UUID ownerId = null;
            Object worldIndex = null;
            Object targetNumber = null;
            for (java.util.Map.Entry<?, ?> indexEntry : indexMap.entrySet())
            {
                if (!(indexEntry.getKey() instanceof UUID candidateOwner)
                        || indexEntry.getValue() == null)
                    continue;

                Object candidateIndex = indexEntry.getValue();
                Field worldsField = candidateIndex.getClass().getDeclaredField("worlds");
                worldsField.setAccessible(true);
                Object worlds = worldsField.get(candidateIndex);
                if (!(worlds instanceof java.util.Map<?, ?> worldMap))
                    continue;

                for (java.util.Map.Entry<?, ?> entry : worldMap.entrySet())
                {
                    if (entry.getValue() instanceof java.util.Collection<?> names
                            && names.stream().anyMatch(name -> targetWorld.equals(String.valueOf(name))))
                    {
                        ownerId = candidateOwner;
                        worldIndex = candidateIndex;
                        targetNumber = entry.getKey();
                        break;
                    }
                }
                if (ownerId != null)
                    break;
            }

            // The world is not registered as a PerfectSelfWorld home.
            if (ownerId == null || worldIndex == null)
                return true;

            if (player.getUniqueId().equals(ownerId))
                return true;

            // Resolve the target home number from the index, then use PSW's own
            // WorldInfo member list so trusted/co-owner players are allowed.
            if (targetNumber != null)
            {
                try
                {
                    Class<?> nodeUtilClass = Class.forName("com.yxmax.perfectSelfWorld.util.NodeUtil");
                    Object playerInfoManager = nodeUtilClass.getField("playerInfoManager").get(null);
                    Method getWorldInfo = playerInfoManager.getClass().getMethod("getWorldInfo", String.class, int.class);
                    Object worldInfo = getWorldInfo.invoke(playerInfoManager, ownerId.toString(),
                            ((Number) targetNumber).intValue());
                    if (worldInfo != null)
                    {
                        Method isMember = worldInfo.getClass().getMethod("isMember", String.class);
                        return Boolean.TRUE.equals(isMember.invoke(worldInfo, player.getUniqueId().toString()));
                    }
                }
                catch (ReflectiveOperationException ignored)
                {
                    return false;
                }
            }
            return false;
        }
        catch (ReflectiveOperationException | IllegalArgumentException | LinkageError exception)
        {
            Main.inst.getLogger().warning("PerfectSelfWorld home ownership check is unavailable: "
                    + exception.getMessage());
            return false;
        }
    }
}
