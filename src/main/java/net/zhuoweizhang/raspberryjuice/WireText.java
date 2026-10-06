package net.zhuoweizhang.raspberryjuice;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in escaping for free text on the mcpi wire (#59).
 *
 * <p>The classic protocol splits command arguments on {@code ,} and joins event records with
 * {@code |}, with no way to carry either character inside a value. A session that sends
 * {@code protocol.escape(1)} switches to this scheme: {@code \,} {@code \|} and {@code \\} stand
 * for {@code ,} {@code |} and {@code \} inside a value, in both directions. A backslash before
 * any other character, or at the end of the text, is kept as is, so Windows paths and stray
 * backslashes survive. Sessions that never opt in keep the classic behaviour.
 */
public final class WireText {

	private WireText() {
	}

	/**
	 * Split a command's argument text on unescaped commas and decode each argument. Trailing empty
	 * arguments are dropped, as {@link String#split(String)} drops them in classic mode, so a
	 * command parses the same way whether or not the session opted in.
	 */
	public static String[] splitArgs(String text) {
		List<String> args = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length() && isEscapable(text.charAt(i + 1))) {
				current.append(text.charAt(++i));
			} else if (c == ',') {
				args.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		args.add(current.toString());
		while (args.size() > 1 && args.get(args.size() - 1).isEmpty()) args.remove(args.size() - 1);
		return args.toArray(new String[0]);
	}

	/** Escape a free-text value so it survives as one field of a record. */
	public static String escape(String value) {
		StringBuilder b = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (isEscapable(c)) b.append('\\');
			b.append(c);
		}
		return b.toString();
	}

	private static boolean isEscapable(char c) {
		return c == ',' || c == '|' || c == '\\';
	}
}
