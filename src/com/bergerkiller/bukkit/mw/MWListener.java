package com.bergerkiller.bukkit.mw;

import java.util.HashSet;
import java.util.Iterator;
import java.util.WeakHashMap;

import net.minecraft.server.EntityPlayer;
import net.minecraft.server.PlayerFileData;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;

public class MWListener implements Listener {
	// World to disable keepspawnloaded for
	private static HashSet<String> initIgnoreWorlds = new HashSet<String>();
	// A mapping of player positions to prevent spammed portal teleportation
	private static WeakHashMap<Player, Location> walkDistanceCheckMap = new WeakHashMap<Player, Location>();
	// Portal times for a minimal delay
    private static WeakHashMap<Entity, Long> portaltimes = new WeakHashMap<Entity, Long>();
    // Whether weather changes handling is ignored
	public static boolean ignoreWeatherChanges = false;

	public static void ignoreWorld(String worldname) {
		initIgnoreWorlds.add(worldname);
	}

	/**
	 * Handles the teleport delay and distance checks
	 * 
	 * @param e Entity to pre-teleport
	 * @return True if teleporting is possible, False if not
	 */
	public static boolean preTeleport(Entity e) {
    	if (walkDistanceCheckMap.containsKey(e)) {
    		return false;
    	}
        long currtime = System.currentTimeMillis();
    	long lastteleport;
    	if (portaltimes.containsKey(e)) {
    		lastteleport = portaltimes.get(e);
    	} else {
    		lastteleport = currtime - MyWorlds.teleportInterval;
    		portaltimes.put(e, lastteleport);
    	}
        if (currtime - lastteleport >= MyWorlds.teleportInterval) {
        	portaltimes.put(e, currtime);
        	return true;
        } else {
        	return false;
        }
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldLoad(WorldLoadEvent event) {
		WorldConfig.get(event.getWorld()).timeControl.updateWorld(event.getWorld());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldUnload(WorldUnloadEvent event) {
		if (!event.isCancelled()) {
			WorldConfig config = WorldConfig.get(event.getWorld());
			config.timeControl.updateWorld(null);
			WorldManager.clearWorldReference(event.getWorld());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldInit(WorldInitEvent event) {
		if (initIgnoreWorlds.remove(event.getWorld().getName())) {
			event.getWorld().setKeepSpawnInMemory(false);
		} else {
			WorldConfig.get(event.getWorld()).update(event.getWorld());
		}
	}

	public static void setWeather(World w, boolean storm) {
		ignoreWeatherChanges = true;
		w.setStorm(storm);
		ignoreWeatherChanges = false;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onWeatherChange(WeatherChangeEvent event) {
		if (!ignoreWeatherChanges && WorldConfig.get(event.getWorld()).holdWeather) {
			event.setCancelled(true);
		} else {
			WorldConfig.get(event.getWorld()).updateSpoutWeather(event.getWorld());
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerJoin(PlayerJoinEvent event) {
		WorldConfig.get(event.getPlayer()).update(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		if (event.isBedSpawn() && !WorldConfig.get(event.getPlayer()).forcedRespawn) {
			return; // Ignore bed spawns that are not overrided
		}
		Location loc = WorldManager.getRespawnLocation(event.getPlayer().getWorld());
		if (loc != null) {
			event.setRespawnLocation(loc);
		}
		WorldConfig.get(event.getRespawnLocation()).update(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerQuit(PlayerQuitEvent event) {
		WorldConfig.updateReload(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerMove(PlayerMoveEvent event) {
		if (!event.isCancelled()) {
			// Handle player movement for portals
			Location loc = walkDistanceCheckMap.get(event.getPlayer());
			if (loc != null) {
				if (loc.getWorld() != event.getTo().getWorld() || loc.distanceSquared(event.getTo()) > 1.0) {
					walkDistanceCheckMap.remove(event.getPlayer());
				}
			}
			// Water teleport handling
			Block b = event.getTo().getBlock();
			if (MyWorlds.useWaterTeleport && b.getTypeId() == 9) {
				if (b.getRelative(BlockFace.UP).getTypeId() == 9 || b.getRelative(BlockFace.DOWN).getTypeId() == 9) {
					boolean allow = false;
					if (b.getRelative(BlockFace.NORTH).getType() == Material.AIR || b.getRelative(BlockFace.SOUTH).getType() == Material.AIR) {
						if (Util.isSolid(b, BlockFace.WEST) && Util.isSolid(b, BlockFace.EAST)) {
							allow = true;
						}
					} else if (b.getRelative(BlockFace.EAST).getType() == Material.AIR || b.getRelative(BlockFace.WEST).getType() == Material.AIR) {
						if (Util.isSolid(b, BlockFace.NORTH) && Util.isSolid(b, BlockFace.SOUTH)) {
							allow = true;
						}
					}
					if (allow && preTeleport(event.getPlayer())) {
						Portal.handlePortalEnter(event.getPlayer());
					}
				}
			}
			if (event.getFrom().getWorld() != event.getTo().getWorld()) {
				WorldConfig.updateReload(event.getFrom());
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerTeleport(PlayerTeleportEvent event) {
		if (!event.isCancelled()) {
			walkDistanceCheckMap.put(event.getPlayer(), event.getTo());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onEntityPortalEnter(EntityPortalEnterEvent event) {
		if (MyWorlds.onlyPlayerTeleportation) {
			if (!(event.getEntity() instanceof Player)) {
				return;
			}
		}
		if (MyWorlds.onlyObsidianPortals) {
			Block b = event.getLocation().getBlock();
			if (b.getType() == Material.PORTAL) {
				if (!Util.isObsidianPortal(b)) {
					return;
				}
			}
		}
		if (preTeleport(event.getEntity())) {
			Portal.handlePortalEnter(event.getEntity());
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerChat(AsyncPlayerChatEvent event) {
		if (!Permission.canChat(event.getPlayer())) {
			event.setCancelled(true);
			Localization.WORLD_NOCHATACCESS.message(event.getPlayer());
			return;
		}
		Iterator<Player> iterator = event.getRecipients().iterator();
		while (iterator.hasNext()) {
			if (!Permission.canChat(event.getPlayer(), iterator.next())) {
				iterator.remove();
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
		WorldConfig.updateReload(event.getFrom());
		WorldConfig.get(event.getPlayer()).update(event.getPlayer());
		if (MyWorlds.useWorldInventories && !Permission.GENERAL_KEEPINV.has(event.getPlayer())) {
			EntityPlayer ep = EntityUtil.getNative(event.getPlayer());
			PlayerFileData data = CommonUtil.getServerConfig().playerFileData;
			net.minecraft.server.World newWorld = ep.world;
			ep.world = WorldUtil.getNative(event.getFrom());
			data.save(ep);
			ep.world = newWorld;
			PlayerData.refreshState(ep);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onCreatureSpawn(CreatureSpawnEvent event) {
		if (!event.isCancelled()) {
			if (event.getSpawnReason() != SpawnReason.CUSTOM) {
				if (WorldConfig.get(event.getEntity()).spawnControl.isDenied(event.getEntity())) {
					event.setCancelled(true);
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockForm(BlockFormEvent event) {
		if (event.isCancelled()) {
			return;
		}
		Material type = event.getNewState().getType();
		if (type == Material.SNOW) {
			if (!WorldConfig.get(event.getBlock()).formSnow) {
				event.setCancelled(true);
			}
		} else if (type == Material.ICE) {
			if (!WorldConfig.get(event.getBlock()).formIce) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockPhysics(BlockPhysicsEvent event) {
		if (!event.isCancelled()) {
			if (event.getBlock().getType() == Material.PORTAL) {
				if (!(event.getBlock().getRelative(BlockFace.DOWN).getType() == Material.AIR)) {
					event.setCancelled(true);
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onBlockBreak(BlockBreakEvent event) {
		if (!event.isCancelled()) {
			Portal portal = Portal.get(event.getBlock(), false);
			if (portal != null && portal.remove()) {
				event.getPlayer().sendMessage(ChatColor.RED + "You removed portal " + ChatColor.WHITE + portal.getName() + ChatColor.RED + "!");
				Util.notifyConsole(event.getPlayer(), "Removed portal '" + portal.getName() + "'!");
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onSignChange(SignChangeEvent event) {
		if (!event.isCancelled()) {
			Portal portal = Portal.get(event.getBlock(), event.getLines());
			if (portal != null) {
				if (Permission.has(event.getPlayer(), "portal.create")) {
					if (Portal.exists(event.getPlayer().getWorld().getName(), portal.getName())) {
						if (!MyWorlds.allowPortalNameOverride || !Permission.has(event.getPlayer(), "portal.override")) {
							event.getPlayer().sendMessage(ChatColor.RED + "This portal name is already used!");
							event.setCancelled(true);
							return;
						}
					}
					portal.add();
					Util.notifyConsole(event.getPlayer(), "Created a new portal: '" + portal.getName() + "'!");
					if (portal.hasDestination()) {
						event.getPlayer().sendMessage(ChatColor.GREEN + "You created a new portal to " + ChatColor.WHITE + portal.getDestinationName() + ChatColor.GREEN + "!");
					} else {
						event.getPlayer().sendMessage(ChatColor.GREEN + "You created a new destination portal!");
					}
				} else {
					event.setCancelled(true);
				}
			}
		}
	}
}
