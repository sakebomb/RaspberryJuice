package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Per-player authorization for setPlayer (#47). auth-token gates WHO may connect; player-tokens
 * gates WHICH player a connection may bind to (and thus whose reactive event feed it may observe).
 *
 * <p>Contract: when the player-tokens map is EMPTY (default), setPlayer(name) binds by name with no
 * token - single-player / trusted deploys are unchanged. When the map is NON-EMPTY the bind is
 * fail-closed: it succeeds only if the named player is listed AND the supplied token matches
 * (setPlayer(name,token)); an unlisted player or a wrong/missing token gets "Fail" and leaves the
 * session bound to nobody, so it cannot observe that player's feed.
 *
 * <p>Per-test MockBukkit lifecycle so each test controls exactly who is online and what the
 * player-tokens map holds (set on the loaded plugin via reflection, cleared on teardown).
 */
class RemoteSessionPlayerAuthTest {

	private ServerMock server;
	private RaspberryJuicePlugin plugin;
	private World world;

	@BeforeEach
	void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-playerauth-test");
	}

	@AfterEach
	void shutdown() {
		// each test gets a fresh MockBukkit.load(plugin) in boot(), so the playerTokens field starts
		// empty every time - no need to reset it here (and resetting before unmock would be fragile).
		plugin.sessions.clear();
		MockBukkit.unmock();
	}

	@SuppressWarnings("unchecked")
	private void setPlayerTokens(Map<String, String> tokens) throws Exception {
		Field f = RaspberryJuicePlugin.class.getDeclaredField("playerTokens");
		f.setAccessible(true);
		((Map<String, String>) f.get(plugin)).clear();
		((Map<String, String>) f.get(plugin)).putAll(tokens);
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

	private String send(RemoteSession s, String line) {
		s.handleLine(line);
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response to " + line);
		return sent.get(sent.size() - 1);
	}

	// ---- default (no player-tokens): unchanged behaviour, AC #3 -------------

	@Test
	void noPlayerTokens_setPlayerBindsWithoutToken() throws Exception {
		RemoteSession s = session();
		server.addPlayer("Alice");
		server.addPlayer("Bob");
		assertEquals("1", send(s, "setPlayer(Alice)"),
			"with no player-tokens configured, setPlayer must bind by name as before");
	}

	// ---- player-tokens configured: fail-closed, AC #1 & #2 ------------------

	@Test
	void correctToken_binds() throws Exception {
		setPlayerTokens(Map.of("Alice", "s3cret"));
		RemoteSession s = session();
		PlayerMock alice = server.addPlayer("Alice");
		assertEquals("1", send(s, "setPlayer(Alice,s3cret)"), "matching token must bind");
		assertTrue(s.isForCurrentPlayer(alice), "an authorized bind must observe its own player");
	}

	@Test
	void wrongToken_failsAndBindsNobody() throws Exception {
		setPlayerTokens(Map.of("Alice", "s3cret"));
		RemoteSession s = session();
		PlayerMock alice = server.addPlayer("Alice");
		server.addPlayer("Bob"); // >1 online so an unbound session fails closed in isForCurrentPlayer
		assertEquals("Fail", send(s, "setPlayer(Alice,wrong)"), "a wrong token must not bind");
		assertFalse(s.isForCurrentPlayer(alice),
			"a rejected bind must leave the session observing nobody - the isolation guarantee");
	}

	@Test
	void missingToken_fails() throws Exception {
		setPlayerTokens(Map.of("Alice", "s3cret"));
		RemoteSession s = session();
		server.addPlayer("Alice");
		assertEquals("Fail", send(s, "setPlayer(Alice)"),
			"once player-tokens is configured, a listed player requires its token");
	}

	@Test
	void unlistedPlayer_failsClosed() throws Exception {
		// fail-closed: with the map non-empty, a player NOT in it cannot be bound at all
		setPlayerTokens(Map.of("Alice", "s3cret"));
		RemoteSession s = session();
		PlayerMock bob = server.addPlayer("Bob");
		server.addPlayer("Alice");
		assertEquals("Fail", send(s, "setPlayer(Bob,anything)"),
			"a player absent from player-tokens must not be bindable");
		assertFalse(s.isForCurrentPlayer(bob), "an unlisted player must not be observable");
	}

	@Test
	void offlinePlayer_failsRegardlessOfToken() throws Exception {
		setPlayerTokens(Map.of("Ghost", "s3cret"));
		RemoteSession s = session();
		server.addPlayer("Alice");
		assertEquals("Fail", send(s, "setPlayer(Ghost,s3cret)"),
			"an offline/unknown player must still fail even with a matching token");
	}

	// ---- config parsing (the real onEnable load path), Lens 2 ---------------

	@Test
	void readPlayerTokens_parsesNameTokenPairs() throws Exception {
		org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
		cfg.loadFromString("player-tokens:\n  Alice: a-secret\n  Bob: b-secret\n");
		Map<String, String> tokens = RaspberryJuicePlugin.readPlayerTokens(cfg.getConfigurationSection("player-tokens"));
		assertEquals(2, tokens.size(), "both listed players must be parsed");
		assertEquals("a-secret", tokens.get("Alice"));
		assertEquals("b-secret", tokens.get("Bob"));
	}

	@Test
	void readPlayerTokens_emptyOrMissing_yieldsAuthzOff() throws Exception {
		// the default "player-tokens: {}" (and an absent key) must leave the map empty -> authz OFF,
		// NOT locked-shut. This is what keeps single-user deploys frictionless (AC #3).
		assertTrue(RaspberryJuicePlugin.readPlayerTokens(null).isEmpty(), "absent section -> empty");
		org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
		cfg.loadFromString("player-tokens: {}\n");
		assertTrue(RaspberryJuicePlugin.readPlayerTokens(cfg.getConfigurationSection("player-tokens")).isEmpty(),
			"empty {} section -> empty map -> per-player authz stays off");
	}

	@Test
	void twoClients_onlyTheOneWithTheTokenBinds() throws Exception {
		// the multi-distrusting-client AC: same target, only the holder of Alice's token may bind
		setPlayerTokens(Map.of("Alice", "alice-secret"));
		RemoteSession authorized = session();
		RemoteSession attacker = session();
		PlayerMock alice = server.addPlayer("Alice");
		server.addPlayer("Mallory");

		assertEquals("Fail", send(attacker, "setPlayer(Alice,guess)"), "attacker without the token is refused");
		assertFalse(attacker.isForCurrentPlayer(alice), "attacker must not observe Alice");

		assertEquals("1", send(authorized, "setPlayer(Alice,alice-secret)"), "token holder binds");
		assertTrue(authorized.isForCurrentPlayer(alice), "token holder observes Alice");
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
