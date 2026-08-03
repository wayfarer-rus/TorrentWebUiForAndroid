import assert from 'node:assert/strict';

export const REQUIRED_PORT_SCENARIOS = [
	'defaultStatus',
	'validSwitch',
	'sessionPreserved',
	'durableQueuePreserved',
	'invalidInputRollback',
	'occupiedPortRollback',
	'persistenceRollback',
	'coldStartUnavailable',
	'browserReconnect',
	'webSocketReconnect'
];

export function assertPortLifecycleCoverage(evidence) {
	assert(evidence.checks && typeof evidence.checks === 'object', 'Port checks must be recorded as observed booleans.');
	for (const scenario of REQUIRED_PORT_SCENARIOS) {
		assert(Object.hasOwn(evidence.checks, scenario), `Missing port scenario: ${scenario}`);
		assert.equal(evidence.checks[scenario], true, `Port scenario did not pass: ${scenario}`);
	}
}

export function assertPortTransition(evidence) {
	assert.equal(evidence.configuredPort, evidence.effectivePort, 'Configured and effective ports differ after the switch.');
	assert.equal(evidence.newListenerActive, true, 'The new listener is not active after the switch.');
	assert.equal(evidence.oldListenerActive, false, 'The old listener remains active after the switch.');
	assert.equal(evidence.queueIdentityPreserved, true, 'Durable queue identity changed during the port switch.');
	assert.equal(evidence.runtimeIdentityPreserved, true, 'Runtime torrent identity changed during the port switch.');
	assert.equal(evidence.nativeSessionInitializationCount, 1, 'The real APK initialized the native torrent session more than once during the port switch.');
}

const REQUIRED_TEARDOWN = [
	'configuredDefault',
	'defaultListenerAbsent',
	'switchedListenerAbsent',
	'occupiedListenerAbsent',
	'forwardsAbsent',
	'privateStateAbsent',
	'fixtureAbsent',
	'daemonStopped'
];

export function assertPortTeardown(evidence) {
	for (const name of REQUIRED_TEARDOWN) {
		assert(Object.hasOwn(evidence, name), `Port teardown evidence is missing required check: ${name}`);
		assert.equal(evidence[name], true, `Port teardown check failed: ${name}`);
	}
}
