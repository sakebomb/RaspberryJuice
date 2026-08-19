package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Tests the optional auth-token handshake (#security). The token is set on the loaded plugin via
 * reflection and restored after each test so the session constructor picks it up.
 */
class RemoteSessionAuthTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-auth-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	private static void setToken(String token) throws Exception {
		Field f = RaspberryJuicePlugin.class.getDeclaredField("authToken");
		f.setAccessible(true);
		f.set(plugin, token);
	}

	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException { super(plugin, socket); }
		@Override protected void startThreads() { }
	}

	private RemoteSession session() throws IOException {
		RemoteSession s = new TestSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, 0, 0, 0));
		return s;
	}

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	@Test
	void noToken_meansNoAuthRequired() throws Exception {
		setToken("");
		try {
			RemoteSession s = session();
			s.handleLine("world.getTime()"); // works immediately, no auth
			assertNotEquals("Fail", lastSent(s));
		} finally {
			setToken("");
		}
	}

	@Test
	void withToken_commandsRejectedUntilAuthenticated() throws Exception {
		setToken("s3cret");
		try {
			RemoteSession s = session(); // constructor reads token -> unauthenticated

			// a normal command is refused before auth
			s.handleLine("world.getTime()");
			assertEquals("Fail", lastSent(s));

			// wrong token -> Fail, still unauthenticated
			s.handleLine("auth(nope)");
			assertEquals("Fail", lastSent(s));
			s.handleLine("world.getTime()");
			assertEquals("Fail", lastSent(s));

			// correct token -> "1", then commands work
			s.handleLine("auth(s3cret)");
			assertEquals("1", lastSent(s));
			s.handleLine("world.getTime()");
			assertNotEquals("Fail", lastSent(s));
		} finally {
			setToken("");
		}
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
