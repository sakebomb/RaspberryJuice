package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Handler-level tests for the {@code agent.*} commands, on the MockBukkit harness (real
 * ServerMock + real armor-stand marker + the real loaded plugin). Covers spawn/despawn
 * lifecycle, movement/facing wiring, and the before-spawn "Fail" policy. Actual block
 * placement (agent.setBlock) uses the legacy id->BlockData bridge MockBukkit can't provide,
 * so it is verified live, not here.
 */
class RemoteSessionAgentTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-agent-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@BeforeEach
	void resetHost() {
		plugin.hostPlayer = null;
	}

	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
			super(plugin, socket);
		}
		@Override
		protected void startThreads() { /* no socket I/O in tests */ }
	}

	private RemoteSession session() throws IOException {
		RemoteSession s = new TestSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, 0, 0, 0)); // origin 0,0,0 => relative == absolute
		return s;
	}

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	// ---- lifecycle ----------------------------------------------------------

	@Test
	void agentCommandsBeforeSpawn_returnFail() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.getPos()");
		assertEquals("Fail", lastSent(s));
		s.handleLine("agent.forward()");
		assertEquals("Fail", lastSent(s));
	}

	@Test
	void spawnAtCoords_thenGetPos() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.spawn(3,64,-2)");
		s.handleLine("agent.getPos()");
		assertEquals("3,64,-2", lastSent(s));
	}

	@Test
	void spawnWithoutCoords_usesCurrentPlayer() throws Exception {
		RemoteSession s = session();
		PlayerMock p = server.addPlayer();
		p.setLocation(new Location(world, 5, 70, 8, 0f, 0f));
		plugin.hostPlayer = p;
		s.handleLine("agent.spawn()");
		s.handleLine("agent.getPos()");
		assertEquals("5,70,8", lastSent(s));
	}

	@Test
	void despawn_thenCommandsFailAgain() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.spawn(0,0,0)");
		s.handleLine("agent.despawn()");
		s.handleLine("agent.getPos()");
		assertEquals("Fail", lastSent(s));
	}

	// ---- movement / facing wiring ------------------------------------------

	@Test
	void forward_movesSouth_byDefaultFacing() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.spawn(0,0,0)"); // spawn(x,y,z) faces south (yaw 0)
		s.handleLine("agent.forward()");
		s.handleLine("agent.getPos()");
		assertEquals("0,0,1", lastSent(s));
	}

	@Test
	void forward_withCount() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.spawn(0,0,0)");
		s.handleLine("agent.forward(4)");
		s.handleLine("agent.getPos()");
		assertEquals("0,0,4", lastSent(s));
	}

	@Test
	void turnRight_thenForward_movesWest() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.spawn(0,0,0)");
		s.handleLine("agent.turnRight()");
		s.handleLine("agent.forward()");
		s.handleLine("agent.getPos()");
		assertEquals("-1,0,0", lastSent(s));
	}

	@Test
	void getRotation_reflectsTurns() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.spawn(0,0,0)");
		s.handleLine("agent.getRotation()");
		assertEquals("0", lastSent(s));
		s.handleLine("agent.turnRight()");
		s.handleLine("agent.getRotation()");
		assertEquals("90", lastSent(s));
	}

	@Test
	void up_movesY() throws Exception {
		RemoteSession s = session();
		s.handleLine("agent.spawn(0,0,0)");
		s.handleLine("agent.up(2)");
		s.handleLine("agent.getPos()");
		assertEquals("0,2,0", lastSent(s));
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
