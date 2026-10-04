// Boots the Paper server in e2e/.server with its console on stdin, so the harness can run
// operator commands and read every log line. Each run starts from a fresh flat world.
import { spawn } from 'node:child_process';
import { rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const STOP_GRACE_MS = 30_000;

export class PaperServer {
	constructor(dir, { mcPort, rjPort }) {
		this.dir = dir;
		this.mcPort = mcPort;
		this.rjPort = rjPort;
		this.lines = [];
		this.waiters = [];
		this.proc = null;
	}

	/** Writes server.properties and the plugin config, wipes old worlds, then starts Paper. */
	async start(pluginConfig, readyTimeoutMs) {
		for (const world of ['world', 'world_nether', 'world_the_end']) {
			rmSync(join(this.dir, world), { recursive: true, force: true });
		}
		// No operators: a leftover op would hold the teacher permission and bypass the plot listener.
		writeFileSync(join(this.dir, 'ops.json'), '[]\n');
		writeFileSync(join(this.dir, 'eula.txt'), 'eula=true\n');
		writeFileSync(join(this.dir, 'server.properties'), [
			'online-mode=false', 'level-type=minecraft\\:flat', 'difficulty=peaceful',
			'spawn-protection=0', 'spawn-monsters=false', 'spawn-animals=false', 'spawn-npcs=false',
			'view-distance=4', 'simulation-distance=4', `server-port=${this.mcPort}`,
			'max-players=4', 'motd=RaspberryJuice e2e',
		].join('\n') + '\n');
		const pluginDir = join(this.dir, 'plugins', 'RaspberryJuice');
		mkdirSync(pluginDir, { recursive: true });
		writeFileSync(join(pluginDir, 'config.yml'), pluginConfig);

		this.proc = spawn('java', ['-Xms1G', '-Xmx2G', '-jar', 'paper.jar', '--nogui'], {
			cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'],
		});
		this.exited = new Promise((resolve) => {
			this.proc.on('exit', resolve);
			this.proc.on('error', (err) => { this.spawnError = err; resolve(null); });
		});
		this.proc.stdin.on('error', () => { }); // a command written after Paper died is not a crash
		// A dead server fails every pending wait at once instead of after its full timeout.
		this.exited.then((code) => {
			const reason = this.spawnError ? `could not start java: ${this.spawnError.message}` : `Paper exited (code ${code})`;
			this.waiters.splice(0).forEach((w) => w.reject(new Error(reason)));
		});
		for (const stream of [this.proc.stdout, this.proc.stderr]) {
			let partial = '';
			stream.setEncoding('utf8');
			stream.on('data', (chunk) => {
				const parts = (partial + chunk).split('\n');
				partial = parts.pop();
				parts.forEach((line) => this.#onLine(line));
			});
		}
		await this.waitFor(/Done \([\d.]+s\)!/, readyTimeoutMs);
	}

	#onLine(line) {
		this.lines.push(line);
		if (process.env.E2E_VERBOSE) console.log(`  [server] ${line}`);
		this.waiters = this.waiters.filter((w) => !(w.pattern.test(line) && (w.resolve(line), true)));
	}

	/** Resolves with the first log line (already seen at or after {@code fromIndex}) matching the pattern. */
	waitFor(pattern, timeoutMs, fromIndex = 0) {
		const seen = this.lines.slice(fromIndex).find((line) => pattern.test(line));
		if (seen) return Promise.resolve(seen);
		if (this.spawnError || (this.proc && this.proc.exitCode !== null)) {
			return Promise.reject(new Error('Paper is not running'));
		}
		return new Promise((resolve, reject) => {
			const waiter = {
				pattern,
				resolve: (line) => { clearTimeout(timer); resolve(line); },
				reject: (err) => { clearTimeout(timer); reject(err); },
			};
			const timer = setTimeout(() => {
				this.waiters = this.waiters.filter((w) => w !== waiter);
				reject(new Error(`timed out after ${timeoutMs}ms waiting for log ${pattern}`));
			}, timeoutMs);
			this.waiters.push(waiter);
		});
	}

	/** Runs a console command and returns the log index from before it, for waitFor. */
	command(cmd) {
		const mark = this.lines.length;
		this.proc.stdin.write(cmd + '\n');
		return mark;
	}

	logSince(index) {
		return this.lines.slice(index);
	}

	kill() {
		if (this.proc && this.proc.exitCode === null) this.proc.kill('SIGKILL');
	}

	async stop() {
		if (!this.proc || this.proc.exitCode !== null) return;
		this.command('stop');
		const timer = setTimeout(() => this.proc.kill('SIGKILL'), STOP_GRACE_MS);
		await this.exited;
		clearTimeout(timer);
	}
}
