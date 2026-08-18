package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Verifies the Adventure Component &lt;-&gt; plain-text boundary used to keep the mcpi wire
 * protocol on plain strings after the migration off the deprecated String chat/name APIs.
 */
class PlainTextTest {

	@Test
	void plain_serializesTextComponentToItsContent() {
		assertEquals("hello world", PlainText.plain(Component.text("hello world")));
		assertEquals("", PlainText.plain(Component.empty()));
	}

	@Test
	void component_roundTripsThroughPlain() {
		assertEquals("RaspberryJuice", PlainText.plain(PlainText.component("RaspberryJuice")));
	}

	@Test
	void plain_stripsLegacyFormattingCodes() {
		// documents the intentional divergence from the removed getPlayerListName(): a colour-coded
		// list name serializes to just the name, so a plain client name still matches in getPlayerId
		Component coloured = Component.text("Steve", NamedTextColor.RED);
		assertEquals("Steve", PlainText.plain(coloured));
	}

	@Test
	void legacy_interpretsSectionCodes() {
		// chat.post broadcasts via legacy() so a section-coded message renders as colour (as the old
		// broadcastMessage(String) did) rather than showing the raw codes as literal text
		assertEquals("red", PlainText.plain(PlainText.legacy("§cred")));
	}

	@Test
	void playerListName_serializesToPlayerName() {
		ServerMock server = MockBukkit.mock();
		try {
			PlayerMock player = server.addPlayer("Steve");
			// getNamedPlayer matches the client-supplied name against this plain-text list name;
			// for a normal player it must equal the player's name (behavior preserved from getPlayerListName()).
			assertEquals("Steve", PlainText.plain(player.playerListName()));
		} finally {
			MockBukkit.unmock();
		}
	}
}
