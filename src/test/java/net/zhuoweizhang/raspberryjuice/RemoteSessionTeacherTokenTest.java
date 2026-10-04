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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Socket teacher commands (#18 PR 6): {@code classroom.freeze/unfreeze/reset(name,token)}.
 * Empty token denies with no strike. A wrong token strikes on its own counter and closes at
 * three. A match does the same work as {@code /rj}, whatever the caller's plot or freeze bit.
 */
class RemoteSessionTeacherTokenTest {

	private static final String TOKEN = "teach-s3cret";
	private static final String PEER_IP = "10.0.0.7";
	private static final int SPAWN_X = 100;
	private static final int SPAWN_Y = 64;
	private static final int SPAWN_Z = 200;

	private ServerMock server;
	private RaspberryJuicePlugin plugin;
	private World world;
	private PlayerMock alice;

	@BeforeEach
	void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-teacher-token");
		world.setSpawnLocation(SPAWN_X, SPAWN_Y, SPAWN_Z);
		plugin.refreshSandboxOrigin();
		alice = server.addPlayer("Alice");
	}

	@AfterEach
	void shutdown() {
		plugin.sessions.clear();
		MockBukkit.unmock();
	}

	// ---- token gate ---------------------------------------------------------

	@Test
	void emptyToken_failsEveryCommandWithoutStrikingOrFreezing() throws Exception {
		LockoutSession s = session();
		for (int i = 0; i < 4; i++) {
			assertEquals("Fail", send(s, "classroom.freeze(Alice,anything)"));
			assertEquals("Fail", send(s, "classroom.unfreeze(Alice,)"));
			assertEquals("Fail", send(s, "classroom.reset(Alice,anything)"));
		}
		assertEquals(0, s.closeCount, "no configured token means nothing to brute-force");
		assertFalse(plugin.isFrozen(alice.getUniqueId()));
	}

	@Test
	void wrongToken_failsAndClosesOnTheThirdStrike() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		LockoutSession s = session();
		assertEquals("Fail", send(s, "classroom.freeze(Alice,wrong1)"));
		assertEquals("Fail", send(s, "classroom.reset(Alice,wrong2)"));
		assertEquals(0, s.closeCount, "two failures must not trip the lockout");
		assertEquals("Fail", send(s, "classroom.unfreeze(Alice)"));
		assertEquals(1, s.closeCount, "a missing token is a mismatch and the third one closes");
		assertFalse(plugin.isFrozen(alice.getUniqueId()));
	}

	@Test
	void teacherAndSetPlayerStrikes_useSeparateCounters() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		setPlayerTokens(Map.of("Alice", "alice-secret"));
		LockoutSession s = session();
		send(s, "setPlayer(Alice,bad1)");
		send(s, "setPlayer(Alice,bad2)");
		send(s, "classroom.freeze(Alice,bad1)");
		send(s, "classroom.freeze(Alice,bad2)");
		assertEquals(0, s.closeCount, "two strikes on each counter is not three on either");
	}

	@Test
	void lockoutLogsTheAddressButNeverTheToken() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		List<String> logged = captureLog();
		LockoutSession s = session();
		send(s, "classroom.freeze(Alice," + TOKEN + ")");
		send(s, "classroom.freeze(Alice,guess-one)");
		send(s, "classroom.freeze(Alice,guess-two)");
		send(s, "classroom.freeze(Alice,guess-three)");
		assertEquals(1, s.closeCount);
		assertTrue(logged.stream().anyMatch(line -> line.contains(PEER_IP) && line.contains("teacher-token")),
			"the lockout must log the remote address: " + logged);
		for (String line : logged) {
			assertFalse(line.contains(TOKEN), line);
			assertFalse(line.contains("guess-"), line);
		}
	}

	@Test
	void successfulTeacherCommands_logWhoAndFromWhere_butNotTheToken() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		plots("""
			plots:
			  Ghost: [0, 0, 0, 3, 0, 3]
			""");
		List<String> logged = captureLog();
		LockoutSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		send(s, "classroom.freeze(Alice," + TOKEN + ")");
		send(s, "classroom.unfreeze(Alice," + TOKEN + ")");
		send(s, "classroom.reset(Ghost," + TOKEN + ")");
		for (String action : List.of("classroom.freeze Alice", "classroom.unfreeze Alice", "classroom.reset Ghost")) {
			assertTrue(logged.stream().anyMatch(line -> line.contains(action) && line.contains(PEER_IP)
				&& line.contains("Alice")), action + " must be logged with the caller: " + logged);
		}
		for (String line : logged) {
			assertFalse(line.contains(TOKEN), line);
		}
	}

	@Test
	void refusedTeacherCommands_logNothingPerAttempt() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		LockoutSession s = session();
		List<String> logged = captureLog();
		send(s, "classroom.freeze(Ghost," + TOKEN + ")");
		send(s, "classroom.reset(Ghost," + TOKEN + ")");
		send(s, "classroom.freeze(Alice,wrong)");
		assertEquals(List.of(), logged);
	}

	@Test
	void teacherTokenWarning_flagsOnlyAComma() {
		assertEquals(null, RaspberryJuicePlugin.teacherTokenWarning(""));
		assertEquals(null, RaspberryJuicePlugin.teacherTokenWarning("long-random|secret"));
		String warning = RaspberryJuicePlugin.teacherTokenWarning("abc,def");
		assertTrue(warning != null && warning.contains("comma"), String.valueOf(warning));
		assertFalse(warning.contains("abc"), "the warning must not echo the token");
	}

	// ---- freeze / unfreeze ---------------------------------------------------

	@Test
	void freezeAndUnfreeze_withTheToken_changeTheOnlinePlayerAndAreIdempotent() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		LockoutSession s = session();
		assertEquals("1", send(s, "classroom.freeze(Alice," + TOKEN + ")"));
		assertTrue(plugin.isFrozen(alice.getUniqueId()));
		assertEquals("1", send(s, "classroom.freeze(Alice," + TOKEN + ")"));
		assertEquals("1", send(s, "classroom.unfreeze(Alice," + TOKEN + ")"));
		assertFalse(plugin.isFrozen(alice.getUniqueId()));
		assertEquals("1", send(s, "classroom.unfreeze(Alice," + TOKEN + ")"));
	}

	@Test
	void freezeOfAnOfflineName_failsWithoutStriking() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		LockoutSession s = session();
		for (int i = 0; i < 3; i++) {
			assertEquals("Fail", send(s, "classroom.freeze(Ghost," + TOKEN + ")"));
			assertEquals("Fail", send(s, "classroom.unfreeze(Ghost," + TOKEN + ")"));
		}
		assertEquals(0, s.closeCount);
	}

	@Test
	void frozenCaller_withTheToken_canStillRunTeacherCommands() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		plots("""
			plots:
			  Bob: [0, 0, 0, 3, 0, 3]
			""");
		LockoutSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		plugin.freeze(alice.getUniqueId());
		String why = "a token match is not gated by the caller's own freeze bit or missing plot";
		assertEquals("1", send(s, "classroom.freeze(Alice," + TOKEN + ")"), why);
		assertEquals("1", send(s, "classroom.reset(Bob," + TOKEN + ")"), why);
		assertEquals("1", send(s, "classroom.unfreeze(Alice," + TOKEN + ")"), why);
		assertFalse(plugin.isFrozen(alice.getUniqueId()));
	}

	// ---- reset ---------------------------------------------------------------

	@Test
	void reset_withTheToken_wipesTheConfiguredPlotEvenWhenTheStudentIsOffline() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		plots("""
			plots:
			  Ghost: [0, 0, 0, 3, 0, 3]
			""");
		spawnZombie(1, 0, 1);
		spawnZombie(8, 0, 0);
		LockoutSession s = session();
		assertEquals("1", send(s, "classroom.reset(Ghost," + TOKEN + ")"));
		assertEquals(1, zombies(), "only the zombie outside the plot remains");
	}

	@Test
	void reset_missingPlotOrOverCeiling_failsWithoutStrikingOrChanging() throws Exception {
		plugin.setClassroomTeacherToken(TOKEN);
		plots("""
			plots:
			  Alice: [0, 0, 0, 100000, 0, 1]
			""");
		spawnZombie(0, 0, 0);
		LockoutSession s = session();
		for (int i = 0; i < 2; i++) {
			assertEquals("Fail", send(s, "classroom.reset(Nobody," + TOKEN + ")"));
			assertEquals("Fail", send(s, "classroom.reset(Alice," + TOKEN + ")"));
		}
		assertEquals(1, zombies());
		assertEquals(0, s.closeCount);
	}

	// ---- helpers -------------------------------------------------------------

	private static final class LockoutSession extends RemoteSession {
		int closeCount = 0;
		LockoutSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException { super(plugin, socket); }
		@Override protected void startThreads() { }
		@Override public void close() { closeCount++; }
	}

	private LockoutSession session() throws IOException {
		LockoutSession s = new LockoutSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, SPAWN_X, SPAWN_Y, SPAWN_Z));
		plugin.sessions.add(s);
		return s;
	}

	private String send(RemoteSession s, String line) {
		s.handleLine(line);
		List<String> sent = s.drainSentForTest();
		assertEquals(1, sent.size(), "expected exactly one line for " + line + " but got " + sent);
		return sent.get(0);
	}

	private void plots(String yaml) throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString(yaml);
		plugin.installPlots(PlotBounds.readPlots(cfg));
	}

	@SuppressWarnings("unchecked")
	private void setPlayerTokens(Map<String, String> tokens) throws Exception {
		Field f = RaspberryJuicePlugin.class.getDeclaredField("playerTokens");
		f.setAccessible(true);
		((Map<String, String>) f.get(plugin)).putAll(tokens);
	}

	private List<String> captureLog() {
		List<String> lines = new ArrayList<>();
		plugin.getLogger().addHandler(new Handler() {
			@Override public void publish(LogRecord record) { lines.add(String.valueOf(record.getMessage())); }
			@Override public void flush() { }
			@Override public void close() { }
		});
		return lines;
	}

	private void spawnZombie(int x, int y, int z) {
		world.spawnEntity(new Location(world, SPAWN_X + x + 0.5, SPAWN_Y + y, SPAWN_Z + z + 0.5), EntityType.ZOMBIE);
	}

	private int zombies() {
		int n = 0;
		for (Entity entity : world.getEntities()) {
			if (entity.getType() == EntityType.ZOMBIE) n++;
		}
		return n;
	}

	private static final class FakeSocket extends Socket {
		private final InputStream in = new ByteArrayInputStream(new byte[0]);
		private final OutputStream out = new ByteArrayOutputStream();
		@Override public InputStream getInputStream() { return in; }
		@Override public OutputStream getOutputStream() { return out; }
		@Override public void setTcpNoDelay(boolean on) { }
		@Override public void setKeepAlive(boolean on) { }
		@Override public void setTrafficClass(int tc) { }
		@Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(PEER_IP, 4711); }
	}
}
