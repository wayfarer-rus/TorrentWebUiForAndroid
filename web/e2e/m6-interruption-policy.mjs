import assert from 'node:assert/strict';

export const REQUIRED_RESUME_POINTS = [
	'destination:refresh',
	'destination:browser-close',
	'destination:process-restart',
	'password:refresh',
	'password:browser-close',
	'password:process-restart',
	'reauthentication:browser-close'
];

export const REQUIRED_MIGRATION_EVIDENCE = [
	'durableQueue',
	'approvedDestination',
	'nonDefaultPassword',
	'incompleteMarkerPrecedence'
];

export const REQUIRED_RECOVERY_STATES = [
	'permissionLoss',
	'unavailableStorage',
	'lastDestinationRemoval',
	'daemonFailure'
];

function assertIncludesAll(observed, required, description) {
	assert(Array.isArray(observed), `${description} must be recorded as an array.`);
	for (const value of required) {
		assert(observed.includes(value), `Missing ${description}: ${value}`);
	}
}

export function assertResumeAndRecoveryCoverage(evidence) {
	assertIncludesAll(evidence.resumePoints, REQUIRED_RESUME_POINTS, 'resume point');
	assertIncludesAll(evidence.recoveryStates, REQUIRED_RECOVERY_STATES, 'recovery state');
}

export function assertMigrationCoverage(evidence) {
	assertIncludesAll(evidence.migrationEvidence, REQUIRED_MIGRATION_EVIDENCE, 'migration evidence');
}

export function assertInterruptionCoverage(evidence) {
	assertResumeAndRecoveryCoverage(evidence);
	assertMigrationCoverage(evidence);
}

export function assertCredentialTransition(statuses) {
	assert.equal(statuses.staleAfterChange, 401, 'The stale credential remained valid after onboarding Password change.');
	assert.equal(statuses.newAfterChange, 200, 'The changed credential did not authenticate after onboarding.');
	assert.equal(statuses.changedAfterReset, 401, 'The changed credential remained valid after Android Password Reset.');
	assert.equal(statuses.defaultAfterReset, 200, 'The reset default credential did not authenticate.');
}

export function assertPasswordResetDialog(hierarchy) {
	assert(hierarchy.includes('Reset WebUI Password?'), 'Password Reset confirmation dialog is absent.');
	assert(hierarchy.includes('Reset Password'), 'Password Reset confirmation action is absent.');
	assert(!hierarchy.includes('Current Password'), 'Password Reset requests the current Password.');
	assert(!hierarchy.includes('New Password'), 'Password Reset exposes a password-entry field.');
	assert(!hierarchy.includes('Replacement Password'), 'Password Reset exposes a password-entry field.');
}

export function collectSensitiveAuditValues(categories) {
	const ordered = [];
	for (const name of ['credentials', 'authorizationHeaders', 'magnets', 'privateTrackers', 'destinationPaths']) {
		const values = categories[name];
		assert(Array.isArray(values) && values.length > 0, `Sensitive log audit requires ${name}.`);
		for (const value of values) {
			assert.equal(typeof value, 'string', `Sensitive log-audit ${name} values must be strings.`);
			assert(value.length > 0, `Sensitive log-audit ${name} values must be non-empty.`);
			if (!ordered.includes(value)) ordered.push(value);
		}
	}
	return ordered;
}
