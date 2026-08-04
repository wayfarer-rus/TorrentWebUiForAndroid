import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readdir, readFile } from 'node:fs/promises';
import { extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAndroidUi } from './m4-android-ui.mjs';
import { startOwnedFixtureController } from './m4-fixture.mjs';
import { OFFICIAL_TORRENT_SOURCES } from './m4-official-torrents.mjs';
import {
	CATALOG_STATE_PATHS,
	catalogStatesOwnedByM4,
	onboardingStateOwnedByM4
} from './m4-private-state.mjs';
import { seedRunAsFile } from './m6-real-apk-tools.mjs';
import { COMPLETED_ONBOARDING_RECORD } from './onboarding-private-state-fixture.mjs';

const e2eDirectory = fileURLToPath(new URL('.', import.meta.url));
const repositoryRoot = resolve(e2eDirectory, '../..');
const packageName = 'com.andreiefimov.torrentwebui';
const hostPort = process.env.M4_HOST_PORT ?? '8081';
const devicePort = process.env.M4_DEVICE_PORT ?? '8080';
const adb = process.env.ADB ?? `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;

assert(!Object.hasOwn(process.env, 'M4_KEEP_STATE'), 'M4_KEEP_STATE is forbidden: M4 acceptance must always run complete teardown.');
assert(process.env.WEBUI_PASSWORD, 'WEBUI_PASSWORD must be set.');
const basicAuthorization = `Basic ${Buffer.from(`:${process.env.WEBUI_PASSWORD}`).toString('base64')}`;

const forbiddenPermissionCommands = [
	new RegExp(['pm', 'grant'].join('\\s+'), 'i'),
	new RegExp(['app', 'ops'].join('\\s*'), 'i')
];

async function sourceFiles(directory) {
	const entries = await readdir(directory, { withFileTypes: true });
	const files = [];
	for (const entry of entries) {
		const path = join(directory, entry.name);
		if (entry.isDirectory()) files.push(...await sourceFiles(path));
		else if (['.kt', '.mjs'].includes(extname(entry.name))) files.push(path);
	}
	return files;
}

const guardedRoots = [join(repositoryRoot, 'app/src/androidTest'), e2eDirectory];
const runnerPath = fileURLToPath(import.meta.url);
for (const root of guardedRoots) {
	for (const path of await sourceFiles(root)) {
		if (path === runnerPath) continue;
		const source = await readFile(path, 'utf8');
		for (const forbidden of forbiddenPermissionCommands) {
			assert(!forbidden.test(source), `Forbidden permission-shell command found in ${path}`);
		}
	}
}

function adbRun(...args) {
	return execFileSync(adb, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function waitFor(check, description, timeoutMs = 20_000) {
	const deadline = Date.now() + timeoutMs;
	let lastError;
	while (Date.now() < deadline) {
		try {
			const result = check();
			if (result) return result;
		} catch (error) {
			lastError = error;
		}
		execFileSync('sleep', ['0.25']);
	}
	throw new Error(`Timed out waiting for ${description}`, { cause: lastError });
}

const devices = adbRun('devices').split('\n').slice(1).map((line) => line.trim().split(/\s+/)).filter((parts) => parts[1] === 'device');
assert.equal(devices.length, 1, `Expected exactly one connected Android device, found ${devices.length}. Set ADB to an isolated emulator.`);
const serial = process.env.ADB_SERIAL ?? devices[0][0];
assert.equal(serial, devices[0][0], `ADB_SERIAL ${serial} is not the connected device.`);
const adbArgs = (...args) => adbRun('-s', serial, ...args);
assert.equal(adbArgs('shell', 'getprop', 'ro.kernel.qemu'), '1', 'M4 acceptance refuses destructive setup on a physical device.');
assert.equal(adbArgs('shell', 'getprop', 'ro.boot.qemu.avd_name'), 'emulator_skill', 'M4 acceptance requires the isolated emulator_skill AVD.');
const androidUi = createAndroidUi({ adb, serial, packageName });

function cleanupOwnedPrivateState() {
	const packageState = adbArgs(
		'shell',
		`if pm path '${packageName}' >/dev/null 2>&1; then echo present; else echo absent; fi`
	);
	assert(['present', 'absent'].includes(packageState), 'Unable to determine package state before cleanup.');
	if (packageState === 'absent') return;
	function readPrivate(path) {
		const state = adbArgs(
			'exec-out', 'run-as', packageName, 'sh', '-c',
			`if [ -e '${path}' ]; then echo present; else echo absent; fi`
		);
		assert(['present', 'absent'].includes(state), `Unable to prove private-state presence for ${path}.`);
		if (state === 'absent') return null;
		return adbArgs('exec-out', 'run-as', packageName, 'cat', path);
	}
	function persistedEntries(text, path) {
		if (text === null) return [];
		const parsed = JSON.parse(text);
		assert(Array.isArray(parsed.entries), `Private state ${path} has no entries array.`);
		return parsed.entries;
	}
	const queueText = readPrivate('files/queue_intent.json');
	const journalText = readPrivate('files/move_journal_v2.json');
	const catalogStates = CATALOG_STATE_PATHS.map(readPrivate);
	const queueEntries = persistedEntries(queueText, 'files/queue_intent.json');
	const journalEntries = persistedEntries(journalText, 'files/move_journal_v2.json');
	const queueOwned = queueEntries.every((entry) => String(entry.destinationPath ?? '').includes('TorrentWebUi-M4-'));
	const journalOwned = journalEntries.every((entry) =>
		String(entry.sourcePath ?? '').includes('TorrentWebUi-M4-') && String(entry.targetPath ?? '').includes('TorrentWebUi-M4-')
	);
	const catalogOwned = catalogStatesOwnedByM4(catalogStates);
	const resumeState = adbArgs('exec-out', 'run-as', packageName, 'sh', '-c',
		"if [ -d files/resume_data ]; then find files/resume_data -mindepth 1 -maxdepth 1 -type f -print; elif [ -e files/resume_data ]; then echo invalid; fi"
	);
	const resumeEntries = resumeState.split('\n').map((line) => line.trim()).filter(Boolean);
	const resumeOwned = resumeEntries.length === 0 || (
		queueEntries.length > 0 && queueOwned && resumeEntries.every((path) => /^files\/resume_data\/\d+$/.test(path))
	);
	const authText = readPrivate('shared_prefs/webui_auth.xml');
	const credentialOwned = authText === null || !authText.includes('name="webui_password"');
	const onboardingText = readPrivate('shared_prefs/consumer_onboarding.xml');
	const onboardingOwned = onboardingStateOwnedByM4(onboardingText);
	assert(queueOwned && journalOwned && catalogOwned && resumeOwned && credentialOwned && onboardingOwned,
		'Refusing to remove app-private state that is not wholly owned by M4 fixtures.');
	adbArgs(
		'exec-out', 'run-as', packageName, 'rm', '-f',
		'files/queue_intent.json', 'files/move_journal_v2.json',
		'shared_prefs/consumer_onboarding.xml',
		...CATALOG_STATE_PATHS
	);
	adbArgs('exec-out', 'run-as', packageName, 'rm', '-rf', 'files/resume_data');
}

const fixtureRoot = `/storage/emulated/0/Download/TorrentWebUi-M4-${process.pid}-${Date.now()}`;
const firstDestination = `${fixtureRoot}/destination-a`;
const secondDestination = `${fixtureRoot}/destination-b`;
const moveDestination = `${fixtureRoot}/destination-move`;
const conflictDestination = `${fixtureRoot}/destination-conflict`;
const partialDestination = `${fixtureRoot}/destination-partial`;
const addValidDestination = `${fixtureRoot}/destination-add-valid`;
const addCorruptDestination = `${fixtureRoot}/destination-add-corrupt`;
const addUnrelatedDestination = `${fixtureRoot}/destination-add-unrelated`;
const stagingDestination = `${fixtureRoot}/staging`;
const officialDestinations = Object.fromEntries(
	OFFICIAL_TORRENT_SOURCES.map(({ label }) => [
		label,
		`${fixtureRoot}/official-${label.toLowerCase().replaceAll(' ', '-')}`
	])
);
const cleanupFailures = [];
let primaryFailure = null;
let fixtureController = null;
let removableVolumeId = null;

try {
	// Fail closed before the destructive clean-install-equivalent reset. This helper
	// removes only proven M4-owned leftovers and rejects user credentials/state.
	cleanupOwnedPrivateState();
	assert.match(adbArgs('shell', 'pm', 'clear', packageName), /Success/, 'Unable to establish clean-install-equivalent app state.');
	seedRunAsFile(adbArgs, packageName, 'shared_prefs/consumer_onboarding.xml', COMPLETED_ONBOARDING_RECORD);
	adbArgs('shell', 'sm', 'set-virtual-disk', 'true');
	const diskId = waitFor(() => adbArgs('shell', 'sm', 'list-disks').split('\n').find((line) => line.startsWith('disk:')), 'virtual storage disk');
	adbArgs('shell', 'sm', 'partition', diskId, 'public');
	const volume = waitFor(() => {
		const line = adbArgs('shell', 'sm', 'list-volumes', 'all').split('\n').find((candidate) => /^public:\S+ mounted \S+/.test(candidate));
		if (!line) return null;
		const [id, , uuid] = line.split(/\s+/);
		return { id, path: `/storage/${uuid}` };
	}, 'mounted removable fixture volume', 30_000);
	removableVolumeId = volume.id;
	const interruptedDestination = `${volume.path}/TorrentWebUi-M4-${process.pid}/interrupted-target`;

	adbArgs('shell', 'mkdir', '-p', firstDestination, secondDestination, moveDestination, conflictDestination, partialDestination,
		addValidDestination, addCorruptDestination, addUnrelatedDestination, stagingDestination, interruptedDestination,
		...Object.values(officialDestinations));
	fixtureController = await startOwnedFixtureController(adbArgs);
	await fixtureController.provision(fixtureController.completed, secondDestination);
	await fixtureController.provision(fixtureController.reuse, stagingDestination);
	await fixtureController.provisionPartial(fixtureController.partial, partialDestination);
	await fixtureController.provisionPartial(fixtureController.partial, stagingDestination);
	await fixtureController.provision(fixtureController.addValid, addValidDestination);
	adbArgs('shell', `printf '%s' corrupt > '${addCorruptDestination}/${fixtureController.addCorrupt.name}'`);
	adbArgs('shell', 'touch', `${addUnrelatedDestination}/unrelated-sibling`);
	await fixtureController.provision(fixtureController.interrupted, secondDestination);
	// Valid matching target data must be reused only after native piece verification;
	// unrelated siblings must neither conflict nor be removed.
	adbArgs('shell', 'cp', `${secondDestination}/${fixtureController.completed.name}`, `${moveDestination}/${fixtureController.completed.name}`);
	adbArgs('shell', 'touch', `${moveDestination}/unrelated-sibling`);
	// A colliding torrent-owned path with incompatible bytes must verify as conflict.
	adbArgs('shell', `printf '%s' corrupt > '${conflictDestination}/${fixtureController.completed.name}'`);

	const observedFirst = adbArgs('shell', 'readlink', '-f', firstDestination);
	const observedSecond = adbArgs('shell', 'readlink', '-f', secondDestination);
	const observedMove = adbArgs('shell', 'readlink', '-f', moveDestination);
	const observedConflict = adbArgs('shell', 'readlink', '-f', conflictDestination);
	const observedPartial = adbArgs('shell', 'readlink', '-f', partialDestination);
	const observedAddValid = adbArgs('shell', 'readlink', '-f', addValidDestination);
	const observedAddCorrupt = adbArgs('shell', 'readlink', '-f', addCorruptDestination);
	const observedAddUnrelated = adbArgs('shell', 'readlink', '-f', addUnrelatedDestination);
	const observedInterrupted = adbArgs('shell', 'readlink', '-f', interruptedDestination);
	const observedOfficialDestinations = Object.fromEntries(
		Object.entries(officialDestinations).map(([label, path]) => [label, adbArgs('shell', 'readlink', '-f', path)])
	);
	assert.equal(observedFirst, firstDestination, 'ADB did not observe the first canonical fixture directory.');
	assert.equal(observedSecond, secondDestination, 'ADB did not observe the second canonical fixture directory.');
	assert.equal(observedMove, moveDestination, 'ADB did not observe the completed-move fixture directory.');
	assert.equal(observedConflict, conflictDestination, 'ADB did not observe the conflict fixture directory.');
	assert.equal(observedPartial, partialDestination, 'ADB did not observe the partial verification directory.');
	assert.equal(observedAddValid, addValidDestination, 'ADB did not observe the safe-add reuse directory.');
	assert.equal(observedAddCorrupt, addCorruptDestination, 'ADB did not observe the safe-add conflict directory.');
	assert.equal(observedAddUnrelated, addUnrelatedDestination, 'ADB did not observe the safe-add unrelated directory.');
	assert.equal(observedInterrupted, interruptedDestination, 'ADB did not observe the removable canonical fixture directory.');
	assert.deepEqual(observedOfficialDestinations, officialDestinations, 'ADB did not observe canonical official-smoke directories.');

	await androidUi.assertStartupDenialAndRestore();
	adbArgs('forward', `tcp:${hostPort}`, `tcp:${devicePort}`);
	waitFor(() => {
		try {
			return JSON.parse(execFileSync('curl', ['-fsS', `http://127.0.0.1:${hostPort}/health`], { encoding: 'utf8' })).status === 'ok';
		} catch { return false; }
	}, 'Ktor health after visible permission restoration', 30_000);

	Object.assign(process.env, {
		M4_FIXTURE_ROOT: fixtureRoot,
		M4_FIRST_DESTINATION: firstDestination,
		M4_SECOND_DESTINATION: secondDestination,
		M4_MOVE_DESTINATION: moveDestination,
		M4_CONFLICT_DESTINATION: conflictDestination,
		M4_PARTIAL_DESTINATION: partialDestination,
		M4_ADD_VALID_DESTINATION: addValidDestination,
		M4_ADD_CORRUPT_DESTINATION: addCorruptDestination,
		M4_ADD_UNRELATED_DESTINATION: addUnrelatedDestination,
		M4_INTERRUPTED_DESTINATION: interruptedDestination,
		M4_INTERRUPTED_VOLUME_ID: removableVolumeId,
		M4_STAGED_REUSE_PAYLOAD: `${stagingDestination}/${fixtureController.reuse.name}`,
		M4_STAGED_PARTIAL_PAYLOAD: `${stagingDestination}/${fixtureController.partial.name}`,
		M4_FIXTURE_MAGNET: fixtureController.completed.magnet,
		M4_FIXTURE_NAME: fixtureController.completed.name,
		M4_REUSE_MAGNET: fixtureController.reuse.magnet,
		M4_REUSE_NAME: fixtureController.reuse.name,
		M4_PARTIAL_MAGNET: fixtureController.partial.magnet,
		M4_PARTIAL_NAME: fixtureController.partial.name,
		M4_ADD_VALID_MAGNET: fixtureController.addValid.magnet,
		M4_ADD_VALID_NAME: fixtureController.addValid.name,
		M4_ADD_CORRUPT_MAGNET: fixtureController.addCorrupt.magnet,
		M4_ADD_CORRUPT_NAME: fixtureController.addCorrupt.name,
		M4_ADD_UNRELATED_MAGNET: fixtureController.addUnrelated.magnet,
		M4_ADD_UNRELATED_NAME: fixtureController.addUnrelated.name,
		M4_INTERRUPTED_MAGNET: fixtureController.interrupted.magnet,
		M4_INTERRUPTED_NAME: fixtureController.interrupted.name,
		M4_FIXTURE_CONTROL_URL: fixtureController.controlBaseUrl,
		M4_OFFICIAL_DESTINATIONS: JSON.stringify(officialDestinations),
		M4_ADB_OFFICIAL_DESTINATIONS: JSON.stringify(observedOfficialDestinations),
		M4_ADB_FIRST_DESTINATION: observedFirst,
		M4_ADB_SECOND_DESTINATION: observedSecond,
		M4_ADB_MOVE_DESTINATION: observedMove,
		M4_ADB_CONFLICT_DESTINATION: observedConflict,
		M4_ADB_PARTIAL_DESTINATION: observedPartial,
		M4_ADB_ADD_VALID_DESTINATION: observedAddValid,
		M4_ADB_ADD_CORRUPT_DESTINATION: observedAddCorrupt,
		M4_ADB_ADD_UNRELATED_DESTINATION: observedAddUnrelated,
		M4_ADB_INTERRUPTED_DESTINATION: observedInterrupted,
		M4_ADB: adb,
		M4_ADB_SERIAL: serial,
		WEBUI_BASE_URL: process.env.WEBUI_BASE_URL ?? `http://127.0.0.1:${hostPort}`
	});

	console.log(`M4 ADB evidence: serial=${serial} first=${observedFirst} second=${observedSecond} move=${observedMove} conflict=${observedConflict} partial=${observedPartial} addValid=${observedAddValid} addConflict=${observedAddCorrupt} addUnrelated=${observedAddUnrelated} removable=${observedInterrupted}`);
	await import('./m4-live.mjs');
} catch (error) {
	primaryFailure = error;
} finally {
	try {
		const listenerPattern = new RegExp(`:${devicePort}\\s`);
		if (listenerPattern.test(adbArgs('shell', 'ss', '-tln'))) {
			adbArgs('forward', `tcp:${hostPort}`, `tcp:${devicePort}`);
			const response = await fetch(`http://127.0.0.1:${hostPort}/api/daemon/stop`, {
				method: 'POST',
				headers: { Authorization: basicAuthorization },
				signal: AbortSignal.timeout(5_000)
			});
			const body = await response.text();
			assert(response.ok, `Failure cleanup could not stop daemon: HTTP ${response.status} ${body}`);
			waitFor(
				() => !listenerPattern.test(adbArgs('shell', 'ss', '-tln')),
				'daemon/Ktor shutdown during outer cleanup',
				10_000
			);
		}
	} catch (error) {
		cleanupFailures.push(new Error('Failure-safe daemon/server cleanup failed', { cause: error }));
	}
	try { cleanupOwnedPrivateState(); } catch (error) {
		cleanupFailures.push(new Error('Owned app-private fixture cleanup failed', { cause: error }));
	}
	if (removableVolumeId) {
		try { adbArgs('shell', 'sm', 'mount', removableVolumeId); } catch (error) { cleanupFailures.push(new Error('Removable volume remount cleanup failed', { cause: error })); }
	}
	try {
		adbArgs('shell', 'rm', '-rf', fixtureRoot);
		const remaining = adbArgs('shell', `if [ -e '${fixtureRoot}' ]; then echo present; else echo absent; fi`);
		if (remaining !== 'absent') cleanupFailures.push(new Error(`Fixture root still exists: ${fixtureRoot}`));
	} catch (error) {
		cleanupFailures.push(new Error(`ADB fixture cleanup failed for ${fixtureRoot}`, { cause: error }));
	}
	try { adbArgs('shell', 'sm', 'set-virtual-disk', 'false'); } catch (error) { cleanupFailures.push(new Error('Virtual storage cleanup failed', { cause: error })); }
	if (fixtureController) {
		try { await fixtureController.stop(); } catch (error) { cleanupFailures.push(error); }
	}
	try {
		const hasForward = adbArgs('forward', '--list').split('\n').some((line) => line.includes(`tcp:${hostPort} tcp:${devicePort}`));
		if (hasForward) adbArgs('forward', '--remove', `tcp:${hostPort}`);
	} catch (error) { cleanupFailures.push(new Error(`ADB forward cleanup failed for tcp:${hostPort}`, { cause: error })); }
	try {
		const listeners = adbArgs('shell', 'ss', '-tln');
		if (new RegExp(`:${devicePort}\\s`).test(listeners)) cleanupFailures.push(new Error(`Ktor listener remains on device port ${devicePort}.`));
	} catch (error) {
		cleanupFailures.push(new Error('Independent server-state cleanup check failed', { cause: error }));
	}
}

if (primaryFailure) {
	if (cleanupFailures.length) primaryFailure.cleanupFailures = cleanupFailures;
	throw primaryFailure;
}
if (cleanupFailures.length) throw new AggregateError(cleanupFailures, 'M4 host cleanup failed.');
console.log(`M4 host cleanup passed for ${packageName}; daemon/server, owned fixtures, virtual storage, and ADB forwarding are absent.`);
