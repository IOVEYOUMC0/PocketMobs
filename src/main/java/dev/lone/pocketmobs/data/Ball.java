package dev.lone.pocketmobs.data;

import dev.lone.pocketmobs.CaughtMob;
import dev.lone.pocketmobs.Constants;
import dev.lone.pocketmobs.Settings;
import dev.lone.pocketmobs.Utils;
import dev.lone.pocketmobs.Main;
import dev.lone.pocketmobs.utils.EntityUtil;
import dev.lone.pocketmobs.utils.InvUtil;
import dev.lone.pocketmobs.utils.HeadUtils;
import dev.lone.pocketmobs.utils.LocaleUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Ball
{
    public String name;
    public String displayName;
    public String headTexture;
    public Material itemMaterial = Material.PLAYER_HEAD;
    public int customModelData = -1;
    public List<String> catchableEntities;
    public List<String> blacklistedEntities;

    public int maxUsages = 1;
    public boolean unlimitedUsages;
    public double catchSuccessPercentage = 100;

    public double buyPrice = -1;
    public int buyAmount = -1;

    public BallEffect catchEffect;
    public BallEffect freeEffect;
    public BallEffect missedEffect;
    public BallEffect notSpawnedEffect;
    public ShapedRecipe recipe;

    public List<String> lore;
    private List<String> configuredLore = new ArrayList<>();
    private List<String> configuredFilledLore = new ArrayList<>();

    public List<String> catchableMobsStringList;
    public List<Component> catchableMobsLoreComponents;
    
    // Cache a stable UUID so client-side head texture caching stays consistent.
    private UUID cachedTextureUuid;
    private ItemStack cachedItemTemplate;

    private static NamespacedKey key(String key)
    {
        return new NamespacedKey("pocketmobs", key.toLowerCase());
    }

    // hasItemMeta() can be true while getItemMeta() still returns null on some
    // server forks, so resolve the container in one null-safe place.
    private static PersistentDataContainer pdcOrNull(ItemStack itemStack)
    {
        if (itemStack == null || !itemStack.hasItemMeta())
            return null;
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null ? meta.getPersistentDataContainer() : null;
    }

    public Ball(String name, String headTexture)
    {
        this.name = name;
        this.headTexture = headTexture;
        this.catchableEntities = new ArrayList<>();
        this.blacklistedEntities = new ArrayList<>();

        this.lore = new ArrayList<>();
        this.catchableMobsLoreComponents = new ArrayList<>();
        
        // Derive a stable UUID from the ball name so the head texture cache can be reused.
        this.cachedTextureUuid = UUID.nameUUIDFromBytes(("PocketMobs:" + name).getBytes());

    }
    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    public void setItemMaterial(Material itemMaterial)
    {
        this.itemMaterial = itemMaterial != null ? itemMaterial : Material.PLAYER_HEAD;
        this.cachedItemTemplate = null;
    }

    public int getCustomModelData()
    {
        return customModelData;
    }

    public void setCustomModelData(int customModelData)
    {
        this.customModelData = customModelData;
        this.cachedItemTemplate = null;
    }

    public void setRecipe(ShapedRecipe recipe)
    {
        this.recipe = recipe;
    }

    public void setMaxUsages(int maxUsages)
    {
        this.maxUsages = maxUsages;
    }

    public void setUnlimitedUsages(boolean unlimitedUsages)
    {
        this.unlimitedUsages = unlimitedUsages;
    }

    public void setCatchSuccessPercentage(double catchSuccessPercentage)
    {
        this.catchSuccessPercentage = catchSuccessPercentage;
    }

    public void setLore(List<String> lore)
    {
        this.lore = lore;
    }

    public void setConfiguredLore(List<String> lore)
    {
        this.configuredLore = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
    }

    public void setConfiguredFilledLore(List<String> lore)
    {
        this.configuredFilledLore = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
    }

    private List<Component> getConfiguredFilledLore(ItemStack itemStack, CaughtMob caughtMob, String overrideName)
    {
        List<Component> result = new ArrayList<>();
        if (configuredFilledLore == null || configuredFilledLore.isEmpty()) return result;

        String mobName = overrideName != null ? overrideName : "{mob-name-component}";
        List<String> detailLines = caughtMob.getDisplayLoreLines();
        for (String line : configuredFilledLore)
        {
            String rendered = line.replace("{usages}", String.valueOf(getUsages(itemStack)))
                    .replace("{max-usages}", String.valueOf(maxUsages))
                    .replace("{catch-chance}", getCatchSuccess(itemStack) + "%")
                    .replace("{mob-life}", String.valueOf(caughtMob.getLife()))
                    .replace("{mob-name}", mobName);
            if (Settings.lang != null)
            {
                rendered = rendered.replace("{lore-info}", Settings.lang.getColored("lore-info"))
                        .replace("{lore-catch-chance}", Settings.lang.getColored("lore-catch-chance"))
                        .replace("{lore-usages}", Settings.lang.getColored("lore-usages"))
                        .replace("{lore-usage}", Settings.lang.getColored("lore-usage"))
                        .replace("{lore-throw}", Settings.lang.getColored("lore-throw"))
                        .replace("{lore-release}", Settings.lang.getColored("lore-release"))
                        .replace("{lore-mob-name}", Settings.lang.getColored("lore-mob-name"))
                        .replace("{lore-mob-details}", Settings.lang.getColored("lore-mob-details"))
                        .replace("{lore-mob-life}", Settings.lang.getColored("lore-mob-life"));
            }
            if (rendered.contains("{mob-details}"))
            {
                String prefix = rendered.replace("{mob-details}", "");
                for (String detailLine : detailLines)
                {
                    result.add(parseLoreLine(prefix + detailLine));
                }
            }
            else if (rendered.contains("{mob-name-component}"))
            {
                String prefix = rendered.replace("{mob-name-component}", "");
                result.add(parseLoreLine(prefix).append(LocaleUtils.getEntityTranslatable(caughtMob.getType())
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            }
            else
            {
                result.add(parseLoreLine(rendered));
            }
        }
        return result;
    }

    private List<Component> getConfiguredLore(ItemStack itemStack)
    {
        List<Component> result = new ArrayList<>();
        if (configuredLore == null || configuredLore.isEmpty()) return result;
        for (String line : configuredLore)
        {
            String rendered = line.replace("{usages}", String.valueOf(getUsages(itemStack)))
                    .replace("{max-usages}", String.valueOf(maxUsages))
                    .replace("{catch-chance}", getCatchSuccess(itemStack) + "%");
            if (Settings.lang != null)
            {
                rendered = rendered.replace("{lore-info}", Settings.lang.getColored("lore-info"))
                        .replace("{lore-catch-chance}", Settings.lang.getColored("lore-catch-chance"))
                        .replace("{lore-usages}", Settings.lang.getColored("lore-usages"))
                        .replace("{lore-catchable}", Settings.lang.getColored("lore-catchable"))
                        .replace("{lore-usage}", Settings.lang.getColored("lore-usage"))
                        .replace("{lore-throw}", Settings.lang.getColored("lore-throw"))
                        .replace("{lore-release}", Settings.lang.getColored("lore-release"));
            }
            if (rendered.contains("{catchable-mobs}"))
            {
                String prefix = rendered.replace("{catchable-mobs}", "");
                if (catchableMobsLoreComponents != null && !catchableMobsLoreComponents.isEmpty())
                {
                    for (Component mobComponent : catchableMobsLoreComponents)
                    {
                        result.add(parseLoreLine(prefix).append(mobComponent));
                    }
                }
                else
                {
                    result.add(parseLoreLine(prefix));
                }
            }
            else
            {
                result.add(parseLoreLine(rendered));
            }
        }
        return result;
    }

    private static Component parseLoreLine(String line)
    {
        if (line != null && line.matches(".*<[#/]?[a-zA-Z0-9_:.#-]+>.*"))
        {
            try
            {
                return MiniMessage.miniMessage().deserialize(line)
                        .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
            }
            catch (Exception ignored)
            {
            }
        }
        String legacyLine = ChatColor.translateAlternateColorCodes('&', line == null ? "" : line);
        return LegacyComponentSerializer.legacySection().deserialize(legacyLine)
                .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public void setBuyPrice(double buyPrice)
    {
        this.buyPrice = buyPrice;
    }

    public void addCatchableEntity(String type)
    {
        this.catchableEntities.add(type);
    }

    public void addBlacklistedEntity(String type)
    {
        this.blacklistedEntities.add(type);
    }

    public ItemStack getItemStack()
    {
        ItemStack item = getBaseItemTemplate().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
        {
            return item;
        }
        
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(key("PBName"), PersistentDataType.STRING, name);
        pdc.set(key("PBIsPokeyball"), PersistentDataType.BOOLEAN, true);
        pdc.set(key("PBMaxUsages"), PersistentDataType.INTEGER, maxUsages);
        pdc.set(key("PBUsages"), PersistentDataType.INTEGER, maxUsages);
        pdc.set(key("PBCatchSuccess"), PersistentDataType.DOUBLE, catchSuccessPercentage);
        
        item.setItemMeta(meta);
        // Build empty-ball lore through the same code path used at runtime so
        // newly created balls remain stack-compatible with updated ones.
        return applyEmptyLore(item);
    }

    private ItemStack getBaseItemTemplate()
    {
        if (cachedItemTemplate != null)
        {
            return cachedItemTemplate;
        }

        ItemStack item = new ItemStack(itemMaterial, 1);
        if (itemMaterial == Material.PLAYER_HEAD && headTexture != null && !headTexture.isEmpty())
        {
            item = HeadUtils.setSkullOwner(item, cachedTextureUuid, headTexture);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null)
        {
            if (displayName != null)
            {
                // Faithfully translate the stored legacy (§/&) display name to a
                // Component so rendering matches the deprecated setDisplayName path.
                // Custom name components render italic unless explicitly disabled,
                // so force italic off unless the display-name itself asks for §o.
                meta.displayName(LegacyComponentSerializer.legacySection().deserialize(displayName)
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            if (customModelData >= 0)
            {
                meta.setCustomModelData(customModelData);
            }
            item.setItemMeta(meta);
        }

        cachedItemTemplate = item;
        return cachedItemTemplate;
    }

    public static String getName(ItemStack itemStack)
    {
        PersistentDataContainer pdc = pdcOrNull(itemStack);
        if (pdc == null) return null;
        if (pdc.has(key("PBName"), PersistentDataType.STRING))
            return pdc.get(key("PBName"), PersistentDataType.STRING);
        return null;
    }

    public static int getMaxUsages(ItemStack itemStack)
    {
        PersistentDataContainer pdc = pdcOrNull(itemStack);
        if (pdc == null) return 1;
        if (pdc.has(key("PBMaxUsages"), PersistentDataType.INTEGER))
            return pdc.get(key("PBMaxUsages"), PersistentDataType.INTEGER);
        return 1;
    }

    public static int getUsages(ItemStack itemStack)
    {
        PersistentDataContainer pdc = pdcOrNull(itemStack);
        if (pdc == null) return 1;
        if (pdc.has(key("PBUsages"), PersistentDataType.INTEGER))
            return pdc.get(key("PBUsages"), PersistentDataType.INTEGER);
        return 1;
    }

    public static double getCatchSuccess(ItemStack itemStack)
    {
        PersistentDataContainer pdc = pdcOrNull(itemStack);
        if (pdc == null) return 100;
        if (pdc.has(key("PBCatchSuccess"), PersistentDataType.DOUBLE))
            return pdc.get(key("PBCatchSuccess"), PersistentDataType.DOUBLE);
        return 100;
    }

    public static ItemStack reduceUsages(ItemStack itemStack, int amount)
    {
        return reduceUsages(itemStack, amount, null);
    }

    public static ItemStack reduceUsages(ItemStack itemStack, int amount, Entity entity)
    {
        if (itemStack == null || !itemStack.hasItemMeta()) return itemStack;
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return itemStack;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(key("PBUsages"), PersistentDataType.INTEGER))
        {
            int currentUsages = pdc.get(key("PBUsages"), PersistentDataType.INTEGER);
            int newUsages = Math.max(0, currentUsages - amount);
            pdc.set(key("PBUsages"), PersistentDataType.INTEGER, newUsages);
            itemStack.setItemMeta(meta);
        }
        return Ball.updateLore(itemStack, entity);
    }

    public static boolean is(ItemStack item)
    {
        if (item == null || item.getType() == Material.AIR)
            return false;

        PersistentDataContainer pdc = pdcOrNull(item);
        if (pdc == null)
            return false;
        if (pdc.has(key("PBIsPokeyball"), PersistentDataType.BOOLEAN))
            return pdc.get(key("PBIsPokeyball"), PersistentDataType.BOOLEAN);
        return false;
    }

    public static boolean removeItalicFormatting(ItemStack item)
    {
        if (!is(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        boolean changed = false;
        Component displayName = meta.displayName();
        if (displayName != null)
        {
            Component updatedName = displayName.decoration(TextDecoration.ITALIC, false);
            if (!updatedName.equals(displayName))
            {
                meta.displayName(updatedName);
                changed = true;
            }
        }
        List<Component> lore = meta.lore();
        if (lore != null && !lore.isEmpty())
        {
            List<Component> updatedLore = lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList();
            if (!updatedLore.equals(lore))
            {
                meta.lore(updatedLore);
                changed = true;
            }
        }
        if (changed) item.setItemMeta(meta);
        return changed;
    }

    public static boolean hasMob(ItemStack item)
    {
        if (item == null || item.getType() == Material.AIR)
            return false;

        PersistentDataContainer pdc = pdcOrNull(item);
        if (pdc == null)
            return false;
        return pdc.has(key("PBMobData"), PersistentDataType.TAG_CONTAINER);
    }

    public static EntityType getStoredMobType(ItemStack item)
    {
        PersistentDataContainer pdc = pdcOrNull(item);
        if (pdc == null || !pdc.has(key("PBMobData"), PersistentDataType.TAG_CONTAINER))
            return null;

        CaughtMob caughtMob = CaughtMob.readFrom(pdc.get(key("PBMobData"), PersistentDataType.TAG_CONTAINER));
        return caughtMob != null ? caughtMob.getType() : null;
    }


    public static ItemStack catchMob(ItemStack ballItemStack, Entity mob)
    {
        CaughtMob caughtMob = new CaughtMob(mob);

        ItemMeta meta = ballItemStack.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        int dataSize = caughtMob.approxDataSize();
        if (dataSize > Constants.MAX_DATA_SIZE)
        {
            Main.inst.getLogger().warning("Caught mob data exceeds the configured size limit and will not be stored.");
            Main.inst.getLogger().warning("Mob type: " + mob.getType() + ", data size: " + dataSize);
            return null;
        }

        // Store the mob as a structured PDC sub-container (TAG_CONTAINER).
        PersistentDataContainer sub = pdc.getAdapterContext().newPersistentDataContainer();
        caughtMob.writeTo(sub);
        pdc.set(key("PBMobData"), PersistentDataType.TAG_CONTAINER, sub);
        ballItemStack.setItemMeta(meta);

        updateLore(ballItemStack, mob, caughtMob);

        return ballItemStack;
    }

    public static Entity freeMob(Item drop, Location location)
    {
        if (drop == null) return null;
        return freeMob(drop.getItemStack(), location, () -> clearStoredMobData(drop));
    }

    public static Entity freeMob(ItemStack ballItemStack, Location location)
    {
        return freeMob(ballItemStack, location, () -> clearStoredMobData(ballItemStack));
    }

    private static Entity freeMob(ItemStack ballItemStack, Location location, Runnable clearAction)
    {
        PersistentDataContainer pdc = pdcOrNull(ballItemStack);
        if (pdc == null) return null;

        if (!pdc.has(key("PBMobData"), PersistentDataType.TAG_CONTAINER))
            return null;
        CaughtMob caughtMob = CaughtMob.readFrom(pdc.get(key("PBMobData"), PersistentDataType.TAG_CONTAINER));

        if (caughtMob == null)
        {
            Entity placeholder = CaughtMob.spawnPlaceholderPig(location, "Stored mob data could not be parsed");
            if (placeholder != null)
            {
                clearAction.run();
            }
            return placeholder;
        }

        Entity spawned = caughtMob.spawnEntity(location);
        if (spawned != null)
        {
            clearAction.run();
        }
        return spawned;
    }

    private static void clearStoredMobData(Item drop)
    {
        ItemStack itemStack = drop.getItemStack();
        if (!itemStack.hasItemMeta()) return;

        ItemMeta meta = itemStack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(key("PBMobData"));
        itemStack.setItemMeta(meta);
        drop.setItemStack(updateLore(itemStack));
    }

    private static void clearStoredMobData(ItemStack itemStack)
    {
        if (itemStack == null || !itemStack.hasItemMeta()) return;

        ItemMeta meta = itemStack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(key("PBMobData"));
        itemStack.setItemMeta(meta);
        updateLore(itemStack);
    }

    public static ItemStack updateLore(ItemStack ballItemStack)
    {
        return updateLore(ballItemStack, null);
    }

    public static ItemStack updateLore(ItemStack ballItemStack, Entity entity)
    {
        return updateLore(ballItemStack, entity, null);
    }

    public static ItemStack updateLore(ItemStack ballItemStack, Entity entity, CaughtMob caughtMob)
    {
        sanitizeStackMetadata(ballItemStack);

        if (hasMob(ballItemStack))
        {
            if (caughtMob == null)
            {
                PersistentDataContainer pdc = pdcOrNull(ballItemStack);
                if (pdc == null) return ballItemStack;
                if (pdc.has(key("PBMobData"), PersistentDataType.TAG_CONTAINER))
                {
                    caughtMob = CaughtMob.readFrom(pdc.get(key("PBMobData"), PersistentDataType.TAG_CONTAINER));
                }
            }

            if (caughtMob == null) return ballItemStack;

            String overrideName = null;
            if (entity != null && entity.getCustomName() != null)
            {
                String customName = EntityUtil.getReadableEntityTypeName(entity, true);
                if (!customName.equals(EntityUtil.getReadableEntityTypeName(entity.getType())))
                {
                    overrideName = customName;
                }
            }
            List<String> detailLines = caughtMob.getDisplayLoreLines();

            Ball original = Main.inst.ballsManager.byItemStack(ballItemStack);
            if (original != null && original.configuredFilledLore != null && !original.configuredFilledLore.isEmpty())
            {
                ItemMeta meta = ballItemStack.getItemMeta();
                if (meta != null)
                {
                    meta.lore(original.getConfiguredFilledLore(ballItemStack, caughtMob, overrideName));
                    ballItemStack.setItemMeta(meta);
                    return ballItemStack;
                }
            }

            List<Component> loreComponents = new ArrayList<>();
            loreComponents.add(Component.text(Settings.lang.getColored("usages").replace("{value}", getUsages(ballItemStack) + "")));
            loreComponents.add(Component.text(Settings.lang.getColored("catch-chance").replace("{value}", getCatchSuccess(ballItemStack) + "%")));
            if (overrideName != null)
            {
                loreComponents.add(Component.text(Settings.lang.getColored("mob-name-line").replace("{value}", overrideName)));
            }
            else
            {
                loreComponents.add(
                        Component.text(Settings.lang.getColored("mob-name-prefix"))
                                .append(
                                        LocaleUtils.getEntityTranslatable(caughtMob.getType())
                                                .color(NamedTextColor.GRAY)
                                                .decoration(TextDecoration.ITALIC, false)
                                )
                );
            }
            for (String line : detailLines)
            {
                loreComponents.add(Component.text(line));
            }
            loreComponents.add(Component.text(Settings.lang.getColored("mob-life").replace("{value}", caughtMob.getLife() + "")));

            try
            {
                loreComponents.replaceAll(component -> component.decoration(TextDecoration.ITALIC, false));
                ItemMeta meta = ballItemStack.getItemMeta();
                meta.lore(loreComponents);
                ballItemStack.setItemMeta(meta);
            }
            catch (LinkageError | RuntimeException e)
            {
                List<String> lore = new ArrayList<>();
                lore.add(Settings.lang.getColored("usages").replace("{value}", getUsages(ballItemStack) + ""));
                lore.add(Settings.lang.getColored("catch-chance").replace("{value}", getCatchSuccess(ballItemStack) + "%"));
                lore.addAll(detailLines);
                lore.add(Settings.lang.getColored("mob-life").replace("{value}", caughtMob.getLife() + ""));
                InvUtil.setItemStackLore(ballItemStack, lore);
            }
        }
        else
        {
            Ball original = Main.inst.ballsManager.byItemStack(ballItemStack);

            // Empty balls must always use their configured lore, including after
            // a missed throw or after releasing a captured mob.
            if (original != null && original.configuredLore != null && !original.configuredLore.isEmpty())
            {
                ItemMeta meta = ballItemStack.getItemMeta();
                if (meta != null)
                {
                    meta.lore(original.getConfiguredLore(ballItemStack));
                    ballItemStack.setItemMeta(meta);
                    return ballItemStack;
                }
            }

            if (original != null && original.catchableMobsLoreComponents != null && !original.catchableMobsLoreComponents.isEmpty())
            {
                List<Component> loreComponents = new ArrayList<>();
                loreComponents.add(Component.text(Settings.lang.getColored("usages").replace("{value}", getUsages(ballItemStack) + "")));
                loreComponents.add(Component.text(Settings.lang.getColored("catch-chance").replace("{value}", getCatchSuccess(ballItemStack) + "%")));
                loreComponents.add(Component.text(Settings.lang.getColored("catchable-mobs")));
                loreComponents.addAll(original.catchableMobsLoreComponents);

                try
                {
                    loreComponents.replaceAll(component -> component.decoration(TextDecoration.ITALIC, false));
                    ItemMeta meta = ballItemStack.getItemMeta();
                    meta.lore(loreComponents);
                    ballItemStack.setItemMeta(meta);
                }
                catch (LinkageError | RuntimeException e)
                {
                    List<String> lore = new ArrayList<>();
                    lore.add(Settings.lang.getColored("usages").replace("{value}", getUsages(ballItemStack) + ""));
                    lore.add(Settings.lang.getColored("catch-chance").replace("{value}", getCatchSuccess(ballItemStack) + "%"));
                    lore.add(Settings.lang.getColored("catchable-mobs"));

                    if (original.catchableMobsStringList != null)
                    {
                        lore.addAll(original.catchableMobsStringList);
                    }

                    InvUtil.setItemStackLore(ballItemStack, lore);
                }
            }
            else
            {
                List<String> lore = new ArrayList<>();
                lore.add(Settings.lang.getColored("usages").replace("{value}", getUsages(ballItemStack) + ""));
                lore.add(Settings.lang.getColored("catch-chance").replace("{value}", getCatchSuccess(ballItemStack) + "%"));
                lore.add(Settings.lang.getColored("catchable-mobs"));
                
                if (original != null && original.catchableMobsStringList != null)
                {
                    lore.addAll(original.catchableMobsStringList);
                }

                InvUtil.setItemStackLore(ballItemStack, lore);
            }
        }
        return ballItemStack;
    }

    private ItemStack applyEmptyLore(ItemStack ballItemStack)
    {
        if (configuredLore != null && !configuredLore.isEmpty())
        {
            ItemMeta meta = ballItemStack.getItemMeta();
            if (meta != null)
            {
                meta.lore(getConfiguredLore(ballItemStack));
                ballItemStack.setItemMeta(meta);
                return ballItemStack;
            }
        }

        if (catchableMobsLoreComponents != null && !catchableMobsLoreComponents.isEmpty())
        {
            List<Component> loreComponents = new ArrayList<>();
            loreComponents.add(Component.text(Settings.lang.getColored("usages").replace("{value}", getUsages(ballItemStack) + "")));
            loreComponents.add(Component.text(Settings.lang.getColored("catch-chance").replace("{value}", getCatchSuccess(ballItemStack) + "%")));
            loreComponents.add(Component.text(Settings.lang.getColored("catchable-mobs")));
            loreComponents.addAll(catchableMobsLoreComponents);

            try
            {
                loreComponents.replaceAll(component -> component.decoration(TextDecoration.ITALIC, false));
                ItemMeta meta = ballItemStack.getItemMeta();
                meta.lore(loreComponents);
                ballItemStack.setItemMeta(meta);
                return ballItemStack;
            }
            catch (LinkageError | RuntimeException ignored)
            {
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add(Settings.lang.getColored("usages").replace("{value}", getUsages(ballItemStack) + ""));
        lore.add(Settings.lang.getColored("catch-chance").replace("{value}", getCatchSuccess(ballItemStack) + "%"));
        lore.add(Settings.lang.getColored("catchable-mobs"));
        if (catchableMobsStringList != null)
        {
            lore.addAll(catchableMobsStringList);
        }
        InvUtil.setItemStackLore(ballItemStack, lore);
        return ballItemStack;
    }

    private static void sanitizeStackMetadata(ItemStack itemStack)
    {
        if (itemStack == null || !itemStack.hasItemMeta())
        {
            return;
        }

        ItemMeta meta = itemStack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(key("PBAntiStackRandom"), PersistentDataType.INTEGER))
        {
            pdc.remove(key("PBAntiStackRandom"));
            itemStack.setItemMeta(meta);
        }
    }

    public void initLore()
    {
        StringBuilder catchableMobsStr = new StringBuilder().append(ChatColor.GRAY);
        List<Component> catchableMobComponents = new ArrayList<>();
        for (String entityType : catchableEntities)
        {
            try
            {
                EntityType type = EntityType.valueOf(entityType);
                catchableMobsStr.append(EntityUtil.getReadableEntityTypeName(type)).append(", ");
                catchableMobComponents.add(Component.text(ChatColor.GRAY + "- ")
                        .append(LocaleUtils.getEntityTranslatable(type))
                        .decoration(TextDecoration.ITALIC, false));
            }
            catch (IllegalArgumentException exc)
            {
                switch (entityType)
                {
                    case "ALL_ANIMALS":
                        catchableMobsStr.append(Settings.lang.getColored("all-animals")).append(", ");
                        catchableMobComponents.add(Component.text(Settings.lang.getColored("all-animals")).decoration(TextDecoration.ITALIC, false));
                        break;
                    case "ALL_MONSTERS":
                        catchableMobsStr.append(Settings.lang.getColored("all-monsters")).append(", ");
                        catchableMobComponents.add(Component.text(Settings.lang.getColored("all-monsters")).decoration(TextDecoration.ITALIC, false));
                        break;
                    case "ALL_FISH":
                        catchableMobsStr.append(Settings.lang.getColored("all-fish")).append(", ");
                        catchableMobComponents.add(Component.text(Settings.lang.getColored("all-fish")).decoration(TextDecoration.ITALIC, false));
                        break;
                    case "ALL_WATER_MOBS":
                        catchableMobsStr.append(Settings.lang.getColored("all-water-mobs")).append(", ");
                        catchableMobComponents.add(Component.text(Settings.lang.getColored("all-water-mobs")).decoration(TextDecoration.ITALIC, false));
                        break;
                    case "ALL_MOBS":
                        catchableMobsStr.append(Settings.lang.getColored("all-mobs")).append(", ");
                        catchableMobComponents.add(Component.text(Settings.lang.getColored("all-mobs")).decoration(TextDecoration.ITALIC, false));
                        break;
                }
            }
        }

        String catchableMobsString = catchableMobsStr.toString();
        if (catchableMobsString.endsWith(", "))
        {
            catchableMobsString = catchableMobsString.substring(0, catchableMobsString.length() - 2);
        }

        String[] lines = catchableMobsString.replaceAll("(?:\\s*)(.{1,27})(?:\\s+|\\s*$)", "$1\n").split("\n");
        List<String> tempList = new ArrayList<>();
        for (String line : lines)
        {
            tempList.add(ChatColor.GRAY + line.trim());
        }
        catchableMobsStringList = tempList;

        catchableMobsLoreComponents = catchableMobComponents;

        List<String> lore = new ArrayList<>();
        lore.add(Settings.lang.getColored("usages").replace("{value}", maxUsages + ""));
        lore.add(Settings.lang.getColored("catch-chance").replace("{value}", catchSuccessPercentage + "%"));
        lore.add(Settings.lang.getColored("catchable-mobs"));
        lore.addAll(catchableMobsStringList);

        setLore(lore);
    }

    public static ItemStack removeMob(ItemStack ball)
    {
        if (!ball.hasItemMeta()) return ball;
        ItemMeta meta = ball.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(key("PBMobData"));
        pdc.remove(key("PBAntiStackRandom"));
        ball.setItemMeta(meta);

        return updateLore(ball);
    }

    public boolean isCatchableMob(Entity entity)
    {
        if (blacklistedEntities.contains(entity.getType().toString()))
            return false;

        if (blacklistedEntities.contains("ALL_MONSTERS") && entity instanceof Monster)
            return false;
        if (blacklistedEntities.contains("ALL_ANIMALS") && entity instanceof Animals)
            return false;
        if (blacklistedEntities.contains("ALL_FISH") && entity instanceof Fish)
            return false;
        if (blacklistedEntities.contains("ALL_WATER_MOBS") && entity instanceof WaterMob)
            return false;


        if (catchableEntities.contains("ALL_MOBS"))
            return true;

        if (catchableEntities.contains(entity.getType().toString()))
            return true;

        if (catchableEntities.contains("ALL_MONSTERS") && entity instanceof Monster)
            return true;
        if (catchableEntities.contains("ALL_ANIMALS") && entity instanceof Animals)
            return true;
        if (catchableEntities.contains("ALL_FISH") && entity instanceof Fish)
            return true;
        if (catchableEntities.contains("ALL_WATER_MOBS") && entity instanceof WaterMob)
            return true;
        return false;
    }

    public void playEffect(BallEffectType ballEffectType, Location location)
    {
        BallEffect ballEffect = null;

        switch (ballEffectType)
        {
            case CATCH:
                ballEffect = catchEffect;
                break;
            case FREE:
                ballEffect = freeEffect;
                break;
            case MISSED:
                ballEffect = missedEffect;
                break;
            case NOT_SPAWNED:
                ballEffect = notSpawnedEffect;
                break;
        }

        spawnParticle(ballEffect, location);
        playSound(ballEffect, location);
    }

    public void spawnParticle(BallEffect ballEffect, Location location)
    {
        if (ballEffect == null || ballEffect.particleData == null) return;
        ballEffect.particleData.spawn(location);
    }

    public void playSound(BallEffect ballEffect, Location location)
    {
        if (ballEffect == null || ballEffect.soundData == null) return;
        if (location.getWorld() == null) return;
        location.getWorld().playSound(location,
                ballEffect.soundData.sound,
                ballEffect.soundData.volume,
                ballEffect.soundData.pitch);
    }

    public boolean rollDice()
    {
        // Always succeed when the configured chance is 100% or above.
        if (catchSuccessPercentage >= 100)
            return true;
        // Always fail when the configured chance is 0% or below.
        if (catchSuccessPercentage <= 0)
            return false;
        // Use an inclusive 1-100 roll so percentage values map directly to success chance.
        return Utils.getRandomInt(1, 100) <= catchSuccessPercentage;
    }
}
