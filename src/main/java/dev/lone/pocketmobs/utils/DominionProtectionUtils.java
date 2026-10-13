package dev.lone.pocketmobs.utils;

import dev.lone.pocketmobs.Main;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional Dominion integration without a compile-time dependency.
 */
public final class DominionProtectionUtils
{
    private static final String DOMINION_PLUGIN = "Dominion";
    private static final String API_CLASS = "cn.lunadeer.dominion.api.DominionAPI";
    private static final String PRI_FLAG_CLASS = "cn.lunadeer.dominion.api.dtos.flag.PriFlag";
    private static final String FLAGS_CLASS = "cn.lunadeer.dominion.api.dtos.flag.Flags";

    private static Method getInstanceMethod;
    private static Method getDominionMethod;
    private static Method getPrivilegeMethod;
    private static Field animalKillingFlag;
    private static Field monsterKillingFlag;
    private static Field villagerKillingFlag;
    private static boolean apiResolved;
    private static boolean errorLogged;

    private DominionProtectionUtils()
    {
    }

    /**
     * Uses Dominion's killing privilege for the mob category being captured.
     */
    public static boolean canCatchMob(Player player, Entity target, Location location)
    {
        Plugin dominion = Main.inst.getServer().getPluginManager().getPlugin(DOMINION_PLUGIN);
        if (dominion == null || !dominion.isEnabled())
            return true;

        try
        {
            resolveApi();
            if (getInstanceMethod == null || getDominionMethod == null || getPrivilegeMethod == null)
                return true;

            Object api = getInstanceMethod.invoke(null);
            Object dominionAtLocation = getDominionMethod.invoke(api, location);
            if (dominionAtLocation == null)
                return true;

            Field flagField = target instanceof Villager
                    ? villagerKillingFlag
                    : target instanceof Monster ? monsterKillingFlag : animalKillingFlag;
            Object flag = flagField.get(null);
            return (boolean) getPrivilegeMethod.invoke(api, location, flag, player);
        }
        catch (ReflectiveOperationException | ClassCastException | LinkageError exception)
        {
            logIntegrationError(exception);
            return true;
        }
    }

    public static boolean canReleaseMob(Player player, EntityType entityType, Location location)
    {
        Plugin dominion = Main.inst.getServer().getPluginManager().getPlugin(DOMINION_PLUGIN);
        if (dominion == null || !dominion.isEnabled())
            return true;

        try
        {
            resolveApi();
            if (getInstanceMethod == null || getDominionMethod == null || getPrivilegeMethod == null)
                return true;

            Object api = getInstanceMethod.invoke(null);
            Object dominionAtLocation = getDominionMethod.invoke(api, location);
            if (dominionAtLocation == null)
                return true;

            Field flagField = entityType == EntityType.VILLAGER
                    ? villagerKillingFlag
                    : isMonster(entityType) ? monsterKillingFlag : animalKillingFlag;

            // Monsters may only be released in the releaser's own Dominion.
            // This restriction is intentionally stronger than the monster-killing
            // privilege: guest damage permission must not allow monster spawning in
            // another player's territory.
            if (isMonster(entityType))
            {
                Method ownerMethod = dominionAtLocation.getClass().getMethod("getOwner");
                Object owner = ownerMethod.invoke(dominionAtLocation);
                boolean ownerOrCoOwner = owner instanceof java.util.UUID ownerId
                        && ownerId.equals(player.getUniqueId());

                if (!ownerOrCoOwner)
                {
                    // Dominion has no universal "co-owner" API. A member assigned
                    // to a group with the monster-killing privilege is treated as a
                    // co-owner for this monster-release restriction.
                    Method getMember = api.getClass().getMethod("getMember",
                            dominionAtLocation.getClass().getInterfaces()[0], Player.class);
                    Object member = getMember.invoke(api, dominionAtLocation, player);
                    if (member != null)
                    {
                        Method getGroup = api.getClass().getMethod("getGroup", member.getClass().getInterfaces()[0]);
                        Object group = getGroup.invoke(api, member);
                        if (group != null)
                        {
                            Method getGroupFlag = group.getClass().getMethod("getFlagValue", flagField.getType());
                            ownerOrCoOwner = Boolean.TRUE.equals(getGroupFlag.invoke(group, flagField.get(null)));
                        }
                    }
                }

                if (!ownerOrCoOwner)
                    return false;
            }

            Object flag = flagField.get(null);
            return (boolean) getPrivilegeMethod.invoke(api, location, flag, player);
        }
        catch (ReflectiveOperationException | ClassCastException | LinkageError exception)
        {
            logIntegrationError(exception);
            return true;
        }
    }

    private static boolean isMonster(EntityType entityType)
    {
        try
        {
            Class<?> monsterClass = entityType.getEntityClass();
            return monsterClass != null && Monster.class.isAssignableFrom(monsterClass);
        }
        catch (LinkageError ignored)
        {
            return false;
        }
    }

    private static synchronized void resolveApi() throws ReflectiveOperationException
    {
        if (apiResolved)
            return;

        apiResolved = true;
        Class<?> apiClass = Class.forName(API_CLASS);
        getInstanceMethod = apiClass.getMethod("getInstance");
        getDominionMethod = apiClass.getMethod("getDominion", Location.class);
        getPrivilegeMethod = apiClass.getMethod("checkPrivilegeFlag", Location.class,
                Class.forName(PRI_FLAG_CLASS), Player.class);

        Class<?> flagsClass = Class.forName(FLAGS_CLASS);
        animalKillingFlag = flagsClass.getField("ANIMAL_KILLING");
        monsterKillingFlag = flagsClass.getField("MONSTER_KILLING");
        villagerKillingFlag = flagsClass.getField("VILLAGER_KILLING");
    }

    private static void logIntegrationError(Throwable exception)
    {
        if (!errorLogged)
        {
            errorLogged = true;
            Main.inst.getLogger().warning("Dominion integration is unavailable: " + exception.getMessage());
        }
    }
}
