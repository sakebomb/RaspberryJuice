package net.zhuoweizhang.raspberryjuice;

import java.io.IOException;
import java.net.Socket;

/**
 * A RemoteSession with no IO threads, for tests that build a session on a Mockito plugin mock.
 *
 * A live session's input and output threads call {@code plugin.getLogger()}. Mockito records the
 * last call on the mock itself, so a thread's call landing inside a test's later
 * {@code when(plugin.x()).thenReturn(v)} stubs getLogger instead of x, and the test fails at
 * random. The output thread would also race {@code drainSentForTest()} for queued replies.
 */
class QuietSession extends RemoteSession {
	QuietSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
		super(plugin, socket);
	}

	@Override
	protected void startThreads() { }
}
