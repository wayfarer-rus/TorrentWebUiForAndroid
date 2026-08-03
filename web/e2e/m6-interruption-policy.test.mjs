import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import {
	assertCredentialTransition,
	assertInterruptionCoverage,
	assertPasswordResetDialog,
	collectSensitiveAuditValues,
	REQUIRED_MIGRATION_EVIDENCE,
	REQUIRED_RECOVERY_STATES,
	REQUIRED_RESUME_POINTS
} from './m6-interruption-policy.mjs';

test('interruption evidence must cover every unresolved step and recovery state', () => {
	const complete = {
		resumePoints: [...REQUIRED_RESUME_POINTS],
		migrationEvidence: [...REQUIRED_MIGRATION_EVIDENCE],
		recoveryStates: [...REQUIRED_RECOVERY_STATES]
	};
	assert.doesNotThrow(() => assertInterruptionCoverage(complete));
	assert.throws(
		() => assertInterruptionCoverage({ ...complete, resumePoints: complete.resumePoints.slice(1) }),
		/resume point/
	);
	assert.throws(
		() => assertInterruptionCoverage({ ...complete, migrationEvidence: ['durableQueue'] }),
		/migration evidence/
	);
	assert.throws(
		() => assertInterruptionCoverage({ ...complete, recoveryStates: [] }),
		/recovery state/
	);
});

test('credential handoff requires stale rejection, new acceptance, and reset reversal', () => {
	assert.doesNotThrow(() => assertCredentialTransition({
		staleAfterChange: 401,
		newAfterChange: 200,
		changedAfterReset: 401,
		defaultAfterReset: 200
	}));
	assert.throws(() => assertCredentialTransition({
		staleAfterChange: 200,
		newAfterChange: 200,
		changedAfterReset: 401,
		defaultAfterReset: 200
	}), /stale credential/);
});

test('Password Reset dialog exposes confirmation but no password-entry request', () => {
	const safe = '<node text="Reset WebUI Password?"/><node text="Reset Password"/><node text="Cancel"/>';
	assert.doesNotThrow(() => assertPasswordResetDialog(safe));
	assert.throws(() => assertPasswordResetDialog('<node text="Reset Password"/>'), /confirmation/);
	assert.throws(
		() => assertPasswordResetDialog(`${safe}<node text="Current Password"/>`),
		/current Password/
	);
	assert.throws(
		() => assertPasswordResetDialog(`${safe}<node text="New Password"/>`),
		/password-entry/
	);
});

test('log audit requires every sensitive category and de-duplicates values', () => {
	const values = collectSensitiveAuditValues({
		credentials: ['old-secret', 'new-secret', 'old-secret'],
		authorizationHeaders: ['Basic abc'],
		magnets: ['magnet:?xt=urn:btih:private'],
		privateTrackers: ['https://tracker.invalid/private'],
		destinationPaths: ['/storage/emulated/0/Download/Private']
	});
	assert.deepEqual(values, [
		'old-secret',
		'new-secret',
		'Basic abc',
		'magnet:?xt=urn:btih:private',
		'https://tracker.invalid/private',
		'/storage/emulated/0/Download/Private'
	]);
	assert.throws(() => collectSensitiveAuditValues({
		credentials: [], authorizationHeaders: ['x'], magnets: ['x'], privateTrackers: ['x'], destinationPaths: ['x']
	}), /credentials/);
});

test('real-APK interruption runner is bounded and forbids shell permission grants', async () => {
	const runner = await readFile(fileURLToPath(new URL('./m6-interruption-runner.mjs', import.meta.url)), 'utf8');
	assert.match(runner, /AbortSignal\.timeout\(/);
	assert.match(runner, /assertInterruptionCoverage\(/);
	const forbidden = [
		new RegExp(['pm', 'grant'].join('\\s+'), 'i'),
		new RegExp(['app', 'ops'].join('\\s*'), 'i')
	];
	for (const relative of ['./m6-interruption-runner.mjs', './m6-real-apk-tools.mjs', './m4-android-ui.mjs']) {
		const source = await readFile(fileURLToPath(new URL(relative, import.meta.url)), 'utf8');
		assert(forbidden.every((pattern) => !pattern.test(source)), `${relative} grants Android permission from the shell`);
	}
	for (const relative of ['./m6-real-apk-tools.mjs', './m4-android-ui.mjs']) {
		const source = await readFile(fileURLToPath(new URL(relative, import.meta.url)), 'utf8');
		assert.match(source, /timeout: 30_000/, `${relative} leaves ADB subprocesses unbounded`);
	}
});
