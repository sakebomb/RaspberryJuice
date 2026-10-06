package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The opt-in escaping for free text on the wire (#59). */
class WireTextTest {

	@Test
	void splitArgs_plainCommas() {
		assertArrayEquals(new String[] {"1", "2", "3"}, WireText.splitArgs("1,2,3"));
	}

	@Test
	void splitArgs_escapedCommaStaysInTheArgument() {
		assertArrayEquals(new String[] {"5", "Bob, the Builder"}, WireText.splitArgs("5,Bob\\, the Builder"));
	}

	@Test
	void splitArgs_decodesPipeAndBackslash() {
		assertArrayEquals(new String[] {"a|b\\c"}, WireText.splitArgs("a\\|b\\\\c"));
	}

	@Test
	void splitArgs_keepsAnUnknownEscapeAndATrailingBackslash() {
		assertArrayEquals(new String[] {"C:\\temp", "end\\"}, WireText.splitArgs("C:\\temp,end\\"));
	}

	@Test
	void splitArgs_keepsEmptyFields() {
		assertArrayEquals(new String[] {"a", "", "b", ""}, WireText.splitArgs("a,,b,"));
		assertArrayEquals(new String[] {""}, WireText.splitArgs(""));
	}

	@Test
	void escape_marksEveryDelimiter() {
		assertEquals("gg \\| wp\\, ok\\\\", WireText.escape("gg | wp, ok\\"));
		assertEquals("plain", WireText.escape("plain"));
	}

	@Test
	void escapeThenSplit_roundTripsAnyText() {
		for (String text : new String[] {"", "a,b", "|", "\\", "\\,", "x\\|y,z|", "Bob, the|Builder\\"}) {
			assertArrayEquals(new String[] {text}, WireText.splitArgs(WireText.escape(text)), text);
		}
	}
}
