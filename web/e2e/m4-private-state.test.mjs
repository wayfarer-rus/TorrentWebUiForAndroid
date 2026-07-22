import assert from 'node:assert/strict';
import test from 'node:test';
import { catalogStateOwnedByM4 } from './m4-private-state.mjs';

test('accepts empty or wholly M4-owned catalog state', () => {
	assert.equal(catalogStateOwnedByM4(null), true);
	assert.equal(catalogStateOwnedByM4('@latest|'), true);
	assert.equal(
		catalogStateOwnedByM4('@latest|/storage/TorrentWebUi-M4-1/destination\n/storage/TorrentWebUi-M4-1/destination|1'),
		true
	);
});

test('rejects a non-M4 AtomicFile backup entry', () => {
	assert.equal(
		catalogStateOwnedByM4('@latest|/storage/TorrentWebUi-M4-1/destination\n/storage/household-downloads|1'),
		false
	);
});
