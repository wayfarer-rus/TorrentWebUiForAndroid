import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createTcpServer } from 'node:net';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PIECE_LENGTH = 256 * 1024;
const METADATA_PIECE_LENGTH = 16 * 1024;
const PEER_ID = Buffer.from('-M4E2E0-OWNEDSEED001');

function bencode(value) {
	if (Buffer.isBuffer(value)) return Buffer.concat([Buffer.from(`${value.length}:`), value]);
	if (typeof value === 'string') return bencode(Buffer.from(value));
	if (Number.isInteger(value)) return Buffer.from(`i${value}e`);
	if (Array.isArray(value)) return Buffer.concat([Buffer.from('l'), ...value.map(bencode), Buffer.from('e')]);
	const entries = Object.entries(value).sort(([left], [right]) => Buffer.from(left).compare(Buffer.from(right)));
	return Buffer.concat([Buffer.from('d'), ...entries.flatMap(([key, item]) => [bencode(key), bencode(item)]), Buffer.from('e')]);
}

function buildTorrent(name, payload) {
	const pieces = [];
	for (let offset = 0; offset < payload.length; offset += PIECE_LENGTH) {
		pieces.push(createHash('sha1').update(payload.subarray(offset, offset + PIECE_LENGTH)).digest());
	}
	const info = { length: payload.length, name, 'piece length': PIECE_LENGTH, pieces: Buffer.concat(pieces) };
	const infoBytes = bencode(info);
	return {
		bytes: bencode({ 'created by': 'TorrentWebUi M4 fixture v1', info }),
		infoBytes,
		infoHash: createHash('sha1').update(infoBytes).digest('hex'),
		pieceCount: pieces.length
	};
}

function listen(server) {
	return new Promise((resolve, reject) => {
		server.once('error', reject);
		server.listen(0, '0.0.0.0', () => resolve(server.address()));
	});
}

