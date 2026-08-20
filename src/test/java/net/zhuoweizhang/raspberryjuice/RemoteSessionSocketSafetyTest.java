package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.Socket;
import java.net.SocketAddress;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Regression for the per-connection socket I/O safety caps: a single client must not be able to OOM
 * the server via a giant unterminated line ({@link RemoteSession#readBoundedLine}), an input flood
 * that outruns the tick drain ({@link RemoteSession#enqueueInput}), or a pile of unread responses
 * ({@link RemoteSession#send}). Each cap stops the session instead of growing memory without bound.
 *
 * <p>Mutation guard: removing the length check in readBoundedLine turns
 * {@code readBoundedLine_returnsNullOnOverlongLine} RED; removing the backlog/queue caps turns the
 * {@code *_stopsSession*} tests RED (the over-cap call would succeed and leave the session running).
 */
class RemoteSessionSocketSafetyTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-sock-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
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

	// ---- readBoundedLine --------------------------------------------------------

	@Test
	void readBoundedLine_returnsLineUpToNewline() throws Exception {
		RemoteSession s = session();
		BufferedReader r = new BufferedReader(new StringReader("world.getBlock(1,2,3)\nnext"));
		assertEquals("world.getBlock(1,2,3)", s.readBoundedLine(r));
	}

	@Test
	void readBoundedLine_toleratesCarriageReturn() throws Exception {
		RemoteSession s = session();
		BufferedReader r = new BufferedReader(new StringReader("chat.post(hi)\r\n"));
		assertEquals("chat.post(hi)", s.readBoundedLine(r));
	}

	@Test
	void readBoundedLine_returnsTrailingPartialThenEof() throws Exception {
		RemoteSession s = session();
		BufferedReader r = new BufferedReader(new StringReader("noNewline"));
		assertEquals("noNewline", s.readBoundedLine(r)); // final partial line
		assertNull(s.readBoundedLine(r));                // then EOF
	}

	@Test
	void readBoundedLine_returnsNullOnOverlongLine() throws Exception {
		RemoteSession s = session();
		// one char past the cap, with no newline: the old unbounded readLine would buffer it all
		String giant = "x".repeat(RemoteSession.MAX_LINE_LENGTH + 1);
		BufferedReader r = new BufferedReader(new StringReader(giant));
		assertNull(s.readBoundedLine(r), "an over-length unterminated line must be rejected, not buffered");
	}

	// ---- inbound backlog cap ----------------------------------------------------

	@Test
	void enqueueInput_stopsSessionWhenBacklogExceedsCap() throws Exception {
		RemoteSession s = session();
		for (int i = 0; i < RemoteSession.MAX_IN_QUEUE; i++) {
			assertTrue(s.enqueueInput("world.getBlock(0,0,0)"), "within the cap, input is accepted");
		}
		assertTrue(s.running, "still running at exactly the cap");
		assertFalse(s.enqueueInput("world.getBlock(0,0,0)"), "the over-cap command is rejected");
		assertFalse(s.running, "flooding past the backlog cap stops the session");
	}

	// ---- outbound response queue cap -------------------------------------------

	@Test
	void send_stopsSessionWhenOutputQueueFull() throws Exception {
		RemoteSession s = session();
		for (int i = 0; i < RemoteSession.MAX_OUT_QUEUE; i++) {
			s.send("1");
		}
		assertFalse(s.pendingRemoval, "queue is exactly full but not yet overflowed");
		s.send("1"); // this one can't be queued -> the client isn't reading -> stop
		assertTrue(s.pendingRemoval, "overflowing the response queue marks the session for removal");
		assertFalse(s.running, "and stops it");
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
