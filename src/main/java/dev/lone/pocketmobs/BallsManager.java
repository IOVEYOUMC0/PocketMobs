package dev.lone.pocketmobs;

import dev.lone.pocketmobs.data.Ball;
import dev.lone.pocketmobs.data.BallEffect;
import dev.lone.pocketmobs.data.ParticleData;
import dev.lone.pocketmobs.data.SoundData;
import dev.lone.pocketmobs.utils.CustomConfigFile;
import dev.lone.pocketmobs.utils.HeadUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class BallsManager
{
    private static final int CURRENT_BALLS_CONFIG_VERSION = 2;
    Plugin plugin;

    public CustomConfigFile config;
    public List<Ball> balls;
    public HashMap<String, Ball> ballsByName;

    BallEffect defaultCatchConfig;
    BallEffect defaultFreeConfig;
    BallEffect defaultMissedConfig;
    BallEffect defaultNotSpawnedConfig;

    public BallsManager(Plugin plugin)
    {
        this.plugin = plugin;
        config = new CustomConfigFile(this.plugin, "balls", false, false);
        migrateConfig();

        reload();
    }

    /** Adds new bundled ball settings without overwriting server customizations. */
    private void migrateConfig()
    {
        int version = config.getInt("balls-config-version", 1);
        if (version >= CURRENT_BALLS_CONFIG_VERSION)
        {
            return;
        }

        try (InputStream resource = plugin.getResource("balls.yml"))
        {
            if (resource == null)
            {
                plugin.getLogger().warning("Bundled balls.yml is unavailable; skipping balls config migration.");
                return;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resource, StandardCharsets.UTF_8));
            mergeMissingValues(defaults, config.getConfig(), "");
            config.set("balls-config-version", CURRENT_BALLS_CONFIG_VERSION);
            config.save();
            config.reloadFromFile();
            plugin.getLogger().info("Balls configuration migrated to v" + CURRENT_BALLS_CONFIG_VERSION + ".");
        }
        catch (Exception e)
        {
            plugin.getLogger().warning("Failed to migrate balls.yml: " + e.getMessage());
        }
    }

    private void mergeMissingValues(ConfigurationSection source, ConfigurationSection target, String path)
    {
        for (String key : source.getKeys(false))
        {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            Object sourceValue = source.get(key);
            if (sourceValue instanceof ConfigurationSection sourceSection)
            {
                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection == null)
                {
                    target.createSection(key);
                    targetSection = target.getConfigurationSection(key);
                }
                mergeMissingValues(sourceSection, targetSection, fullPath);
            }
            else if (!target.contains(key))
            {
                target.set(key, sourceValue);
            }
        }
    }

    public void load()
    {
        this.balls = new ArrayList<>();
        this.ballsByName = new HashMap<>();

        ConfigurationSection ballsSection = config.getConfig().getConfigurationSection("balls");
        if (ballsSection == null)
        {
            plugin.getLogger().severe("[PocketMobs] Could not find the 'balls' section in balls.yml.");
            return;
        }

        Set<String> list = ballsSection.getKeys(false);
        for (String key : list)
        {
            if (!config.getBoolean("balls." + key + ".enabled", true))
            {
                continue;
            }

            String headTexture = HeadUtils.normalizeTextureReference(config.getString("balls." + key + ".head-texture"));
            Ball tmp = new Ball(key, headTexture);

            String materialName = config.getString("balls." + key + ".item-material", Material.PLAYER_HEAD.name());
            Material itemMaterial;
            try
            {
                itemMaterial = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException e)
            {
                plugin.getLogger().warning("Ball '" + key + "' has an invalid item-material '" + materialName + "'. Falling back to PLAYER_HEAD.");
                itemMaterial = Material.PLAYER_HEAD;
            }
            tmp.setItemMaterial(itemMaterial);
            tmp.setCustomModelData(config.getInt("balls." + key + ".custom-model-data", -1));

            if (itemMaterial == Material.PLAYER_HEAD && (headTexture == null || headTexture.isEmpty()) && tmp.getCustomModelData() < 0)
            {
                plugin.getLogger().warning("Ball '" + key + "' does not define head-texture or custom-model-data and will use a plain player head.");
            }

            String displayName = config.getString("balls." + key + ".display-name");
            if (displayName != null)
            {
                displayName = ChatColor.translateAlternateColorCodes('&', displayName);
            }
            tmp.setDisplayName(ChatColor.RESET + (displayName != null ? displayName : key));
            tmp.setConfiguredLore(config.getConfig().getStringList("balls." + key + ".lore"));
            tmp.setConfiguredFilledLore(config.getConfig().getStringList("balls." + key + ".filled-lore"));

            if (Settings.config.getBoolean("logic.buy.enabled", true))
            {
                if (Main.economy != null && config.getBoolean("balls." + key + ".buy.enabled", false))
                {
                    double price = config.getDouble("balls." + key + ".buy.price");
                    int amount = config.getInt("balls." + key + ".buy.amount");
                    if (price > 0 && amount >= 1)
                    {
                        tmp.setBuyPrice(price);
                        tmp.buyAmount = amount;
                    }
                    else
                    {
                        plugin.getLogger().warning("Ball '" + key + "' has buy.enabled but an invalid buy.price (" + price + ") or buy.amount (" + amount + "); buy button disabled.");
                    }
                }
            }

            for (String entityType : config.getConfig().getStringList("balls." + key + ".catchable-entities"))
            {
                tmp.addCatchableEntity(entityType);
            }

            for (String entityType : config.getConfig().getStringList("balls." + key + ".blacklisted-entities"))
            {
                tmp.addBlacklistedEntity(entityType);
            }

            loadParticles(tmp, key);
            loadMaxUsages(tmp, key);
            loadCatchSuccessPercentage(tmp, key);

            tmp.initLore();

            if (Settings.config.getBoolean("logic.craft.enabled", true))
            {
                loadRecipe(tmp, key);
            }

            balls.add(tmp);
            ballsByName.put(key, tmp);
            registerBallPermissions(key);
        }
    }

    /**
     * Registers the per-ball user permission nodes with a {@code true} default so
     * regular (non-op) players can craft and buy balls out of the box. Ball names
     * come from balls.yml at runtime, so they cannot be declared statically in
     * plugin.yml; without this, the undeclared nodes fall back to op-only and
     * non-op players silently cannot craft or buy. Server owners can still revoke
     * access with a negative node via any permissions plugin.
     */
    private void registerBallPermissions(String key)
    {
        PluginManager pm = Bukkit.getPluginManager();
        ensureUserPermission(pm, Constants.Permissions.USER_CRAFT + "." + key);
        ensureUserPermission(pm, Constants.Permissions.USER_BUY + "." + key);
    }

    private void ensureUserPermission(PluginManager pm, String node)
    {
        if (pm.getPermission(node) == null)
        {
            pm.addPermission(new Permission(node, PermissionDefault.TRUE));
        }
    }

    public void reload()
    {
        cleanup();
        config.reloadFromFile();

        defaultCatchConfig = loadDefaultEffectsConfig("catch");
        defaultFreeConfig = loadDefaultEffectsConfig("free");
        defaultMissedConfig = loadDefaultEffectsConfig("missed");
        defaultNotSpawnedConfig = loadDefaultEffectsConfig("not_spawned");
        this.load();
    }

    private void loadCatchSuccessPercentage(Ball tmp, String key)
    {
        double value = config.getDouble("balls." + key + ".catch-success", 100);
        if (value < 0)
        {
            plugin.getLogger().warning("Ball '" + key + "' has a negative catch-success value (" + value + "). Using 0 instead.");
            value = 0;
        }
        else if (value > 100)
        {
            plugin.getLogger().warning("Ball '" + key + "' has a catch-success value above 100 (" + value + "). Using 100 instead.");
            value = 100;
        }
        tmp.setCatchSuccessPercentage(value);
    }

    private void loadMaxUsages(Ball tmp, String key)
    {
        if (config.getBoolean("balls." + key + ".unlimited-usages", false))
        {
            tmp.setUnlimitedUsages(true);
            tmp.setMaxUsages(-1);
        }
        else
        {
            int value = config.getInt("balls." + key + ".max-usages", 1);
            if (value < 1)
            {
                plugin.getLogger().warning("Ball '" + key + "' has an invalid max-usages value (" + value + "). Using 1 instead.");
                value = 1;
            }
            tmp.setMaxUsages(value);
        }
    }

    private void loadRecipe(Ball tmp, String key)
    {
        if (!config.hasKey("balls." + key + ".craft-recipe"))
        {
            return;
        }
        if (!config.getBoolean("balls." + key + ".craft-recipe.enabled"))
        {
            return;
        }

        try
        {
            ItemStack copy = tmp.getItemStack().clone();
            copy.setAmount(config.getInt("balls." + key + ".craft-recipe.amount", 1));

            ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, key), copy);

            // shape() throws IllegalArgumentException on a malformed pattern (0 or >3
            // rows, unequal row lengths). It sits inside this try so one bad config
            // skips only that recipe instead of aborting ball loading / plugin enable.
            String[] shape = config.getConfig()
                    .getStringList("balls." + key + ".craft-recipe.pattern")
                    .stream()
                    .map(line -> line.replace('X', ' '))
                    .toArray(String[]::new);
            recipe.shape(shape);

            ConfigurationSection defined = config.getConfig().getConfigurationSection("balls." + key + ".craft-recipe.ingredients");
            if (defined == null)
            {
                plugin.getLogger().warning("Ball '" + key + "' has craft-recipe enabled but no ingredients section.");
                return;
            }

            for (String ingredientKey : defined.getKeys(false))
            {
                if (ingredientKey.charAt(0) == 'X')
                {
                    continue;
                }

                String materialName = defined.getString(ingredientKey);
                Material ingredient = materialName != null ? Material.matchMaterial(materialName) : null;
                if (ingredient == null)
                {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED +
                            (Settings.lang != null
                                    ? Settings.lang.getColored("material-not-found").replace("{material}", String.valueOf(materialName))
                                    : "Material not found: " + materialName));
                    continue;
                }
                recipe.setIngredient(ingredientKey.charAt(0), ingredient);
            }

            try
            {
                Bukkit.addRecipe(recipe);
            }
            catch (IllegalStateException ignored)
            {
                Bukkit.removeRecipe(new NamespacedKey(plugin, key));
                Bukkit.addRecipe(recipe);
            }
            tmp.setRecipe(recipe);
        }
        catch (IllegalArgumentException e)
        {
            plugin.getLogger().warning("Ball '" + key + "' has an invalid craft-recipe (" + e.getMessage() + "). Skipping its recipe.");
        }
    }

    private void loadParticles(Ball tmp, String key)
    {
        tmp.catchEffect = loadEffectsConfig(key, defaultCatchConfig, "catch");
        tmp.freeEffect = loadEffectsConfig(key, defaultFreeConfig, "free");
        tmp.missedEffect = loadEffectsConfig(key, defaultMissedConfig, "missed");
        tmp.notSpawnedEffect = loadEffectsConfig(key, defaultNotSpawnedConfig, "not_spawned");
    }

    public BallEffect loadEffectsConfig(String configKey, BallEffect defaultConfig, String name)
    {
        try
        {
            BallEffect.BallEffectBuilder builder = new BallEffect.BallEffectBuilder();
            if (config.hasKey("balls." + configKey + ".effects." + name + ".particle"))
            {
                String particleName = config.getString("balls." + configKey + ".effects." + name + ".particle.name");
                int amount = config.getInt("balls." + configKey + ".effects." + name + ".particle.amount");

                String colorHex = config.getString("balls." + configKey + ".effects." + name + ".particle.color");

                ParticleData particleData;
                if (colorHex != null && !colorHex.isEmpty())
                {
                    particleData = new ParticleData(particleName, amount, colorHex);
                }
                else
                {
                    particleData = new ParticleData(particleName, amount);
                }

                builder.setParticleConfig(particleData);
            }
            else
            {
                builder.setParticleConfig(defaultConfig.particleData);
            }

            if (config.hasKey("balls." + configKey + ".effects." + name + ".sound"))
            {
                builder.setSoundConfig(new SoundData(
                        config.getString("balls." + configKey + ".effects." + name + ".sound.name"),
                        (float) config.getDouble("balls." + configKey + ".effects." + name + ".sound.volume"),
                        (float) config.getDouble("balls." + configKey + ".effects." + name + ".sound.pitch")
                ));
            }
            else
            {
                builder.setSoundConfig(defaultConfig.soundData);
            }
            return builder.build();
        }
        catch (IllegalArgumentException e)
        {
            Main.inst.getLogger().severe(ChatColor.RED + "[PocketMobs] Invalid particle or sound name in balls.yml for ball '" + configKey + "' effect '" + name + "'.");
            return defaultConfig;
        }
        catch (Exception e)
        {
            Main.inst.getLogger().severe(ChatColor.RED + "[PocketMobs] Failed to load effect '" + name + "' for ball '" + configKey + "': " + e.getMessage());
            return defaultConfig;
        }
    }

    public BallEffect loadDefaultEffectsConfig(String name)
    {
        try
        {
            BallEffect.BallEffectBuilder builder = new BallEffect.BallEffectBuilder();

            String particleName = Settings.config.getString("default.ball.effects." + name + ".particle.name");
            int amount = Settings.config.getInt("default.ball.effects." + name + ".particle.amount");
            String colorHex = Settings.config.getString("default.ball.effects." + name + ".particle.color");

            ParticleData particleData;
            if (colorHex != null && !colorHex.isEmpty())
            {
                particleData = new ParticleData(particleName, amount, colorHex);
            }
            else
            {
                particleData = new ParticleData(particleName, amount);
            }

            builder.setParticleConfig(particleData);
            builder.setSoundConfig(new SoundData(
                    Settings.config.getString("default.ball.effects." + name + ".sound.name"),
                    (float) Settings.config.getDouble("default.ball.effects." + name + ".sound.volume"),
                    (float) Settings.config.getDouble("default.ball.effects." + name + ".sound.pitch")
            ));
            return builder.build();
        }
        catch (IllegalArgumentException e)
        {
            Main.inst.getLogger().severe("[PocketMobs] Invalid default particle or sound name in config.yml for effect '" + name + "'.");
            return new BallEffect(new ParticleData("FLAME", 10), new SoundData("ENTITY_EXPERIENCE_ORB_PICKUP", 1, 1));
        }
    }

    public boolean exists(String string)
    {
        return byKey(string) != null;
    }

    public ItemStack getOriginalItemStack(String string)
    {
        Ball ball = byKey(string);
        return ball != null ? ball.getItemStack() : null;
    }

    public Ball byItemStack(ItemStack ballInstance)
    {
        if (ballInstance == null || !ballInstance.hasItemMeta())
        {
            return null;
        }

        String ballName = Ball.getName(ballInstance);
        if (ballName == null || ballName.isEmpty())
        {
            return null;
        }

        return ballsByName.get(ballName);
    }

    public Ball byKey(String string)
    {
        if (!ballsByName.containsKey(string))
        {
            return null;
        }
        return ballsByName.get(string);
    }

    public List<String> getKeys(String searchWord)
    {
        List<String> names = new ArrayList<>();
        for (Ball entry : balls)
        {
            if (entry.name.contains(searchWord))
            {
                names.add(entry.name);
            }
        }
        return names;
    }

    public List<ItemStack> getRecipeItems(ItemStack item)
    {
        List<ItemStack> items = new ArrayList<>();

        Ball ball = byKey(Ball.getName(item));
        if (ball == null)
        {
            return null;
        }
        ShapedRecipe recipe = ball.recipe;
        if (recipe == null)
        {
            return null;
        }

        Map<Character, ItemStack> chart = recipe.getIngredientMap();
        String[] shape = recipe.getShape();
        for (String line : shape)
        {
            for (char letter : line.toCharArray())
            {
                for (Map.Entry<Character, ItemStack> entry : chart.entrySet())
                {
                    if (letter == entry.getKey())
                    {
                        items.add(entry.getValue());
                    }
                }
            }
        }
        return items;
    }

    public void cleanup()
    {
        if (balls != null)
        {
            for (Ball ball : balls)
            {
                if (ball.recipe != null)
                {
                    try
                    {
                        Bukkit.removeRecipe(ball.recipe.getKey());
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
            balls.clear();
        }

        if (ballsByName != null)
        {
            ballsByName.clear();
        }
    }
}
