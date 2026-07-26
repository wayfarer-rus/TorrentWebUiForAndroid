import assert from 'node:assert/strict';

export const MAX_SETUP_DURATION_MS = 180_000;

export function assertWithinSetupBudget(startedAtMs, completedAtMs) {
	const durationMs = completedAtMs - startedAtMs;
	assert(durationMs >= 0, 'Setup duration cannot be negative.');
	assert(durationMs < MAX_SETUP_DURATION_MS, 'Normal setup must complete under three minutes.');
	return durationMs;
}

export function assertNoDefaultPasswordInRenderedContent(renderedText) {
	assert(!renderedText.includes('start123'), 'Rendered content exposed the default Password.');
}

export function assertHostForwardAvailable(forwardList, serial, hostPort) {
	const local = `tcp:${hostPort}`;
	const occupied = forwardList.split('\n').some((line) => line.trim().split(/\s+/)[1] === local);
	assert(!occupied, `ADB host forwarding port ${hostPort} is already owned; refusing to rebind for ${serial}.`);
}

export function assertSensitiveValuesAbsent(appProcessLogs, sensitiveValues) {
	for (const value of sensitiveValues) {
		assert(value.length > 0, 'Sensitive log-audit values must be non-empty.');
		assert(!appProcessLogs.includes(value), 'App-process logs contain a sensitive value.');
	}
}

export function assertCanonicalPathMatch(backendPath, adbObservedPath) {
	assert.equal(backendPath, adbObservedPath, 'Backend and ADB canonical path evidence differ.');
	return backendPath;
}

const REQUIRED_TEARDOWN_CHECKS = [
	'daemonStopped',
	'listenerAbsent',
	'forwardAbsent',
	'fixtureAbsent',
	'privateStateAbsent',
	'virtualStorageDisabled'
];

export function assertTeardownComplete(evidence) {
	for (const name of REQUIRED_TEARDOWN_CHECKS) {
		assert(Object.hasOwn(evidence, name), `Teardown evidence is missing required check: ${name}`);
		assert.equal(evidence[name], true, `Teardown check failed: ${name}`);
	}
}
