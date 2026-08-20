package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-IP fixed-window connection-rate limiter (#56). {@code now} is injected so
 * window behaviour is deterministic (no reliance on wall-clock timing).
 *
 * <p>Mutation guard: dropping the {@code count >= maxPerWindow} check makes the over-limit
 * connection allowed ({@code allowsUpToLimitThenRefuses} red); dropping the window reset keeps it
 * refused forever ({@code resetsAfterWindow} red).
 */
class ConnectionRateLimiterTest {

	@Test
	void allowsUpToLimitThenRefuses() {
		ConnectionRateLimiter limiter = new ConnectionRateLimiter(3, 60_000L);
		long t = 1_000L;
		assertTrue(limiter.allow("1.2.3.4", t));
		assertTrue(limiter.allow("1.2.3.4", t));
		assertTrue(limiter.allow("1.2.3.4", t));
		assertFalse(limiter.allow("1.2.3.4", t), "the 4th connection in the window must be refused");
	}

	@Test
	void resetsAfterWindow() {
		ConnectionRateLimiter limiter = new ConnectionRateLimiter(2, 60_000L);
		assertTrue(limiter.allow("1.2.3.4", 0L));
		assertTrue(limiter.allow("1.2.3.4", 100L));
		assertFalse(limiter.allow("1.2.3.4", 200L), "over the limit within the window");
		// once the window has fully elapsed, the counter resets
		assertTrue(limiter.allow("1.2.3.4", 60_000L), "a new window should allow again");
	}

	@Test
	void perIpIndependent() {
		ConnectionRateLimiter limiter = new ConnectionRateLimiter(1, 60_000L);
		assertTrue(limiter.allow("10.0.0.1", 0L));
		assertFalse(limiter.allow("10.0.0.1", 0L), "same IP over its limit");
		assertTrue(limiter.allow("10.0.0.2", 0L), "a different IP has its own budget");
	}

	@Test
	void nonPositiveLimitDisablesRateLimiting() {
		ConnectionRateLimiter limiter = new ConnectionRateLimiter(0, 60_000L);
		for (int i = 0; i < 1000; i++) {
			assertTrue(limiter.allow("1.2.3.4", 0L), "a limit of 0 means unlimited");
		}
	}
}
