import { createHash } from 'node:crypto';

const MAX_METAINFO_BYTES = 1024 * 1024;

export const OFFICIAL_TORRENT_SOURCES = Object.freeze([
	{
		label: 'Arch Linux',
		torrentUrl: 'https://archlinux.org/releng/releases/2026.07.01/torrent/'
	},
	{
		label: 'Debian',
		torrentUrl: 'https://cdimage.debian.org/cdimage/release/13.6.0/amd64/bt-cd/debian-13.6.0-amd64-netinst.iso.torrent'
	},
	{
		label: 'Ubuntu',
		torrentUrl: 'https://releases.ubuntu.com/24.04/ubuntu-24.04.4-desktop-amd64.iso.torrent'
	},
	{
		label: 'Fedora',
		torrentUrl: 'https://torrent.fedoraproject.org/torrents/Fedora-Workstation-Live-x86_64-44.torrent'
	}
]);

function fail(message) {
	throw new Error(`Invalid official torrent metainfo: ${message}`);
}

function readByteString(bytes, offset) {
	let cursor = offset;
	if (bytes[cursor] < 48 || bytes[cursor] > 57) fail('expected byte string');
	let length = 0;
	while (cursor < bytes.length && bytes[cursor] >= 48 && bytes[cursor] <= 57) {
		length = (length * 10) + bytes[cursor] - 48;
		cursor++;
	}
	if (bytes[cursor] !== 58) fail('unterminated byte-string length');
	const start = cursor + 1;
	const end = start + length;
	if (!Number.isSafeInteger(length) || end > bytes.length) fail('byte string exceeds document');
	return { start, end, next: end };
}

function skipValue(bytes, offset, depth = 0) {
	if (depth > 64 || offset >= bytes.length) fail('invalid nesting');
	const token = bytes[offset];
	if (token >= 48 && token <= 57) return readByteString(bytes, offset).next;
	if (token === 105) {
		const end = bytes.indexOf(101, offset + 1);
		if (end < 0) fail('unterminated integer');
		return end + 1;
	}
	if (token === 108) {
		let cursor = offset + 1;
		while (bytes[cursor] !== 101) cursor = skipValue(bytes, cursor, depth + 1);
		return cursor + 1;
	}
	if (token === 100) {
		let cursor = offset + 1;
		while (bytes[cursor] !== 101) {
			cursor = readByteString(bytes, cursor).next;
			cursor = skipValue(bytes, cursor, depth + 1);
		}
		return cursor + 1;
	}
	fail('unknown value token');
}

function dictionaryValue(bytes, dictionaryStart, keyName) {
	if (bytes[dictionaryStart] !== 100) fail('expected dictionary');
	let cursor = dictionaryStart + 1;
	while (bytes[cursor] !== 101) {
		const key = readByteString(bytes, cursor);
		cursor = key.next;
		const valueStart = cursor;
		const valueEnd = skipValue(bytes, valueStart);
		if (Buffer.from(bytes.subarray(key.start, key.end)).toString('utf8') === keyName) {
			return { start: valueStart, end: valueEnd };
		}
		cursor = valueEnd;
	}
	return null;
}

export function magnetFromMetainfo(input) {
	const bytes = Buffer.from(input);
	const info = dictionaryValue(bytes, 0, 'info');
	if (!info || bytes[info.start] !== 100) fail('missing info dictionary');
	const utf8Name = dictionaryValue(bytes, info.start, 'name.utf-8');
	const legacyName = dictionaryValue(bytes, info.start, 'name');
	const nameValue = utf8Name ?? legacyName;
	if (!nameValue || bytes[nameValue.start] < 48 || bytes[nameValue.start] > 57) fail('missing torrent name');
	const nameBytes = readByteString(bytes, nameValue.start);
	const name = Buffer.from(bytes.subarray(nameBytes.start, nameBytes.end)).toString('utf8');
	if (!name) fail('empty torrent name');
	const infoHash = createHash('sha1').update(bytes.subarray(info.start, info.end)).digest('hex');
	return { name, magnet: `magnet:?xt=urn:btih:${infoHash}&dn=${encodeURIComponent(name)}` };
}

async function fetchBounded(source) {
	const response = await fetch(source.torrentUrl, {
		headers: { 'User-Agent': 'TorrentWebUiForAndroid-M4-acceptance/1.0' },
		signal: AbortSignal.timeout(15_000)
	});
	if (!response.ok) throw new Error(`${source.label} metainfo fetch failed with HTTP ${response.status}.`);
	const requested = new URL(source.torrentUrl);
	const resolved = new URL(response.url);
	if (resolved.protocol !== 'https:' || resolved.hostname !== requested.hostname) {
		throw new Error(`${source.label} metainfo redirected outside its official HTTPS host.`);
	}
	const declaredLength = Number(response.headers.get('content-length') ?? 0);
	if (declaredLength > MAX_METAINFO_BYTES) throw new Error(`${source.label} metainfo exceeds the size limit.`);
	if (!response.body) throw new Error(`${source.label} metainfo response had no body.`);

	const chunks = [];
	const reader = response.body.getReader();
	let total = 0;
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		total += value.byteLength;
		if (total > MAX_METAINFO_BYTES) {
			await reader.cancel();
			throw new Error(`${source.label} metainfo exceeds the size limit.`);
		}
		chunks.push(Buffer.from(value));
	}
	return Buffer.concat(chunks, total);
}

export async function loadOfficialTorrents() {
	const settled = await Promise.allSettled(OFFICIAL_TORRENT_SOURCES.map(async (source) => {
		const metainfo = await fetchBounded(source);
		return { ...source, ...magnetFromMetainfo(metainfo) };
	}));
	const failures = settled.filter((result) => result.status === 'rejected');
	if (failures.length) {
		throw new AggregateError(failures.map((result) => result.reason), 'Official torrent metadata matrix could not be loaded.');
	}
	return settled.map((result) => result.value);
}
