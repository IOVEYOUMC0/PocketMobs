package dev.lone.pocketmobs.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Detects mobs that are spawned and managed by pet plugins (MyPet & co.).
 * <p>
 * Those mobs are not plain vanilla mobs: their data lives in the owning plugin,
 * so catching one into a ball would remove it from the plugin and break (or
 * duplicate) the pet. Detection is reflection based so PocketMobs needs no
 * compile-time dependency; classes are resolved lazily and only while the
 * target plugin is actually enabled.
 */
public final class PetProtection
{
    // MyPet wraps every pet with a Bukkit entity implementing this interface
    // (it extends org.bukkit.entity.Creature). Present in MyPet 3.x.
    private static final String MYPET_INTERFACE = "de.Keyle.MyPet.api.entity.MyPetBukkitEntity";

    private static final String MYPET_API = "de.Keyle.MyPet.MyPetApi";
    private static volatile Class<?> myPetInterface;
    private static volatile Method getPetManager;
    private static volatile Method getPetFromEntity;
    private static volatile boolean myPetResolved;

    private PetProtection() { }

    /**
     * @return true if the entity was spawned and is managed by a pet plugin.
     */
    public static boolean isPetPluginMob(Entity entity)
    {
        if (entity == null)
            return false;

        if (Bukkit.getPluginManager().isPluginEnabled("MyPet"))
            return isMyPet(entity);

        return false;
    }

    private static boolean isMyPet(Entity entity)
    {
        if (!myPetResolved)
        {
            synchronized (PetProtection.class)
            {
                if (!myPetResolved)
                {
                    try
                    {
                        // Resolve through MyPet's own classloader: the interface is
                        // shipped inside the MyPet plugin, not inside PocketMobs.
                        Plugin myPet = Bukkit.getPluginManager().getPlugin("MyPet");
                        if (myPet != null)
                        {
                            ClassLoader classLoader = myPet.getClass().getClassLoader();
                            try
                            {
                                myPetInterface = Class.forName(MYPET_INTERFACE, false, classLoader);
                            }
                            catch (ClassNotFoundException ignored)
                            {
                                // MyPet 4 removed the old Bukkit entity interface.
                            }

                            try
                            {
                                Class<?> api = Class.forName(MYPET_API, false, classLoader);
                                getPetManager = api.getMethod("getPetManager");
                                Class<?> managerType = getPetManager.getReturnType();
                                getPetFromEntity = managerType.getMethod("getPetFromEntity", Entity.class);
                            }
                            catch (ReflectiveOperationException ignored)
                            {
                                // Older MyPet versions are covered by the interface check above.
                            }
                        }
                    }
                    catch (Throwable ignored)
                    {
                        myPetInterface = null;
                    }
                    myPetResolved = true;
                }
            }
        }
        try
        {
            if (getPetManager != null && getPetFromEntity != null)
            {
                Object manager = getPetManager.invoke(null);
                if (manager != null && getPetFromEntity.invoke(manager, entity) != null)
                    return true;
            }
        }
        catch (Throwable ignored)
        {
            // MyPet may still be initializing or may expose an incompatible API.
        }

        return myPetInterface != null && myPetInterface.isInstance(entity);
    }
}
