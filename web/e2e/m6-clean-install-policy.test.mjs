import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import {
	assertCanonicalPathMatch,
	assertHostForwardAvailable,
	assertNoDefaultPasswordInRenderedContent,
	assertSensitiveValuesAbsent,
	assertTeardownComplete,
	assertWithinSetupBudget
} from './m6-clean-install-policy.mjs';

test('setup timing must remain strictly below three minutes', () => {
	assert.equal(assertWithinSetupBudget(10_000, 189_999), 179_999);
	assert.throws(() => assertWithinSetupBudget(0, 180_000), /under three minutes/);
});

test('rendered content check rejects the default credential', () => {
	assert.doesNotThrow(() => assertNoDefaultPasswordInRenderedContent('Consumer Onboarding'));
	assert.throws(
		() => assertNoDefaultPasswordInRenderedContent('Password: start123'),
		/default Password/
	);
});

test('canonical path evidence must match exactly', () => {
	assert.equal(
		assertCanonicalPathMatch(
			'/storage/emulated/0/Download/Torrents',
			'/storage/emulated/0/Download/Torrents'
		),
		'/storage/emulated/0/Download/Torrents'
	);
	assert.throws(
		() => assertCanonicalPathMatch('/storage/emulated/0/Download/Torrents', '/sdcard/Download/Torrents'),
		/canonical path/
	);
});

test('acceptance sources contain no shell permission grants', async () => {
	const forbidden = [
		new RegExp(['pm', 'grant'].join('\\s+'), 'i'),
		new RegExp(['app', 'ops'].join('\\s*'), 'i')
	];
	for (const relative of ['./m6-clean-install-runner.mjs', './m6-real-apk-tools.mjs', './m4-android-ui.mjs']) {
		const source = await readFile(fileURLToPath(new URL(relative, import.meta.url)), 'utf8');
		assert(forbidden.every((pattern) => !pattern.test(source)), `${relative} grants permission from the shell`);
	}
});

test('host forwarding fails closed when the requested port is already mapped', () => {
	assert.doesNotThrow(() => assertHostForwardAvailable('', 'emulator-5554', '18082'));
	assert.throws(
		() => assertHostForwardAvailable('emulator-5554 tcp:18082 tcp:8080', 'emulator-5554', '18082'),
		/host forwarding port/
	);
});

test('app-process log audit checks every supplied sensitive value', () => {
	assert.doesNotThrow(() => assertSensitiveValuesAbsent('ordinary app output', ['credential', '/private/path']));
	assert.throws(() => assertSensitiveValuesAbsent('native /private/path output', ['credential', '/private/path']), /sensitive value/);
});

test('teardown evidence fails closed when any owned resource remains or field is missing', () => {
	const complete = {
		daemonStopped: true,
		listenerAbsent: true,
		forwardAbsent: true,
		fixtureAbsent: true,
		privateStateAbsent: true,
		virtualStorageDisabled: true
	};
	assert.doesNotThrow(() => assertTeardownComplete(complete));
	assert.throws(() => assertTeardownComplete({ ...complete, listenerAbsent: false }), /listenerAbsent/);
	const { forwardAbsent: _omitted, ...incomplete } = complete;
	assert.throws(() => assertTeardownComplete(incomplete), /forwardAbsent/);
});
