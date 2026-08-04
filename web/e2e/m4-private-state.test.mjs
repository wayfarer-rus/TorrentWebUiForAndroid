import assert from 'node:assert/strict';
import test from 'node:test';
import {
	CATALOG_STATE_PATHS,
	catalogStateOwnedByM4,
	catalogStatesOwnedByM4,
	onboardingStateOwnedByM4
} from './m4-private-state.mjs';
import { COMPLETED_ONBOARDING_RECORD } from './onboarding-private-state-fixture.mjs';

test('accepts empty or wholly M4-owned catalog state', () => {
	assert.equal(catalogStateOwnedByM4(null), true);
	assert.equal(catalogStateOwnedByM4('@latest|'), true);
	assert.equal(
		catalogStateOwnedByM4('@latest|/storage/TorrentWebUi-M4-1/destination\n/storage/TorrentWebUi-M4-1/destination|1'),
		true
	);
});

test('covers every AtomicFile state path', () => {
	assert.deepEqual(CATALOG_STATE_PATHS, [
		'files/destination_catalog.txt',
		'files/destination_catalog.txt.bak',
		'files/destination_catalog.txt.new'
	]);
});

test('accepts only the exact owned M4 onboarding record', () => {
	assert.equal(onboardingStateOwnedByM4(null), true);
	assert.equal(onboardingStateOwnedByM4(COMPLETED_ONBOARDING_RECORD), true);
	assert.equal(onboardingStateOwnedByM4(COMPLETED_ONBOARDING_RECORD.trim()), true);
	assert.equal(onboardingStateOwnedByM4(COMPLETED_ONBOARDING_RECORD.replace('</map>', '<string name="extra">user</string>\n</map>')), false);
	assert.equal(onboardingStateOwnedByM4(COMPLETED_ONBOARDING_RECORD.replace('value="true"', 'value="false"')), false);
});

test('rejects a non-M4 AtomicFile new or backup state', () => {
	const owned = '@latest|/storage/TorrentWebUi-M4-1/destination';
	const nonOwned = '@latest|/storage/household-downloads';

	assert.equal(catalogStatesOwnedByM4([owned, owned, nonOwned]), false);
	assert.equal(catalogStatesOwnedByM4([owned, nonOwned, owned]), false);
	assert.equal(catalogStatesOwnedByM4([owned, owned, owned]), true);
});
