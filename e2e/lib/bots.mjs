// Mineflayer players. They speak 26.1; ViaVersion + ViaBackwards on the test server translate.
// Hand actions return whether the client saw them land. The test does not trust that answer:
// the block owner's socket read is the oracle, because a cancelled edit can flicker client-side.
import mineflayer from 'mineflayer';
import { Vec3 } from 'vec3';

const BOT_PROTOCOL_VERSION = '26.1';
const HAND_ACTION_TIMEOUT_MS = 3_000;
// Survival reach is 4.5 blocks from the eyes, creative is 5. Use the tighter one.
const REACH = 4.5;

export function joinBot(name, port, timeoutMs) {
	const bot = mineflayer.createBot({
		host: '127.0.0.1', port, username: name, auth: 'offline',
		version: BOT_PROTOCOL_VERSION, hideErrors: true,
	});
	return new Promise((resolve, reject) => {
		const timer = setTimeout(() => reject(new Error(`${name} did not spawn within ${timeoutMs}ms`)), timeoutMs);
		bot.once('spawn', () => { clearTimeout(timer); resolve(bot); });
		bot.once('kicked', (reason) => { clearTimeout(timer); reject(new Error(`${name} kicked: ${JSON.stringify(reason)}`)); });
		bot.once('error', (err) => { clearTimeout(timer); reject(err); });
	});
}

/** Waits until the bot's client has the chunk at (x, z) and the block there is known. */
export async function waitForBlock(bot, x, y, z, timeoutMs) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (bot.blockAt(new Vec3(x, y, z))) return;
		await sleep(100);
	}
	throw new Error(`${bot.username} never loaded block ${x},${y},${z}`);
}

/** Left-click breaks the block at (x, y, z). Creative mode makes it instant. */
export async function breakBlock(bot, x, y, z) {
	const block = bot.blockAt(new Vec3(x, y, z));
	if (!block || block.name === 'air') throw new Error(`${bot.username}: nothing to break at ${x},${y},${z}`);
	assertCanAct(bot, block);
	return withTimeout(bot.dig(block, true));
}

/**
 * A refused-edit check reads "unchanged" whether the server refused the action or the client never
 * sent it. Throw for every way it might not be sent: disconnected, or the block out of reach.
 */
function assertCanAct(bot, block) {
	if (!bot.entity || bot._client.state !== 'play') throw new Error(`${bot.username} is not in the game`);
	const eye = bot.entity.position.offset(0, bot.entity.eyeHeight ?? 1.62, 0);
	const distance = eye.distanceTo(block.position.offset(0.5, 0.5, 0.5));
	if (distance > REACH) {
		throw new Error(`${bot.username} is ${distance.toFixed(2)} blocks from ${block.position}, past reach ${REACH}`);
	}
}

/** Places the held item on the top face of the block at (x, y, z), so into (x, y + 1, z). */
export async function placeOnTop(bot, itemName, x, y, z) {
	const item = bot.inventory.items().find((i) => i.name === itemName);
	if (!item) throw new Error(`${bot.username} has no ${itemName}`);
	await bot.equip(item, 'hand');
	const reference = bot.blockAt(new Vec3(x, y, z));
	// Without a solid block to click, the client never sends the place, and a refused-edit check
	// would pass for the wrong reason.
	if (!reference || reference.name === 'air') throw new Error(`${bot.username}: nothing to place on at ${x},${y},${z}`);
	assertCanAct(bot, reference);
	return withTimeout(bot.placeBlock(reference, new Vec3(0, 1, 0)));
}

async function withTimeout(promise) {
	let timer;
	const timeout = new Promise((resolve) => { timer = setTimeout(() => resolve(false), HAND_ACTION_TIMEOUT_MS); });
	try {
		return await Promise.race([promise.then(() => true, () => false), timeout]);
	} finally {
		clearTimeout(timer);
	}
}

export function sleep(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}
