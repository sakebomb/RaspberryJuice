package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;

/**
 * Free text with commas and pipes on the wire (#59). A session that sends protocol.escape(1)
 * gets exact text in both directions; a session that doesn't keeps the classic framing.
 */
class RemoteSessionWireEscapeTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-escape-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	private PlayerMock host;

	@BeforeEach
	void host() {
		host = server.addPlayer();
		host.setLocation(new Location(world, 0, 64, 0));
		plugin.hostPlayer = host;
	}

	private RemoteSession session() throws IOException {
		RemoteSession s = new TestSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, 0, 0, 0));
		return s;
	}

	private RemoteSession escapingSession() throws IOException {
		RemoteSession s = session();
		s.handleLine("protocol.escape(1)");
		assertEquals(List.of("1"), s.drainSentForTest());
		return s;
	}

	private LivingEntity spawnZombie(RemoteSession owner) {
		LivingEntity zed = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
		owner.ownedEntities.add(zed.getEntityId());
		return zed;
	}

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	private AsyncChatEvent chat(String message) {
		AsyncChatEvent event = mock(AsyncChatEvent.class);
		when(event.getPlayer()).thenReturn(host);
		when(event.message()).thenReturn(Component.text(message));
		return event;
	}

	// ---- handshake ----------------------------------------------------------

	@Test
	void protocolEscape_acceptsOnAndOff_rejectsAnythingElse() throws Exception {
		RemoteSession s = session();
		s.handleLine("protocol.escape(0)");
		assertEquals("1", lastSent(s));
		s.handleLine("protocol.escape(2)");
		assertEquals("Fail", lastSent(s));
	}

	@Test
	void protocolEscape_off_restoresClassicFraming() throws Exception {
		RemoteSession s = escapingSession();
		s.handleLine("protocol.escape(0)");
		s.drainSentForTest();
		s.queueChatPostedEvent(chat("a|b"));
		s.handleLine("events.chat.posts()");
		assertEquals(host.getEntityId() + ",a|b", lastSent(s));
	}

	// ---- inbound ------------------------------------------------------------

	@Test
	void setName_keepsAnEscapedComma() throws Exception {
		RemoteSession s = escapingSession();
		LivingEntity z = spawnZombie(s);
		s.handleLine("entity.setName(" + z.getEntityId() + ",Bob\\, the \\| Builder)");
		assertEquals(Component.text("Bob, the | Builder"), z.customName());
	}

	@Test
	void setName_keepsCommas_forClassicClientsToo() throws Exception {
		RemoteSession s = session();
		LivingEntity z = spawnZombie(s);
		s.handleLine("entity.setName(" + z.getEntityId() + ",Bob, the Builder)");
		assertEquals(Component.text("Bob, the Builder"), z.customName());
	}

	@Test
	void chatPost_keepsEscapedText() throws Exception {
		RemoteSession s = escapingSession();
		while (host.nextComponentMessage() != null) { } // drop the join greeting
		s.handleLine("chat.post(one\\, two \\| three)");
		assertEquals("one, two | three", PlainText.plain(host.nextComponentMessage()));
	}

	// ---- outbound -----------------------------------------------------------

	@Test
	void chatPosts_escapeTheMessage() throws Exception {
		RemoteSession s = escapingSession();
		s.queueChatPostedEvent(chat("gg | wp, ok\\"));
		s.queueChatPostedEvent(chat("second"));
		s.handleLine("events.chat.posts()");
		int id = host.getEntityId();
		assertEquals(id + ",gg \\| wp\\, ok\\\\|" + id + ",second", lastSent(s));
	}

	@Test
	void chatPosts_classicSessionGetsRawText() throws Exception {
		RemoteSession s = session();
		s.queueChatPostedEvent(chat("gg, wp"));
		s.handleLine("events.chat.posts()");
		assertEquals(host.getEntityId() + ",gg, wp", lastSent(s));
	}

	// MockBukkit's Entity.getName() ignores custom names, so these use player list names. The
	// custom-mob path is the same wireText call and is covered by the in-game e2e test.

	@Test
	void getName_escapesAPlayerListName() throws Exception {
		RemoteSession s = escapingSession();
		host.playerListName(Component.text("Bob, the|Builder"));
		s.handleLine("entity.getName(" + host.getEntityId() + ")");
		assertEquals("Bob\\, the\\|Builder", lastSent(s));
	}

	@Test
	void projectileHits_escapeShooterAndTargetNames() throws Exception {
		RemoteSession s = escapingSession();
		host.playerListName(Component.text("Ann|A"));
		PlayerMock target = server.addPlayer();
		target.playerListName(Component.text("Bob, the|Builder"));
		Arrow arrow = mock(Arrow.class);
		when(arrow.getType()).thenReturn(EntityType.ARROW);
		when(arrow.getShooter()).thenReturn(host);
		when(arrow.getLocation()).thenReturn(new Location(world, 1, 64, 2));
		s.queueProjectileHitEvent(new ProjectileHitEvent(arrow, target, null, null));
		s.handleLine("events.projectile.hits()");
		assertEquals("1,64,2,1,Ann\\|A,Bob\\, the\\|Builder", lastSent(s));
	}

	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException { super(plugin, socket); }
		@Override protected void startThreads() { }
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
