package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * Guards command-line parsing (#4): a malformed packet must be rejected gracefully,
 * never thrown out of handleLine (which previously blew up the whole tick loop).
 */
class RemoteSessionParseTest {

	private RemoteSession newSession() throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		RemoteSession s = new RemoteSession(plugin, socket);
		// give it an origin so well-formed in-memory commands (events.clear) can execute
		s.setOrigin(new Location(mock(World.class), 0, 0, 0));
		return s;
	}

	@Test
	void handleLine_doesNotThrow_onMissingParen() throws Exception {
		RemoteSession s = newSession();
		assertDoesNotThrow(() -> s.handleLine("garbage with no paren"));
	}

	@Test
	void handleLine_doesNotThrow_onEmptyLine() throws Exception {
		RemoteSession s = newSession();
		assertDoesNotThrow(() -> s.handleLine(""));
	}

	@Test
	void handleLine_doesNotThrow_onUnterminatedCommand() throws Exception {
		RemoteSession s = newSession();
		assertDoesNotThrow(() -> s.handleLine("world.getBlock(0,0,0"));
	}

	@Test
	void handleLine_doesNotThrow_onWellFormedNoArgCommand() throws Exception {
		RemoteSession s = newSession();
		// events.clear() touches only in-memory queues, no world needed
		assertDoesNotThrow(() -> s.handleLine("events.clear()"));
	}
}
