// A raw RaspberryJuice socket client. call() expects exactly one reply line; send() is for
// fire-and-forget commands. Any reply that arrives with no call waiting is kept as "stray" so a
// test can assert that a silent command really stayed silent.
import net from 'node:net';

export class RjSocket {
	static async connect(port, replyTimeoutMs) {
		const sock = new RjSocket(port, replyTimeoutMs);
		await new Promise((resolve, reject) => {
			sock.socket.once('connect', resolve);
			sock.socket.once('error', reject);
		});
		return sock;
	}

	constructor(port, replyTimeoutMs) {
		this.replyTimeoutMs = replyTimeoutMs;
		this.pending = [];
		this.stray = [];
		this.closed = false;
		this.socket = net.createConnection({ host: '127.0.0.1', port });
		this.socket.setEncoding('utf8');
		let partial = '';
		this.socket.on('data', (chunk) => {
			const parts = (partial + chunk).split('\n');
			partial = parts.pop();
			parts.forEach((line) => this.#onReply(line));
		});
		this.socket.on('close', () => {
			this.closed = true;
			this.pending.splice(0).forEach((p) => p.resolve(null));
		});
		this.socket.on('error', () => { });
	}

	#onReply(line) {
		const waiter = this.pending.shift();
		if (waiter) waiter.resolve(line);
		else this.stray.push(line);
	}

	send(line) {
		this.socket.write(line + '\n');
	}

	/** Sends a request-response command. Resolves with the reply, or null if the socket closed. */
	call(line) {
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => reject(new Error(`no reply to ${line} within ${this.replyTimeoutMs}ms`)),
				this.replyTimeoutMs);
			this.pending.push({ resolve: (reply) => { clearTimeout(timer); resolve(reply); } });
			this.send(line);
		});
	}

	/** Resolves once the server has processed everything sent so far (one round trip). */
	async sync() {
		await this.call('world.getTime()');
	}

	close() {
		this.socket.end();
	}
}
