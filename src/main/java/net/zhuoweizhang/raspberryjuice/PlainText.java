package net.zhuoweizhang.raspberryjuice;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Adventure {@link Component} &lt;-&gt; plain {@link String} helpers.
 *
 * <p>The mcpi wire protocol is plain text, but the modern Bukkit/Paper chat and
 * player-name APIs use Adventure components. These helpers convert at that boundary.
 *
 * <p><b>Formatting note:</b> {@link #plain} intentionally <em>strips</em> legacy section
 * ({@code §}) colour/formatting codes. This differs from the removed
 * {@code Player.getPlayerListName()}, which returned a legacy-section string that kept the
 * codes. For the mcpi use cases (matching a human-typed name in {@code world.getPlayerId},
 * and returning player names to scripts) the un-formatted name is the correct, more robust
 * value - it also matches names that legacy code with embedded colour codes would have
 * failed to match. Where legacy {@code §} codes should still be honoured (broadcasting a
 * chat line), use {@link #legacy} instead.
 *
 * <p>The serializer singletons are immutable and thread-safe.
 */
public final class PlainText {

	private PlainText() {
	}

	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

	/** Serialize a component to plain text, discarding colour/formatting. */
	public static String plain(Component component) {
		return PLAIN.serialize(component);
	}

	/** Wrap a plain string as a literal text component (no {@code §} code interpretation). */
	public static Component component(String text) {
		return Component.text(text);
	}

	/** Parse a string that may contain legacy section ({@code §}) codes into a formatted component. */
	public static Component legacy(String text) {
		return LEGACY.deserialize(text);
	}
}
