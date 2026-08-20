package net.zhuoweizhang.raspberryjuice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-IP fixed-window connection-rate limiter. Blunts a connection flood (resource-exhaustion
 * DoS) and slows token brute-forcing: the per-connection auth/setPlayer lockouts only close the
 * <em>current</em> connection after 3 bad guesses, so without this an attacker just reconnects for
 * another 3 with no cooldown (#56).
 *
 * <p>Fixed window (a counter + window start per IP) rather than a sliding window: O(1), tiny memory,
 * and the coarse 2x-at-boundary burst it allows is immaterial for a DoS/brute-force guard. Callers
 * pass {@code now} so the logic is deterministically testable. Thread-safe (the accept loop is a
 * single thread today, but keep it safe in case that changes).
 */
final class ConnectionRateLimiter {

	/** Beyond this many tracked IPs, expired windows are pruned so an IP-spoofing flood can't grow
	 *  the map without bound (the very memory this class exists to protect). */
	private static final int MAX_TRACKED_IPS = 10_000;

	private final int maxPerWindow;
	private final long windowMillis;
	private final Map<String, Window> byIp = new ConcurrentHashMap<>();

	private static final class Window {
		long start;
		int count;
	}

	/** @param maxPerWindow max new connections per IP per window; {@code <= 0} disables the limit.
	 *  @param windowMillis the window length in milliseconds. */
	ConnectionRateLimiter(int maxPerWindow, long windowMillis) {
		this.maxPerWindow = maxPerWindow;
		this.windowMillis = windowMillis;
	}

	/** Records a connection attempt from {@code ip} at {@code now}, returning true if it is within
	 *  the rate and false if it should be refused. */
	boolean allow(String ip, long now) {
		if (maxPerWindow <= 0) return true; // disabled
		if (byIp.size() > MAX_TRACKED_IPS) pruneExpired(now);
		Window w = byIp.computeIfAbsent(ip, k -> new Window());
		synchronized (w) {
			if (now - w.start >= windowMillis) { // window elapsed -> reset
				w.start = now;
				w.count = 0;
			}
			if (w.count >= maxPerWindow) return false;
			w.count++;
			return true;
		}
	}

	/** Drops windows whose period has fully elapsed, bounding memory under an IP-spoofing flood. */
	private void pruneExpired(long now) {
		byIp.forEach((ip, w) -> {
			synchronized (w) {
				if (now - w.start >= windowMillis) byIp.remove(ip, w);
			}
		});
	}
}
