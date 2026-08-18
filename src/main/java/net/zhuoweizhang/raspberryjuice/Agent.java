package net.zhuoweizhang.raspberryjuice;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;

import net.kyori.adventure.text.Component;

/**
 * A per-session, code-driven agent: the pure turtle {@link AgentState} mirrored onto a visible
 * armor-stand marker so students can watch it step block-to-block. Movement is teleport-step
 * (deterministic, grid-aligned) rather than pathfinding.
 *
 * <p>The agent keeps its current chunk loaded (a plugin chunk ticket) so move/place stay
 * reliable even when it roams away from any player - otherwise the chunk can unload and entity
 * operations start failing.
 */
public final class Agent {

	private final RaspberryJuicePlugin plugin;
	private final AgentState state;
	private final ArmorStand marker;

	private boolean hasTicket = false;
	private int ticketX;
	private int ticketZ;

	private Agent(RaspberryJuicePlugin plugin, AgentState state, ArmorStand marker) {
		this.plugin = plugin;
		this.state = state;
		this.marker = marker;
	}

	/** Spawn a gravity-less armor-stand marker at the given block, facing {@code yaw}. */
	static Agent spawn(RaspberryJuicePlugin plugin, World world, int x, int y, int z, float yaw) {
		AgentState state = new AgentState(x, y, z, Math.round(yaw));
		// spawnEntity loads the target chunk; the ticket below keeps it loaded afterwards.
		ArmorStand marker = (ArmorStand) world.spawnEntity(centerOf(world, state), EntityType.ARMOR_STAND);
		marker.setGravity(false);
		marker.setInvulnerable(true);
		marker.customName(Component.text("Agent"));
		marker.setCustomNameVisible(true);
		marker.setBasePlate(false);
		marker.setSmall(true);
		Agent agent = new Agent(plugin, state, marker);
		agent.keepChunkLoaded(world);
		return agent;
	}

	boolean isValid() {
		// !isDead() (not isValid()) so the agent survives being in a chunk that isn't currently
		// loaded - Entity.isValid() requires a loaded chunk, which fails e.g. with no player nearby.
		return marker != null && !marker.isDead();
	}

	void remove() {
		releaseChunk();
		if (marker != null) marker.remove();
	}

	int x() { return state.getX(); }
	int y() { return state.getY(); }
	int z() { return state.getZ(); }
	int facing() { return state.getFacing(); }

	void forward(int n) { state.forward(n); sync(); }
	void back(int n) { state.back(n); sync(); }
	void up(int n) { state.up(n); sync(); }
	void down(int n) { state.down(n); sync(); }
	void turnLeft() { state.turnLeft(); sync(); }
	void turnRight() { state.turnRight(); sync(); }

	/** Teleport the marker to match the logical state (block-centered, facing yaw). */
	private void sync() {
		World world = marker.getWorld();
		keepChunkLoaded(world);
		marker.teleport(centerOf(world, state));
	}

	/** Keep exactly the agent's current chunk loaded via a plugin ticket (best-effort). */
	private void keepChunkLoaded(World world) {
		int cx = state.getX() >> 4;
		int cz = state.getZ() >> 4;
		if (hasTicket && cx == ticketX && cz == ticketZ) return;
		try {
			if (hasTicket) world.removePluginChunkTicket(ticketX, ticketZ, plugin);
			world.addPluginChunkTicket(cx, cz, plugin);
			ticketX = cx;
			ticketZ = cz;
			hasTicket = true;
		} catch (Throwable ignored) {
			// chunk tickets are an optimization; unsupported harnesses can ignore them
		}
	}

	private void releaseChunk() {
		if (!hasTicket || marker == null) return;
		try {
			marker.getWorld().removePluginChunkTicket(ticketX, ticketZ, plugin);
		} catch (Throwable ignored) {
		}
		hasTicket = false;
	}

	private static Location centerOf(World world, AgentState s) {
		return new Location(world, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, s.getFacing(), 0f);
	}
}
