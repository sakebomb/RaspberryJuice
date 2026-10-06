// In-game end-to-end test for the #18 classroom sandbox. Boots Paper 26.2 with the built plugin,
// joins two players (Alice, Bob) whose plots sit two blocks apart, and checks every protection
// against the live server: socket fences, the break/place listener, freeze, reset, and the socket
// teacher token. Run e2e/setup.sh first. Exit code 0 = every check passed.
//
// Plots use ABSOLUTE coordinates on a flat world: the grass top is y = -61, so y = -60 is the
// first air layer. The owner's socket read is the oracle for every block check.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Vec3 } from 'vec3';

import { PaperServer } from './lib/server.mjs';
import { RjSocket } from './lib/socket.mjs';
import { joinBot, waitForBlock, breakBlock, placeOnTop, sleep } from './lib/bots.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const MC_PORT = Number(process.env.E2E_MC_PORT ?? 25599);
const RJ_PORT = Number(process.env.E2E_RJ_PORT ?? 4799);
const TOKEN = 'e2e-teacher-token';
const BOOT_TIMEOUT_MS = 180_000;
const REPLY_TIMEOUT_MS = 20_000;
const SETTLE_MS = 1_000;

const Y = -60;
const AIR = '0', STONE = '1', GOLD = '41';
const PIG = 90; // pig: passive, survives peaceful difficulty

const results = [];
function check(label, ok, detail = '') {
	results.push(ok);
	console.log(`[${ok ? 'PASS' : 'FAIL'}] ${label}${detail ? ` — ${detail}` : ''}`);
}

function pluginConfig() {
	let yml = readFileSync(join(HERE, '..', 'src', 'main', 'resources', 'config.yml'), 'utf8');
	const edits = {
		'port: 4711': `port: ${RJ_PORT}`,
		'location: RELATIVE': 'location: ABSOLUTE',
		'enable-op-commands: true': 'enable-op-commands: false',
		'plots: {}': `plots:\n  Alice: [0, ${Y}, 0, 7, ${Y + 5}, 7]\n  Bob: [10, ${Y}, 0, 17, ${Y + 5}, 7]`,
		"classroom-teacher-token: ''": `classroom-teacher-token: '${TOKEN}'`,
	};
	for (const [from, to] of Object.entries(edits)) {
		if (!yml.includes(from)) throw new Error(`config.yml no longer contains "${from}"`);
		yml = yml.replace(from, to);
	}
	return yml;
}

async function block(sock, x, y, z) {
	return (await sock.call(`world.getBlock(${x},${y},${z})`))?.split(',')[0];
}

/** Polls the owner's read until it equals {@code expected}; for edits that should land. */
async function becomes(sock, x, y, z, expected, timeoutMs = 5_000) {
	const deadline = Date.now() + timeoutMs;
	let got;
	do {
		got = await block(sock, x, y, z);
		if (got === expected) return got;
		await sleep(200);
	} while (Date.now() < deadline);
	return got;
}

/** Waits for any change to land, then reads once; for edits that should be refused. */
async function stays(sock, x, y, z) {
	await sleep(SETTLE_MS);
	return block(sock, x, y, z);
}

async function setUp(server) {
	const alice = await joinBot('Alice', MC_PORT, 60_000);
	const bob = await joinBot('Bob', MC_PORT, 60_000);
	for (const [name, x] of [['Alice', 6.5], ['Bob', 11.5]]) {
		server.command(`gamemode creative ${name}`);
		server.command(`tp ${name} ${x} ${Y} 3.5`);
		server.command(`give ${name} stone 64`);
	}
	const sa = await RjSocket.connect(RJ_PORT, REPLY_TIMEOUT_MS);
	const sb = await RjSocket.connect(RJ_PORT, REPLY_TIMEOUT_MS);
	check('Alice socket binds with setPlayer', (await sa.call('setPlayer(Alice)')) === '1');
	check('Bob socket binds with setPlayer', (await sb.call('setPlayer(Bob)')) === '1');
	await waitForBlock(alice, 10, Y, 3, 20_000);
	await waitForBlock(bob, 7, Y, 3, 20_000);
	return { alice, bob, sa, sb };
}

async function socketFences({ sa, sb }) {
	sa.send(`world.setBlock(2,${Y},2,${GOLD})`);
	check('own-plot socket write lands', (await becomes(sa, 2, Y, 2, GOLD)) === GOLD);
	sa.send(`world.setBlock(12,${Y},2,${GOLD})`);
	await sa.sync();
	check('cross-plot socket write is silent', sa.stray.length === 0, `stray ${JSON.stringify(sa.stray)}`);
	check('cross-plot socket write does not land', (await stays(sb, 12, Y, 2)) === AIR);
	check('cross-plot getBlocks answers Fail', (await sa.call(`world.getBlocks(10,${Y},0,11,${Y},1)`)) === 'Fail');
	check('cross-plot getBlock answers Fail', (await sb.call(`world.getBlock(2,${Y},2)`)) === 'Fail');
	check('cross-plot spawnEntity answers Fail',
		(await sa.call(`world.spawnEntity(12,${Y},2,${PIG})`)) === 'Fail');
}