function close(server) {
	return new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

function wireMessage(id, payload = Buffer.alloc(0)) {
	const length = Buffer.alloc(4);
	length.writeUInt32BE(1 + payload.length);
	return Buffer.concat([length, Buffer.from([id]), payload]);
}

function compactPeer(port) {
	const peer = Buffer.from([10, 0, 2, 2, 0, 0]);
	peer.writeUInt16BE(port, 4);
	return peer;
}

function createSeeder(fixturesByHash, blockedPayloads) {
	return createTcpServer((socket) => {
		console.log('M4 fixture peer connected.');
		let buffered = Buffer.alloc(0);
		let fixture = null;
		let handshakeComplete = false;
		let remoteMetadataId = null;
		let loggedMessages = 0;
		socket.on('close', () => console.log('M4 fixture peer disconnected.'));
		socket.on('error', (error) => console.log(`M4 fixture peer error: ${error.message}`));

		socket.on('data', (chunk) => {
			buffered = Buffer.concat([buffered, chunk]);
			if (!handshakeComplete) {
				if (buffered.length < 68) return;
				if (buffered[0] !== 19 || buffered.subarray(1, 20).toString() !== 'BitTorrent protocol') return socket.destroy();
				const infoHash = buffered.subarray(28, 48);
				fixture = fixturesByHash.get(infoHash.toString('hex'));
				if (!fixture) return socket.destroy();
				console.log(`M4 fixture handshake accepted for ${fixture.name}.`);
				const reserved = Buffer.alloc(8);
				reserved[5] = 0x10;
				socket.write(Buffer.concat([Buffer.from([19]), Buffer.from('BitTorrent protocol'), reserved, infoHash, PEER_ID]));
				socket.write(wireMessage(1));
				const bitfield = Buffer.alloc(Math.ceil(fixture.pieceCount / 8), 0xff);
				const remainder = fixture.pieceCount % 8;
				if (remainder) bitfield[bitfield.length - 1] = (0xff << (8 - remainder)) & 0xff;
				socket.write(wireMessage(5, bitfield));
				const extensionHandshake = bencode({ m: { ut_metadata: 1 }, metadata_size: fixture.infoBytes.length });
				socket.write(wireMessage(20, Buffer.concat([Buffer.from([0]), extensionHandshake])));
				buffered = buffered.subarray(68);
				handshakeComplete = true;
			}

			while (buffered.length >= 4) {
				const length = buffered.readUInt32BE(0);
				if (buffered.length < 4 + length) break;
				const message = buffered.subarray(4, 4 + length);
				buffered = buffered.subarray(4 + length);
				if (length === 0) continue;
				if (loggedMessages++ < 20) console.log(`M4 fixture peer message id=${message[0]} extension=${message[1] ?? '-'} length=${length}.`);
				if (message[0] === 20 && message[1] === 0) {
					const extensionHandshake = message.subarray(2).toString();
					remoteMetadataId = Number(extensionHandshake.match(/11:ut_metadatai(\d+)e/)?.[1]);
				} else if (message[0] === 20 && message[1] === 1) {
					const request = message.subarray(2).toString();
					const piece = Number(request.match(/5:piecei(\d+)e/)?.[1]);
					if (!Number.isInteger(piece)) continue;
					const metadata = fixture.infoBytes.subarray(piece * METADATA_PIECE_LENGTH, (piece + 1) * METADATA_PIECE_LENGTH);
					const header = bencode({ msg_type: 1, piece, total_size: fixture.infoBytes.length });
					if (Number.isInteger(remoteMetadataId)) {
						socket.write(wireMessage(20, Buffer.concat([Buffer.from([remoteMetadataId]), header, metadata])));
					}
				} else if (message[0] === 6 && message.length >= 13) {
					if (blockedPayloads.has(fixture.name)) continue;
					const index = message.readUInt32BE(1);
					const begin = message.readUInt32BE(5);
					const requestedLength = message.readUInt32BE(9);
					const offset = index * PIECE_LENGTH + begin;
					const block = fixture.payload.subarray(offset, offset + requestedLength);
					const header = Buffer.alloc(8);
					header.writeUInt32BE(index, 0);
					header.writeUInt32BE(begin, 4);
					socket.write(wireMessage(7, Buffer.concat([header, block])));
				}
			}
		});
	});
}

export async function startOwnedFixtureController(adbArgs) {
	const hostRoot = await mkdtemp(join(tmpdir(), 'torrent-webui-m4-'));
	const definitions = [
		['m4-completed-fixture.bin', 2 * 1024 * 1024, 0x41],
		['m4-reused-destination-fixture.bin', 1024 * 1024, 0x52],
		['m4-partial-verification-fixture.bin', 64 * 1024 * 1024, 0x50],
		['m4-add-valid-fixture.bin', 1024 * 1024, 0x56],
		['m4-add-corrupt-fixture.bin', 1024 * 1024, 0x43],
		['m4-add-unrelated-fixture.bin', 1024 * 1024, 0x55],
		['m4-interrupted-fixture.bin', 128 * 1024 * 1024, 0x49]
	];
	const fixtures = new Map();
	const fixturesByHash = new Map();

	for (const [name, size, fill] of definitions) {
		const payload = Buffer.alloc(size, fill);
		const torrent = buildTorrent(name, payload);
		const payloadPath = join(hostRoot, name);
		const torrentPath = `${payloadPath}.torrent`;
		await writeFile(payloadPath, payload);
		await writeFile(torrentPath, torrent.bytes);
		let partialPayloadPath = null;
		if (name === 'm4-partial-verification-fixture.bin') {
			const partial = Buffer.concat([
				payload.subarray(0, size / 2),
				Buffer.alloc(size / 2)
			]);
			partialPayloadPath = `${payloadPath}.partial`;
			await writeFile(partialPayloadPath, partial);
		}
		const fixture = { name, size, payload, payloadPath, partialPayloadPath, torrentPath, ...torrent };
		fixtures.set(name, fixture);
		fixturesByHash.set(torrent.infoHash, fixture);
	}

	const blockedPayloads = new Set();
	const seeder = createSeeder(fixturesByHash, blockedPayloads);
	const peerAddress = await listen(seeder);
	assert(peerAddress && typeof peerAddress === 'object');

	const httpServer = createHttpServer(async (request, response) => {
		try {
			const url = new URL(request.url, 'http://fixture.invalid');
			if (url.pathname === '/control/block' || url.pathname === '/control/unblock') {
				const name = url.searchParams.get('name');
				if (!fixtures.has(name)) return response.writeHead(404).end();
				if (url.pathname.endsWith('/block')) blockedPayloads.add(name);
				else blockedPayloads.delete(name);
				response.writeHead(204).end();
				return;
			}
			if (url.pathname === '/announce') {
				console.log('M4 fixture tracker announce received.');
				const body = bencode({ complete: 1, incomplete: 0, interval: 30, peers: compactPeer(peerAddress.port) });
				response.writeHead(200, { 'Content-Type': 'text/plain', 'Content-Length': body.length });
				response.end(body);
				return;
			}
			const requestedName = decodeURIComponent(url.pathname.slice(1));
			const fixture = [...fixtures.values()].find((candidate) => `${candidate.name}.torrent` === requestedName);
			if (!fixture) return response.writeHead(404).end();
			const body = await readFile(fixture.torrentPath);
			response.writeHead(200, { 'Content-Type': 'application/x-bittorrent', 'Content-Length': body.length });
			response.end(body);
		} catch (error) {
			response.writeHead(500).end(String(error));
		}
	});
	const httpAddress = await listen(httpServer);
	assert(httpAddress && typeof httpAddress === 'object');
	const hostBaseUrl = `http://127.0.0.1:${httpAddress.port}`;
	const emulatorBaseUrl = `http://10.0.2.2:${httpAddress.port}`;
	const tracker = `${emulatorBaseUrl}/announce`;

	for (const fixture of fixtures.values()) {
		const response = await fetch(`${hostBaseUrl}/${encodeURIComponent(`${fixture.name}.torrent`)}`);
		assert(response.ok, `Owned fixture server was not ready for ${fixture.name}.`);
		fixture.magnet = `magnet:?xt=urn:btih:${fixture.infoHash}&dn=${encodeURIComponent(fixture.name)}&tr=${encodeURIComponent(tracker)}&x.pe=10.0.2.2:${peerAddress.port}`;
	}

	return {
		completed: fixtures.get('m4-completed-fixture.bin'),
		reuse: fixtures.get('m4-reused-destination-fixture.bin'),
		partial: fixtures.get('m4-partial-verification-fixture.bin'),
		addValid: fixtures.get('m4-add-valid-fixture.bin'),
		addCorrupt: fixtures.get('m4-add-corrupt-fixture.bin'),
		addUnrelated: fixtures.get('m4-add-unrelated-fixture.bin'),
		interrupted: fixtures.get('m4-interrupted-fixture.bin'),
		controlBaseUrl: hostBaseUrl,
		async provision(fixture, destination) {
			adbArgs('push', fixture.payloadPath, `${destination}/${fixture.name}`);
			const observedSize = Number(adbArgs('shell', 'stat', '-c', '%s', `${destination}/${fixture.name}`));
			assert.equal(observedSize, fixture.size, `Device fixture payload verification failed for ${fixture.name}.`);
		},
		async provisionPartial(fixture, destination) {
			assert(fixture.partialPayloadPath, `No partial payload exists for ${fixture.name}.`);
			adbArgs('push', fixture.partialPayloadPath, `${destination}/${fixture.name}`);
			const observedSize = Number(adbArgs('shell', 'stat', '-c', '%s', `${destination}/${fixture.name}`));
			assert.equal(observedSize, fixture.size, `Device partial fixture size failed for ${fixture.name}.`);
		},
		async stop() {
			const failures = [];
			try { await close(httpServer); } catch (error) { failures.push(error); }
			try { await close(seeder); } catch (error) { failures.push(error); }
			try { await rm(hostRoot, { recursive: true, force: true }); } catch (error) { failures.push(error); }
			if (failures.length) throw new AggregateError(failures, 'Owned fixture controller cleanup failed.');
		}
	};
}
