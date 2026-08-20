package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Connection admission control (#56): the concurrent-session cap and the per-IP connection-rate
 * limit, exercised through the loaded plugin with its default config (max-sessions 100,
 * max-connections-per-minute 60).
 *
 * <p>Mutation guard: turning {@code withinSessionCap}'s {@code <} into {@code <=} (or dropping the
 * cap) flips the boundary test; removing the rate-limit check from {@code admit} lets the 61st
 * connection through.
 */
class ConnectionAdmissionTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@Test
	void withinSessionCap_boundaryAtConfiguredMax() {
		// default max-sessions is 100
		assertTrue(plugin.withinSessionCap(99), "99 current -> a 100th session fits");
		assertFalse(plugin.withinSessionCap(100), "at the cap, no new session");
		assertFalse(plugin.withinSessionCap(101), "over the cap, no new session");
	}

	@Test
	void admit_refusesConnectionsFromAnIpOverTheRate() {
		// default max-connections-per-minute is 60; all calls here land in the same minute window
		for (int i = 0; i < 60; i++) {
			assertTrue(plugin.admit(fakeSocket("203.0.113.7")), "connection " + (i + 1) + " within the rate");
		}
		assertFalse(plugin.admit(fakeSocket("203.0.113.7")), "the 61st connection from that IP is refused");
		assertTrue(plugin.admit(fakeSocket("203.0.113.8")), "a different IP still has its own budget");
	}

	private static Socket fakeSocket(String ip) {
		return new FakeSocket(new InetSocketAddress(ip, 54321));
	}

	private static final class FakeSocket extends Socket {
		private final SocketAddress remote;
		private final InputStream in = new ByteArrayInputStream(new byte[0]);
		private final OutputStream out = new ByteArrayOutputStream();
		FakeSocket(SocketAddress remote) { this.remote = remote; }
		@Override public InputStream getInputStream() { return in; }
		@Override public OutputStream getOutputStream() { return out; }
		@Override public SocketAddress getRemoteSocketAddress() { return remote; }
	}
}
