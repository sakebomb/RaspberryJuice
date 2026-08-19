package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * Guards the command-dispatch registry (#46). The seven-way handle*() if/else ladder was replaced
 * by a {@code Map<String, CommandHandler>}; these tests pin the registry's contents and the
 * unsupported-command fallthrough so a future edit can't silently drop, rename, or misroute a
 * command (which per-command behaviour tests wouldn't all catch), or fold {@code auth} - which must
 * stay special-cased ahead of the authentication gate - into the registry.
 */
class RemoteSessionDispatchTest {

	// The full protocol surface the registry must dispatch. Kept explicit (not derived) so a
	// dropped or typo'd registration fails loudly here and any addition is a deliberate edit.
	private static final Set<String> EXPECTED_COMMANDS = Set.of(
		// world blocks + bulk-entity
		"world.getBlock", "world.getBlocks", "world.getBlockWithData", "world.setBlock",
		"world.setBlocks", "world.getPlayerIds", "world.getPlayerId", "entity.getName",
		"world.getEntities", "world.removeEntity", "world.removeEntities", "world.getHeight",
		"world.setSign", "world.spawnEntity", "world.getEntityTypes",
		// world & player control
		"world.setTime", "world.getTime", "world.setWeather", "world.clone",
		"player.setGameMode", "player.give",
		// chat + session identity
		"chat.post", "setPlayer",
		// event polls
		"events.clear", "events.block.hits", "events.chat.posts", "events.projectile.hits",
		"entity.events.clear", "entity.events.block.hits", "entity.events.chat.posts",
		"entity.events.projectile.hits", "player.events.block.hits", "player.events.chat.posts",
		"player.events.projectile.hits", "player.events.clear",
		"events.player.moves", "events.block.places", "events.block.breaks", "events.player.deaths",
		// player pose + queries
		"player.getTile", "player.setTile", "player.getAbsPos", "player.setAbsPos",
		"player.getPos", "player.setPos", "player.setDirection", "player.getDirection",
		"player.setRotation", "player.getRotation", "player.setPitch", "player.getPitch",
		"player.getEntities", "player.removeEntities",
		// entity pose + queries
		"entity.getTile", "entity.setTile", "entity.getPos", "entity.setPos",
		"entity.setDirection", "entity.getDirection", "entity.setRotation", "entity.getRotation",
		"entity.setPitch", "entity.getPitch", "entity.getEntities", "entity.removeEntities",
		// entity/mob control
		"entity.moveTo", "entity.lookAt", "entity.getHealth", "entity.setHealth",
		"entity.setName", "entity.setAI",
		// agent / turtle
		"agent.spawn", "agent.despawn", "agent.getPos", "agent.getRotation", "agent.forward",
		"agent.back", "agent.up", "agent.down", "agent.turnLeft", "agent.turnRight",
		"agent.setBlock"
	);

	private RemoteSession newSession() throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		RemoteSession s = new RemoteSession(plugin, socket);
		s.setOrigin(new Location(mock(World.class), 0, 0, 0));
		return s;
	}

	@Test
	void registry_containsExactlyTheExpectedCommandSurface() throws Exception {
		RemoteSession s = newSession();
		Set<String> actual = s.registeredCommandsForTest();
		// TreeSet-wrapped diffs give a readable failure message naming exactly what drifted.
		assertEquals(new TreeSet<>(EXPECTED_COMMANDS), new TreeSet<>(actual),
			"the dispatch registry must map exactly the known command surface - a diff means a "
			+ "command was dropped, renamed, or added without updating this guard");
	}

	@Test
	void auth_isNotInRegistry_soItStaysSpecialCasedAheadOfTheAuthGate() throws Exception {
		RemoteSession s = newSession();
		assertFalse(s.registeredCommandsForTest().contains("auth"),
			"auth must be handled before the authenticated gate, not via the registry");
	}

	@Test
	void unknownCommand_repliesFail() throws Exception {
		RemoteSession s = newSession();
		s.handleLine("totally.bogus.command()");
		List<String> sent = s.drainSentForTest();
		assertEquals(List.of("Fail"), sent, "an unsupported command must reply exactly \"Fail\"");
	}

	@Test
	void knownCommand_routesToItsHandler_notTheUnsupportedFallthrough() throws Exception {
		// events.clear() touches only in-memory queues (no world/player needed) and sends nothing;
		// reaching it (rather than the "not supported" -> Fail path) proves the registry routed it.
		RemoteSession s = newSession();
		s.handleLine("events.clear()");
		assertTrue(s.drainSentForTest().isEmpty(),
			"a registered fire-and-forget command must not fall through to the Fail path");
	}
}
