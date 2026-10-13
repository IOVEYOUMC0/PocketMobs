package dev.lone.pocketmobs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

final class CaughtMobSpawnSupport
{
    private CaughtMobSpawnSupport()
    {
    }

    static boolean isSpawnUsable(Entity entity)
    {
        if (entity == null)
        {
            return false;
        }

        try
        {
            // Do not resolve the entity again through Bukkit.getEntity(). On Folia
            // the global lookup can briefly return null immediately after
            // EntitySnapshot.createEntity(), even though that call already created
            // a valid entity on the owning region. Treating that transient lookup
            // miss as a failed restore caused the fallback path to create a second
            // mob for one release.
            if (entity.isDead() || !entity.isValid())
            {
                CaughtMob.debugLog("Discarding spawned entity because it is not present in the world after creation.");
                return false;
            }
            return true;
        }
        catch (Exception e)
        {
            Main.inst.getLogger().warning("Failed to validate spawned entity state: " + e.getMessage());
            return false;
        }
    }

    static Entity spawnFromSnapshot(CaughtMob mob, Location location)
    {
        if (mob.nbtTagCompound == null || mob.nbtTagCompound.isEmpty())
        {
            return null;
        }

        try
        {
            // Native, complete restore from the captured SNBT: createEntity round-trips
            // base fields, subtype data, attributes, effects, variant, custom name and the
            // entity PDC in one call. The target location is already supplied to
            // createEntity, so no synchronous cross-region teleport is needed.
            EntitySnapshot snapshot = Bukkit.getEntityFactory().createEntitySnapshot(mob.nbtTagCompound);
            if (snapshot == null)
            {
                return null;
            }

            Entity entity = snapshot.createEntity(location);
            if (entity == null)
            {
                return null;
            }

            try
            {
                prepareSpawnedEntity(entity, location);
            }
            catch (Exception preparationException)
            {
                // The snapshot entity already exists. A failure while applying
                // velocity/metadata must not make the caller create a second
                // fallback entity.
                CaughtMob.debugLog("Failed to finish preparing snapshot entity: " + preparationException.getMessage());
            }
            CaughtMob.debugLog("Spawned entity from EntitySnapshot: " + entity.getType());
            // EntitySnapshot.createEntity() has already created the entity. Do not
            // treat a transient isValid()/region-state result as a failed restore,
            // because CaughtMob.spawnEntity() would then create a second fallback
            // entity while the snapshot entity is still present.
            return entity;
        }
        catch (Exception e)
        {
            CaughtMob.debugLog("EntitySnapshot spawn failed, falling back for this mob: " + e.getMessage());
        }
        return null;
    }

    static Entity spawnEntityBasic(CaughtMob mob, Location location)
    {
        CaughtMob.debugLog("Spawning entity using basic fallback path: " + mob.type.name());

        Class<? extends Entity> clazz = mob.type.getEntityClass();
        if (clazz == null)
        {
            Main.inst.getLogger().warning("Cannot spawn entity because its Bukkit class is unavailable: " + mob.type.name());
            return null;
        }

        Entity entity = location.getWorld().spawn(location, clazz, newEntity ->
                newEntity.setMetadata("SpawnedWithPB", new FixedMetadataValue(Main.inst, true)));

        entity.setVelocity(new Vector(0, 0.1f, 0));

        if (isSpawnUsable(entity))
        {
            return entity;
        }

        CaughtMob.debugLog("Basic spawn path produced an unusable entity.");
        try
        {
            entity.remove();
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static void prepareSpawnedEntity(Entity entity, Location location)
    {
        entity.setMetadata("SpawnedWithPB", new FixedMetadataValue(Main.inst, true));
        entity.setVelocity(new Vector(0, 0.1f, 0));
    }
}