async function handProtection(server, { alice, sa, sb }) {
	// Separate targets per action, so one check's outcome cannot set up the next one.
	sb.send(`world.setBlock(10,${Y},3,${STONE})`);
	sb.send(`world.setBlock(10,${Y},4,${STONE})`);
	sa.send(`world.setBlock(7,${Y},3,${GOLD})`);
	sa.send(`world.setBlock(5,${Y},3,${STONE})`);
	await becomes(sb, 10, Y, 3, STONE);
	await becomes(sb, 10, Y, 4, STONE);
	await becomes(sa, 5, Y, 3, STONE);
	await sleep(SETTLE_MS); // let the clients receive the new blocks

	await breakBlock(alice, 10, Y, 3);
	check("Alice cannot break a block in Bob's plot", (await stays(sb, 10, Y, 3)) === STONE);
	await breakBlock(alice, 7, Y, 3);
	check('Alice can break a block in her own plot', (await becomes(sa, 7, Y, 3, AIR)) === AIR);

	await placeOnTop(alice, 'stone', 10, Y, 4);
	check("Alice cannot place a block in Bob's plot", (await stays(sb, 10, Y + 1, 4)) === AIR);
	await placeOnTop(alice, 'stone', 5, Y, 3);
	check('Alice can place a block in her own plot', (await becomes(sa, 5, Y + 1, 3, STONE)) === STONE);
}

// Paper logs a listener crash as "Could not pass event ..." at ERROR, and a command handler crash
// as "Error handling command". Anything at ERROR after boot is a failure, whatever logged it.
const SERVER_ERROR = /\bERROR\]|Could not pass event|Error handling command|Exception\b/;

