import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { magnetFromMetainfo, OFFICIAL_TORRENT_SOURCES } from './m4-official-torrents.mjs';

test('hashes the exact raw info dictionary and keeps the metainfo name', () => {
	const info = Buffer.from('d6:lengthi42e4:name11:example.isoe');
	const metainfo = Buffer.concat([
		Buffer.from('d8:announce24:https://tracker.invalid/4:info'),
		info,
		Buffer.from('e')
	]);
	const expectedHash = createHash('sha1').update(info).digest('hex');

	assert.deepEqual(magnetFromMetainfo(metainfo), {
		name: 'example.iso',
		magnet: `magnet:?xt=urn:btih:${expectedHash}&dn=example.iso`
	});
});

test('official matrix is HTTPS-only and contains the required distributions', () => {
	assert.deepEqual(
		OFFICIAL_TORRENT_SOURCES.map((source) => source.label).sort(),
		['Arch Linux', 'Debian', 'Fedora', 'Ubuntu']
	);
	for (const source of OFFICIAL_TORRENT_SOURCES) {
		assert.equal(new URL(source.torrentUrl).protocol, 'https:');
	}
});
