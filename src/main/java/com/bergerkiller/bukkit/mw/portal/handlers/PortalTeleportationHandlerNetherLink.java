package com.bergerkiller.bukkit.mw.portal.handlers;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.mw.Localization;
import com.bergerkiller.bukkit.mw.portal.NetherPortalOrientation;
import com.bergerkiller.bukkit.mw.portal.NetherPortalSearcher;
import com.bergerkiller.bukkit.mw.portal.PortalTeleportationHandler;
import com.bergerkiller.bukkit.mw.portal.NetherPortalSearcher.SearchStatus;

/**
 * Handler for teleporting between worlds through nether portals,
 * searching for existing portals on the other end, or creating
 * new ones if needed.
 */
public class PortalTeleportationHandlerNetherLink extends PortalTeleportationHandler {
    @Override
    public void handleWorld(World world) {
        // Figure out the desired Block location on the other world
        // This uses *8 rules for nether world vs other worlds
        double factor = (portalBlock.getWorld().getEnvironment() == Environment.NETHER ? 8.0 : 1.0) /
                        (world.getEnvironment() == Environment.NETHER ? 8.0 : 1.0);

        Block searchStartBlock = world.getBlockAt(MathUtil.floor(portalBlock.getX() * factor),
                                                  portalBlock.getY(),
                                                  MathUtil.floor(portalBlock.getZ() * factor));

        // Calculate the create options for the current entity teleportation
        NetherPortalSearcher.CreateOptions createOptions = null;
        if (checkCanCreatePortals()) {
            createOptions = NetherPortalSearcher.CreateOptions.create()
                    .initiator(entity)
                    .orientation(portalType.getOrientation(portalBlock, entity));
        }

        // Start searching
        NetherPortalSearcher.SearchResult result = plugin.getNetherPortalSearcher().search(searchStartBlock, createOptions);

        // For entities that can be kept in stasis while processing happens in the background,
        // put the entity in there. Or release, when no longer busy.
        // If this parity breaks, a timeout will free the entity eventually.
        if (plugin.getEntityStasisHandler().canBeKeptInStasis(entity)) {
            if (result.getStatus().isBusy()) {
                plugin.getEntityStasisHandler().putInStasis(entity);
            } else {
                plugin.getEntityStasisHandler().freeFromStasis(entity);
            }
        }

        // Stop when busy / debounce
        if (result.getStatus().isBusy() || !plugin.getPortalTeleportationCooldown().tryEnterPortal(entity)) {
            return;
        }

        if (result.getStatus() == SearchStatus.NOT_FOUND) {
            // Failed to find an existing portal, we can't create a new one
            if (entity instanceof Player) {
                Localization.PORTAL_LINK_UNAVAILABLE.message((Player) entity);
            }
        } else if (result.getStatus() == SearchStatus.NOT_CREATED) {
            // Failed to create a new portal on the other end
            if (entity instanceof Player) {
                Localization.PORTAL_LINK_FAILED.message((Player) entity);
            }
        } else if (result.getStatus() == SearchStatus.FOUND) {
            // Retrieve portal transformations for the current (old) and destination (new) portal
            Matrix4x4 oldTransform = portalType.getTransform(portalBlock, entity);
            Matrix4x4 newTransform = NetherPortalOrientation.compute(result.getResult().getBlock());

            // Retrieve location by combining the entity feet position with the (possible) player eye location
            Matrix4x4 transform;
            {
                Location loc = entity.getLocation();
                if (entity instanceof LivingEntity) {
                    Location eyeLoc = ((LivingEntity) entity).getEyeLocation();
                    loc.setYaw(eyeLoc.getYaw());
                    loc.setPitch(eyeLoc.getPitch());
                }
                transform = Matrix4x4.fromLocation(loc);
            }

            // Change transformation to be relative to the portal entered
            transform = Matrix4x4.diff(oldTransform, transform);

            // Remove distance from portal as a component from the transform
            // This positions the player inside the portal on arrival, rather than slightly in front
            {
                Matrix4x4 correctiveTransform = new Matrix4x4();
                correctiveTransform.translate(0.0, 0.0, -transform.toVector().getZ());
                transform.storeMultiply(correctiveTransform, transform);
            }

            // Apply new portal transformation to old portal relative transformation
            transform.storeMultiply(newTransform, transform);

            // Compute the final Location information from the transform
            final Location locToTeleportTo = transform.toLocation(result.getResult().getWorld());

            // Retrieve the velocity of the entity upon entering the portal
            // Transform this velocity the same way we transformed the position
            final Vector velocityAfterTeleport = entity.getVelocity();
            Matrix4x4.diffRotation(oldTransform, newTransform).transformPoint(velocityAfterTeleport);

            // Perform the teleportation woo
            scheduleTeleportation(locToTeleportTo, velocityAfterTeleport);
        }
    }
}