async function freeze(server, { alice, sa }) {
	let mark = server.command('rj freeze Alice');
	await server.waitFor(/Froze Alice's socket/, 10_000, mark);
	sa.send(`world.setBlock(3,${Y},3,${GOLD})`);
	check('frozen socket write does not land', (await stays(sa, 3, Y, 3)) === AIR);
	check('frozen socket can still read', (await block(sa, 2, Y, 2)) === GOLD);
	check('frozen socket spawnEntity answers Fail',
		(await sa.call(`world.spawnEntity(3,${Y},3,${PIG})`)) === 'Fail');
	await breakBlock(alice, 5, Y + 1, 3);
	check('frozen player can still build by hand', (await becomes(sa, 5, Y + 1, 3, AIR)) === AIR);

	mark = server.command('rj unfreeze Alice');
	await server.waitFor(/Unfroze Alice's socket/, 10_000, mark);
	sa.send(`world.setBlock(3,${Y},3,${GOLD})`);
	check('unfrozen socket write lands', (await becomes(sa, 3, Y, 3, GOLD)) === GOLD);
}

async function reset(server, { sa, sb }) {
	const pig = await sb.call(`world.spawnEntity(13,${Y},5,${PIG})`);
	check('owner can spawn a mob in its plot', /^\d+$/.test(pig ?? ''), `got ${pig}`);
	const mark = server.command('rj reset Bob');
	await server.waitFor(/Reset Bob's plot to air/, 15_000, mark);
	check("/rj reset clears Bob's blocks", (await block(sb, 10, Y, 3)) === AIR);
	check("/rj reset removes Bob's mob", (await sb.call(`entity.getPos(${pig})`)) === 'Fail');
	check("/rj reset leaves Alice's plot alone", (await block(sa, 2, Y, 2)) === GOLD);
}

async function teacherToken(server, { sa }) {
	const teacher = await RjSocket.connect(RJ_PORT, REPLY_TIMEOUT_MS);
	const mark = server.lines.length;
	check('token freeze answers 1', (await teacher.call(`classroom.freeze(Alice,${TOKEN})`)) === '1');
	sa.send(`world.setBlock(4,${Y},4,${GOLD})`);
	check('token freeze stops the socket', (await stays(sa, 4, Y, 4)) === AIR);
	check('token unfreeze answers 1', (await teacher.call(`classroom.unfreeze(Alice,${TOKEN})`)) === '1');
	check('token reset answers 1', (await teacher.call(`classroom.reset(Alice,${TOKEN})`)) === '1');
	check("token reset clears Alice's plot", (await becomes(sa, 2, Y, 2, AIR)) === AIR);
	check('token reset of an unplotted name answers Fail',
		(await teacher.call(`classroom.reset(Nobody,${TOKEN})`)) === 'Fail');
	const audit = server.logSince(mark).filter((l) => /classroom\.(freeze|unfreeze|reset) Alice by an unbound session from/.test(l));
	check('each token action leaves one audit line', audit.length === 3, `${audit.length} lines`);

	for (const guess of ['wrong-1', 'wrong-2', 'wrong-3']) await teacher.call(`classroom.freeze(Alice,${guess})`);
	await sleep(SETTLE_MS);
	check('third wrong token closes the connection', teacher.closed);
	check('lockout is logged', server.logSince(mark).some((l) => /failed classroom teacher-token attempts/.test(l)));
	check('no token or guess reaches the log', !server.lines.some((l) => l.includes(TOKEN) || l.includes('wrong-')));
}

/** Calls {@code line} until the reply is non-empty; for event polls that wait on the server. */
async function pollUntil(sock, line, timeoutMs = 5_000) {
	const deadline = Date.now() + timeoutMs;
	let got;
	do {
		got = await sock.call(line);
		if (got) return got;
		await sleep(200);
	} while (Date.now() < deadline);
	return got;
}

// Commas and pipes in free text (#59). Alice's socket opts into escaping; Bob's stays classic.
async function wireText({ alice, sa, sb }) {
	check('protocol.escape(1) answers 1', (await sa.call('protocol.escape(1)')) === '1');

	sa.send(`world.setSign(1,${Y + 1},1,63,0,Hi\\, there,a \\| b,(ok))`);
	await waitForBlock(alice, 1, Y + 1, 1, 5_000);
	let front;
	const deadline = Date.now() + 5_000;
	do {
		front = alice.blockAt(new Vec3(1, Y + 1, 1))?.getSignText?.()[0];
		if (front?.startsWith('Hi')) break;
		await sleep(200);
	} while (Date.now() < deadline);
	check('escaped sign lines keep commas, pipes and parens',
		front?.split('\n').slice(0, 3).join('/') === 'Hi, there/a | b/(ok)', JSON.stringify(front));

	const pig = await sa.call(`world.spawnEntity(4,${Y},4,${PIG})`);
	sa.send(`entity.setName(${pig},Bob\\, the \\| Builder)`);
	check('escaped custom name round-trips', (await sa.call(`entity.getName(${pig})`)) === 'Bob\\, the \\| Builder');

	const aliceId = await sa.call('world.getPlayerId(Alice)');
	sa.send('events.clear()');
	await sa.sync(); // the clear runs on a later tick and must not wipe the chat below
	alice.chat('gg | wp, ok');
	const post = await pollUntil(sa, 'events.chat.posts()');
	check('chat event escapes the message', post === `${aliceId},gg \\| wp\\, ok`, JSON.stringify(post));

	const bobPig = await sb.call(`world.spawnEntity(13,${Y},5,${PIG})`);
	sb.send(`entity.setName(${bobPig},Bob, the Builder)`);
	check('classic setName keeps commas', (await sb.call(`entity.getName(${bobPig})`)) === 'Bob, the Builder');
}

async function main() {
	const server = new PaperServer(join(HERE, '.server'), { mcPort: MC_PORT, rjPort: RJ_PORT });
	let world = null;
	// Ctrl-C or a cancelled CI job must not leave Paper running and holding the ports.
	for (const signal of ['SIGINT', 'SIGTERM']) {
		process.once(signal, () => { server.kill(); process.exit(130); });
	}
	try {
		await server.start(pluginConfig(), BOOT_TIMEOUT_MS);
		const booted = server.lines.length;
		check('ViaVersion is enabled', server.lines.some((l) => /Enabling ViaVersion/.test(l)));
		check('RaspberryJuice is enabled', server.lines.some((l) => /Enabling RaspberryJuice/.test(l)));
		check('enable log reports the classroom sandbox',
			server.lines.some((l) => l.includes('Classroom sandbox on: 2 valid plots, 0 skipped keys.')));
		world = await setUp(server);
		await socketFences(world);
		await handProtection(server, world);
		await freeze(server, world);
		await reset(server, world);
		await teacherToken(server, world);
		await wireText(world);
		const errors = server.logSince(booted).filter((l) => SERVER_ERROR.test(l));
		check('no server errors after boot', errors.length === 0, errors.slice(0, 5).join(' | '));
	} catch (err) {
		check('harness ran to completion', false, err.stack ?? String(err));
	} finally {
		world?.sa.close();
		world?.sb.close();
		world?.alice.quit();
		world?.bob.quit();
		await server.stop();
	}
	const failed = results.filter((ok) => !ok).length;
	console.log(`\n${results.length - failed}/${results.length} checks passed`);
	if (failed && !process.env.E2E_VERBOSE) {
		console.log('Last server log lines:\n' + server.lines.slice(-40).join('\n'));
	}
	process.exit(failed ? 1 : 0);
}

main();
