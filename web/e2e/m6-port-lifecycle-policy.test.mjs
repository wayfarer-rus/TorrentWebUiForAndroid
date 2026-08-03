import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import {
	assertPortLifecycleCoverage,
	assertPortTransition,
	assertPortTeardown,
	REQUIRED_PORT_SCENARIOS
} from './m6-port-lifecycle-policy.mjs';

test('port lifecycle evidence requires every atomicity and reconnection scenario', () => {
	const checks = Object.fromEntries(REQUIRED_PORT_SCENARIOS.map((scenario) => [scenario, true]));
	assert.doesNotThrow(() => assertPortLifecycleCoverage({ checks }));
	const { defaultStatus: _missing, ...incomplete } = checks;
	assert.throws(() => assertPortLifecycleCoverage({ checks: incomplete }), /missing port scenario/i);
	assert.throws(() => assertPortLifecycleCoverage({ checks: { ...checks, validSwitch: false } }), /did not pass/i);
});

test('successful transition requires the new listener and retires the old listener', () => {
	const complete = {
		configuredPort: 18084,
		effectivePort: 18084,
		oldListenerActive: false,
		newListenerActive: true,
		queueIdentityPreserved: true,
		runtimeIdentityPreserved: true,
		nativeSessionInitializationCount: 1
	};
	assert.doesNotThrow(() => assertPortTransition(complete));
	assert.throws(() => assertPortTransition({ ...complete, oldListenerActive: true }), /old listener/i);
	assert.throws(() => assertPortTransition({ ...complete, queueIdentityPreserved: false }), /queue identity/i);
	assert.throws(() => assertPortTransition({ ...complete, nativeSessionInitializationCount: 2 }), /native torrent session/i);
});

test('teardown fails closed unless the default is restored and every listener/fixture is absent', () => {
	const complete = {
		configuredDefault: true,
		defaultListenerAbsent: true,
		switchedListenerAbsent: true,
		occupiedListenerAbsent: true,
		forwardsAbsent: true,
		privateStateAbsent: true,
		fixtureAbsent: true,
		daemonStopped: true
	};
	assert.doesNotThrow(() => assertPortTeardown(complete));
	assert.throws(() => assertPortTeardown({ ...complete, configuredDefault: false }), /configuredDefault/);
	const { fixtureAbsent: _missing, ...incomplete } = complete;
	assert.throws(() => assertPortTeardown(incomplete), /fixtureAbsent/);
});

test('real-APK port runner is bounded and contains no shell permission grants', async () => {
	const runner = await readFile(fileURLToPath(new URL('./m6-port-lifecycle-runner.mjs', import.meta.url)), 'utf8');
	assert.match(runner, /AbortSignal\.timeout\(/);
	assert.match(runner, /assertPortLifecycleCoverage\(/);
	const forbidden = [
		new RegExp(['pm', 'grant'].join('\\s+'), 'i'),
		new RegExp(['app', 'ops'].join('\\s*'), 'i')
	];
	assert(forbidden.every((pattern) => !pattern.test(runner)), 'runner grants Android permission from the shell');
});
