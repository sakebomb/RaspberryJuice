package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * In-game plot wall (#18). Break, place, and buckets cancel only when the block is outside
 * the actor's plot and inside someone else's. Gaps and overlaps stay shared.
 */
class ClassroomPlotListenerTest {

	private static final int SPAWN_X = 100;
	private static final int SPAWN_Y = 64;
	private static final int SPAWN_Z = 200;

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;
	private static PlayerMock alice;
	private static PlayerMock bob;
	private static PlayerMock carol;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-listener");
		world.setSpawnLocation(SPAWN_X, SPAWN_Y, SPAWN_Z);
		plugin.refreshSandboxOrigin();
		alice = server.addPlayer("Alice");
		bob = server.addPlayer("Bob");
		carol = server.addPlayer("Carol");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@AfterEach
	void reset() {
		plugin.installPlots(PlotBounds.ParsedPlots.off());
		plugin.sessions.clear();
	}

	private static final class CountingSession extends RemoteSession {
		int breaks;
		CountingSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
			super(plugin, socket);
		}
		@Override
		protected void startThreads() { }
		@Override
		public void queueBlockBreak(Player player, Block block) {
			breaks++;
		}
	}

	private CountingSession session() throws IOException {
		CountingSession s = new CountingSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, SPAWN_X, SPAWN_Y, SPAWN_Z));
		plugin.sessions.add(s);
		return s;
	}

	private void plots(String yaml) throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString(yaml);
		plugin.installPlots(PlotBounds.readPlots(cfg));
	}

	private void spacedPlots() throws Exception {
		plots("""
			plots:
			  Alice: [0, 0, 0, 31, 15, 31]
			  Bob: [48, 0, 0, 79, 15, 31]
			""");
	}

	/** World block at a location-space offset from the stored spawn-block origin. */
	private Block at(int x, int y, int z) {
		return world.getBlockAt(SPAWN_X + x, SPAWN_Y + y, SPAWN_Z + z);
	}

	private void bind(RemoteSession s, String name) {
		s.handleLine("setPlayer(" + name + ")");
		List<String> sent = s.drainSentForTest();
		assertEquals("1", sent.get(sent.size() - 1));
	}

	@Test
	void originBlock_matchesSpawn_andSpawnCellIsTheActorsPlot() throws Exception {
		assertEquals(SPAWN_X, plugin.sandboxOriginBlockX());
		assertEquals(SPAWN_Y, plugin.sandboxOriginBlockY());
		assertEquals(SPAWN_Z, plugin.sandboxOriginBlockZ());
		spacedPlots();
		assertFalse(plugin.denied(alice, at(0, 0, 0)), "spawn block is location-space (0,0,0)");
		assertTrue(plugin.denied(alice, at(50, 0, 0)), "Bob's pad is not a gap once origin is subtracted");
	}

	@Test
	void ownPlotAllowed_neighborDenied_gapAllowed() throws Exception {
		spacedPlots();
		assertFalse(plugin.denied(alice, at(1, 0, 1)));
		assertTrue(plugin.denied(alice, at(50, 0, 0)));
		assertFalse(plugin.denied(alice, at(40, 0, 0)), "the shell between plots stays buildable");
	}

	@Test
	void overlap_isSharedByOwners_andDeniedForAThird() throws Exception {
		plots("""
			plots:
			  Alice: [0, 0, 0, 31, 15, 31]
			  Bob: [31, 0, 0, 62, 15, 31]
			  Carol: [200, 0, 0, 210, 15, 31]
			""");
		Block shared = at(31, 0, 0);
		assertFalse(plugin.denied(alice, shared));
		assertFalse(plugin.denied(bob, shared));
		assertTrue(plugin.denied(carol, shared));
	}

	@Test
	void otherWorld_andSandboxOff_andTeacher_areAllowed() throws Exception {
		spacedPlots();
		World elsewhere = server.addSimpleWorld("rj-listener-other");
		assertFalse(plugin.denied(alice, elsewhere.getBlockAt(SPAWN_X + 50, SPAWN_Y, SPAWN_Z)));

		plugin.installPlots(PlotBounds.ParsedPlots.off());
		assertFalse(plugin.denied(alice, at(50, 0, 0)));

		spacedPlots();
		PermissionAttachment teacher = alice.addAttachment(plugin, "raspberryjuice.classroom.teacher", true);
		try {
			assertFalse(plugin.denied(alice, at(50, 0, 0)));
		} finally {
			alice.removeAttachment(teacher);
		}
	}

	@Test
	void bucketFacingANeighbor_isDenied_bucketFacingOwnPlot_isNot() throws Exception {
		plots("""
			plots:
			  Alice: [0, 0, 0, 31, 15, 31]
			  Bob: [32, 0, 0, 63, 15, 31]
			""");
		Block edge = at(31, 0, 0);
		assertTrue(plugin.bucketDenied(alice, edge, BlockFace.EAST));
		assertFalse(plugin.bucketDenied(alice, edge, BlockFace.WEST));

		PlayerBucketEmptyEvent emptied = new PlayerBucketEmptyEvent(
			alice, edge, BlockFace.EAST, Material.WATER_BUCKET, new ItemStack(Material.WATER_BUCKET));
		Bukkit.getPluginManager().callEvent(emptied);
		assertTrue(emptied.isCancelled());
	}

	@Test
	void placeAgainstANeighborFace_insideOwnPlotOrAGap_isNotCancelled() throws Exception {
		spacedPlots();
		ItemStack stone = new ItemStack(Material.STONE);
		Block own = at(1, 0, 1);
		Block gap = at(40, 0, 0);
		Block neighbor = at(50, 0, 0);
		BlockPlaceEvent intoOwn = new BlockPlaceEvent(own, own.getState(), neighbor, stone, alice, true);
		BlockPlaceEvent intoGap = new BlockPlaceEvent(gap, gap.getState(), neighbor, stone, alice, true);
		Bukkit.getPluginManager().callEvent(intoOwn);
		Bukkit.getPluginManager().callEvent(intoGap);
		assertFalse(intoOwn.isCancelled());
		assertFalse(intoGap.isCancelled());
	}

	@Test
	void multiPlace_cancelsWhenAReplacedBlockIsInTheNeighborPlot() throws Exception {
		spacedPlots();
		Block own = at(1, 0, 1);
		Block neighbor = at(50, 0, 0);
		BlockMultiPlaceEvent event = new BlockMultiPlaceEvent(
			List.of(own.getState(), neighbor.getState()), own, new ItemStack(Material.STONE), alice, true);
		assertEquals(own.getX(), event.getBlock().getX(), "the placed block is Alice's; the neighbor is only a replaced state");
		assertFalse(event.getBlockAgainst().equals(neighbor));
		Bukkit.getPluginManager().callEvent(event);
		assertTrue(event.isCancelled());
	}

	@Test
	void neighborBreak_isCancelledBeforeTheSessionQueue() throws Exception {
		spacedPlots();
		CountingSession s = session();
		bind(s, "Alice");
		BlockBreakEvent event = new BlockBreakEvent(at(50, 0, 0), alice);
		Bukkit.getPluginManager().callEvent(event);
		assertTrue(event.isCancelled());
		assertEquals(0, s.breaks, "LOWEST protection must cancel before the break fan-out queues");

		BlockBreakEvent own = new BlockBreakEvent(at(1, 0, 1), alice);
		Bukkit.getPluginManager().callEvent(own);
		assertFalse(own.isCancelled());
		assertEquals(1, s.breaks);
	}

	private static final class FakeSocket extends Socket {
		private final InputStream in = new ByteArrayInputStream(new byte[0]);
		private final OutputStream out = new ByteArrayOutputStream();
		@Override public InputStream getInputStream() { return in; }
		@Override public OutputStream getOutputStream() { return out; }
		@Override public void setTcpNoDelay(boolean on) { }
		@Override public void setKeepAlive(boolean on) { }
		@Override public void setTrafficClass(int tc) { }
		@Override public SocketAddress getRemoteSocketAddress() { return null; }
	}
}
