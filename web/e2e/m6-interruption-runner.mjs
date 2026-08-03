import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createAndroidUi } from './m4-android-ui.mjs';
import { createAdbRunner, waitFor } from './m6-real-apk-tools.mjs';
import {
	assertHostForwardAvailable,
	assertNoDefaultPasswordInRenderedContent,
	assertSensitiveValuesAbsent,
	assertTeardownComplete
} from './m6-clean-install-policy.mjs';
import {
	assertCredentialTransition,
	assertInterruptionCoverage,
	assertMigrationCoverage,
	assertPasswordResetDialog,
	assertResumeAndRecoveryCoverage,
	collectSensitiveAuditValues
} from './m6-interruption-policy.mjs';

const e2eDirectory = fileURLToPath(new URL('.', import.meta.url));
const repositoryRoot = resolve(e2eDirectory, '../..');
const packageName = 'com.andreiefimov.torrentwebui';
const activityName = `${packageName}/.MainActivity`;
const devicePort = process.env.M6_DEVICE_PORT ?? '8080';
const hostPort = process.env.M6_HOST_PORT ?? '18083';
const adb = process.env.ADB ?? `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;
const apk = resolve(repositoryRoot, 'app/build/outputs/apk/debug/app-debug.apk');
const baseUrl = `http://127.0.0.1:${hostPort}`;
const defaultPassword = 'start123';
const changedPassword = `m6-household-${process.pid}`;
const migrationPassword = `m6-established-${process.pid}`;
const tracker = 'https://tracker.invalid/m6-private';
const encodedTracker = encodeURIComponent(tracker);
const sensitiveMagnet = `magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=m6-private&tr=${encodedTracker}`;
const fixtureRootName = `TorrentWebUi-M6-09-${process.pid}`;
const selectedScenario = process.env.M6_SCENARIO ?? 'all';
assert(['all', 'migration', 'journey'].includes(selectedScenario), `Unsupported M6_SCENARIO: ${selectedScenario}`);

const adbRun = createAdbRunner(adb);

function basicAuthorization(password) {
	return `Basic ${Buffer.from(`browser:${password}`).toString('base64')}`;
}

function shellSingleQuote(value) {
	return `'${value.replaceAll("'", "'\\''")}'`;
}

assert(existsSync(apk), `Debug APK is missing: ${apk}`);
const devices = adbRun('devices').split('\n').slice(1)
	.map((line) => line.trim().split(/\s+/))
	.filter((parts) => parts[1] === 'device');
assert.equal(devices.length, 1, `Expected exactly one connected Android device, found ${devices.length}.`);
const serial = process.env.ADB_SERIAL ?? devices[0][0];
assert.equal(serial, devices[0][0], `ADB_SERIAL ${serial} is not the connected device.`);
const adbArgs = (...args) => adbRun('-s', serial, ...args);
assert.equal(adbArgs('shell', 'getprop', 'ro.kernel.qemu'), '1', 'M6 acceptance refuses physical devices.');
assert.equal(adbArgs('shell', 'getprop', 'ro.boot.qemu.avd_name'), 'emulator_skill', 'The isolated emulator_skill AVD is required.');

const androidUi = createAndroidUi({ adb, serial, packageName });
const listenerPattern = new RegExp(`:${devicePort}\\s`);
const externalStorage = adbArgs('shell', 'readlink', '-f', adbArgs('shell', 'printenv', 'EXTERNAL_STORAGE'));
const fixtureRoot = `${externalStorage}/Download/${fixtureRootName}`;
const migrationDestination = `${fixtureRoot}/migration`;
let alternateDestination;
let removableFixtureRoot;
let removableVolumeId;
let browser;
let browserContext;
let forwardInstalled = false;
let primaryFailure;
let evidence;
const cleanupFailures = [];
let scenarioPids = new Set();

function listenerActive() {
	return listenerPattern.test(adbArgs('shell', 'ss', '-tln'));
}

async function request(path, password, options = {}) {
	return fetch(`${baseUrl}${path}`, {
		...options,
		headers: { Authorization: basicAuthorization(password), ...(options.headers ?? {}) },
		signal: options.signal ?? AbortSignal.timeout(5_000)
	});
}

async function onboardingStatus(password) {
	const response = await request('/api/onboarding/status', password);
	assert(response.ok, `Onboarding status failed with HTTP ${response.status}.`);
	return response.json();
}

function installOwnedForward() {
	assertHostForwardAvailable(adbArgs('forward', '--list'), serial, hostPort);
	adbArgs('forward', '--no-rebind', `tcp:${hostPort}`, `tcp:${devicePort}`);
	forwardInstalled = true;
}

function recordAppProcessIds() {
	const output = adbArgs('shell', `pidof '${packageName}' || true`);
	assert(output, 'The application process is not running.');
	output.split(/\s+/).filter(Boolean).forEach((pid) => scenarioPids.add(pid));
}

async function tapVisibleAndroidAction(label, attempts = 8) {
	for (let attempt = 0; attempt < attempts; attempt += 1) {
		const hierarchy = await androidUi.hierarchy();
		const node = hierarchy.match(new RegExp(`<node[^>]*text="${label}"[^>]*enabled="true"[^>]*>`))?.[0];
		if (node) {
			const bounds = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
			assert(bounds, `${label} UI node has no bounds.`);
			const x = Math.floor((Number(bounds[1]) + Number(bounds[3])) / 2);
			const y = Math.floor((Number(bounds[2]) + Number(bounds[4])) / 2);
			androidUi.run('shell', 'input', 'tap', String(x), String(y));
			return;
		}
		androidUi.run('shell', 'input', 'swipe', '540', '1900', '540', '650', '250');
		await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
	}
	assert.fail(`${label} was not visible and enabled in the Android fallback UI.`);
}

async function observePermissionRecoveryState() {
	for (let attempt = 0; attempt < 3; attempt += 1) {
		androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_HOME');
		androidUi.run('shell', 'am', 'start', '-n', activityName);
		try {
			await androidUi.waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Torrent Daemon permission recovery surface', 3_000);
			for (let scroll = 0; scroll < 8; scroll += 1) {
				const hierarchy = await androidUi.hierarchy();
				if (/<node[^>]*text="(?:Revoked|Denied)[^"]*"[^>]*>/.test(hierarchy)) return;
				androidUi.run('shell', 'input', 'swipe', '540', '1900', '540', '650', '250');
				await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
			}
			throw new Error('Permission recovery state was not visible after scrolling.');
		} catch {
			await androidUi.waitForNode(/<node[^>]*checkable="true"[^>]*checked="false"[^>]*>/, 'revoked All Files Access switch', 10_000);
			androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_BACK');
			// A process transition can return to Home; relaunch and retry the visible flow.
		}
	}
	assert.fail('Android did not expose its visible permission recovery state.');
}

async function startApp(password) {
	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await androidUi.waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Torrent Daemon screen', 15_000);
	await new Promise((resolveDelay) => setTimeout(resolveDelay, 500));
	if (!listenerActive()) {
		let started = false;
		for (let attempt = 0; attempt < 3 && !started; attempt += 1) {
			try {
				await tapVisibleAndroidAction('Start downloads');
			} catch (error) {
				if (!listenerActive()) throw error;
			}
			try {
				await waitFor(() => listenerActive(), 'Ktor listener after visible Start downloads', 10_000);
				started = true;
			} catch {
				androidUi.run('shell', 'am', 'start', '--activity-reorder-to-front', '-n', activityName);
			}
		}
		assert(started, 'Ktor listener did not start after three visible Android retries.');
	}
	recordAppProcessIds();
	await waitFor(async () => (await request('/health', password)).ok, 'authenticated Ktor health');
}

async function restartApp(password) {
	androidUi.run('shell', 'am', 'force-stop', packageName);
	await waitFor(() => !listenerActive(), 'listener shutdown after process interruption', 15_000);
	await startApp(password);
}

async function crashAndRestartApp(password, description) {
	const before = adbArgs('shell', `pidof '${packageName}' || true`);
	assert(before, `${description} requires a running application process.`);
	adbArgs('shell', 'am', 'crash', packageName);
	await waitFor(() => !listenerActive(), `${description} listener failure`, 15_000);
	androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_BACK');
	await startApp(password);
	const after = adbArgs('shell', `pidof '${packageName}' || true`);
	assert(after && after !== before, `${description} did not replace the application process.`);
}

async function openBrowser(password) {
	await browserContext?.close();
	browserContext = await browser.newContext({
		httpCredentials: { username: 'browser', password }
	});
	const page = await browserContext.newPage();
	await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
	return page;
}

async function stopDaemon(password) {
	if (!listenerActive()) return;
	const response = await request('/api/daemon/stop', password, { method: 'POST' });
	assert(response.ok, `Daemon stop failed with HTTP ${response.status}.`);
	await waitFor(() => !listenerActive(), 'daemon and Ktor shutdown', 15_000);
}

function privateStateIsAbsent() {
	const remaining = adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		[
			'files/queue_intent.json',
			'files/move_journal_v2.json',
			'files/destination_catalog.txt',
			'files/resume_data',
			'shared_prefs/consumer_onboarding.xml',
			'shared_prefs/webui_auth.xml'
		].map((path) => `[ -e '${path}' ] && echo '${path}'`).join('; ') + '; true'
	);
	return remaining === '';
}

function clearOwnedPrivateState() {
	androidUi.run('shell', 'am', 'force-stop', packageName);
	adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		'rm -rf files/* shared_prefs/* databases/* no_backup/* cache/* code_cache/*; mkdir -p files shared_prefs'
	);
	assert(privateStateIsAbsent(), 'App-private M6 state remained after scenario cleanup.');
}

function seedPrivateFile(relativePath, content) {
	const encoded = Buffer.from(content).toString('base64');
	const parent = relativePath.split('/').slice(0, -1).join('/');
	adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		`mkdir -p ${shellSingleQuote(parent)}; echo ${shellSingleQuote(encoded)} | base64 -d > ${shellSingleQuote(relativePath)}`
	);
}

function onboardingPreferences({ completed = false, decision = 'Pending' } = {}) {
	return `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<boolean name="initialized" value="true" />\n<boolean name="completed" value="${completed}" />\n<string name="password_decision">${decision}</string>\n</map>\n`;
}

function authPreferences(password) {
	return `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<string name="webui_password">${password}</string>\n</map>\n`;
}

function catalogContent(path) {
	return `@latest|${path}\n${path}|1`;
}

function queueContent(path) {
	return JSON.stringify({
		version: 2,
		entries: [{
			magnetUri: sensitiveMagnet,
			isPaused: true,
			destinationPath: path,
			queueId: 'm6-ticket-09-queue',
			storagePauseRequired: false
		}]
	});
}

async function provisionRemovableDestination() {
	adbArgs('shell', 'sm', 'set-virtual-disk', 'true');
	const diskId = await waitFor(
		() => adbArgs('shell', 'sm', 'list-disks').split('\n').find((line) => line.startsWith('disk:')),
		'virtual storage disk'
	);
	adbArgs('shell', 'sm', 'partition', diskId, 'public');
	const volume = await waitFor(() => {
		const line = adbArgs('shell', 'sm', 'list-volumes', 'all')
			.split('\n')
			.find((candidate) => /^public:\S+ mounted \S+/.test(candidate));
		if (!line) return false;
		const [id, , uuid] = line.split(/\s+/);
		return { id, root: `/storage/${uuid}` };
	}, 'mounted removable Storage Volume', 30_000);
	removableVolumeId = volume.id;
	removableFixtureRoot = `${volume.root}/${fixtureRootName}`;
	alternateDestination = `${removableFixtureRoot}/alternate`;
	assert.equal(
		adbArgs('shell', `if [ -e ${shellSingleQuote(removableFixtureRoot)} ]; then echo present; fi`),
		'',
		`Removable fixture root already exists: ${removableFixtureRoot}`
	);
	adbArgs('shell', 'mkdir', '-p', alternateDestination);
	assert.equal(adbArgs('shell', 'readlink', '-f', alternateDestination), alternateDestination);
}

function completedMarkerIsDurable() {
	const marker = adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		"cat shared_prefs/consumer_onboarding.xml 2>/dev/null || true"
	);
	return /name="completed" value="true"/.test(marker);
}

async function assertNormalWebUi(page) {
	await page.getByRole('heading', { name: 'Add Torrent' }).waitFor();
	assert.equal(await page.getByRole('heading', { name: 'Consumer Onboarding' }).count(), 0);
}

async function assertDestinationStep(page) {
	await page.getByRole('heading', { name: 'Choose a download folder' }).waitFor();
	assertNoDefaultPasswordInRenderedContent(await page.locator('body').innerText());
}

async function assertPasswordStep(page) {
	await page.getByRole('heading', { name: 'Password choice' }).waitFor();
	assertNoDefaultPasswordInRenderedContent(await page.locator('body').innerText());
}

async function auditScenarioLogs(values) {
	const logs = [...scenarioPids]
		.map((pid) => adbArgs('logcat', '-d', `--pid=${pid}`))
		.join('\n');
	assertSensitiveValuesAbsent(logs, values);
}

async function resetScenario(password, sensitiveValues) {
	try { await stopDaemon(password); } catch { androidUi.run('shell', 'am', 'force-stop', packageName); }
	await browserContext?.close();
	browserContext = undefined;
	await auditScenarioLogs(sensitiveValues);
	clearOwnedPrivateState();
	scenarioPids = new Set();
}

function progress(stage) {
	console.error(`[m6-09] ${stage}`);
}

async function runMigrationEvidence(name, seed, password, expectedDestination, sensitiveValues) {
	progress(`migration:${name}`);
	clearOwnedPrivateState();
	seed();
	adbArgs('logcat', '-c');
	await startApp(password);
	let page;
	if (name === 'durableQueue') {
		page = await openBrowser(password);
		await assertNormalWebUi(page);
	}
	const migrated = await onboardingStatus(password);
	assert.equal(migrated.completed, true, `${name} did not migrate to completed onboarding.`);
	assert.equal(migrated.passwordDecision, 'deferred');
	assert.equal(migrated.hasApprovedDestination, expectedDestination);

	if (name === 'durableQueue') {
		androidUi.run('shell', 'am', 'force-stop', packageName);
		await waitFor(() => !listenerActive(), `${name} migration restart`);
		adbArgs('exec-out', 'run-as', packageName, 'rm', '-f', 'files/queue_intent.json');
		await startApp(password);
		page = await openBrowser(password);
		await assertNormalWebUi(page);
		assert.equal((await onboardingStatus(password)).completed, true, `${name} migration was not durable.`);
	}
	await resetScenario(password, sensitiveValues);
}

try {
	assert.equal(adbArgs('shell', `if [ -e ${shellSingleQuote(fixtureRoot)} ]; then echo present; fi`), '', `Fixture root already exists: ${fixtureRoot}`);
	try { adbArgs('uninstall', packageName); } catch { /* Fresh emulator may not have the package. */ }
	assert.match(adbArgs('install', apk), /Success/, 'APK installation failed.');
	assert.match(adbArgs('shell', 'pm', 'clear', packageName), /Success/, 'Unable to clear application data.');
	adbArgs('logcat', '-c');
	androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_HOME');
	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await androidUi.waitForNode(
		/<node[^>]*(?:text="(?:Allow|ALLOW)"|text="Torrent Daemon")[^>]*>/,
		'first actionable Android Startup Bootstrap screen',
		15_000
	);
	await androidUi.grantNotificationIfRequested();
	const bootstrapPid = adbArgs('shell', `pidof '${packageName}' || true`);
	assert(bootstrapPid, 'Android Startup Bootstrap process is not running.');
	androidUi.run('shell', 'am', 'force-stop', packageName);
	androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_HOME');
	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await androidUi.waitForNode(/<node[^>]*checkable="true"[^>]*checked="false"[^>]*>/, 'All Files Access after bootstrap process restart', 15_000);
	try {
		await androidUi.setCurrentAllFilesAccess(true);
	} catch {
		// The API 36 notification dialog can arrive after the first bounded wait on a cold app process.
		await androidUi.grantNotificationIfRequested();
		await androidUi.setCurrentAllFilesAccess(true);
	}
	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await androidUi.waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Torrent Daemon screen');
	await tapVisibleAndroidAction('Start downloads');
	await waitFor(() => listenerActive(), 'initial Ktor listener', 30_000);
	installOwnedForward();
	await waitFor(async () => (await request('/health', defaultPassword)).ok, 'initial authenticated Ktor health');
	await stopDaemon(defaultPassword);
	clearOwnedPrivateState();
	adbArgs('shell', 'mkdir', '-p', migrationDestination);
	assert.equal(adbArgs('shell', 'readlink', '-f', migrationDestination), migrationDestination);
	await provisionRemovableDestination();

	browser = await chromium.launch({ headless: process.env.M6_HEADED !== 'true' });
	const defaultAuthorization = basicAuthorization(defaultPassword);
	const migrationAuthorization = basicAuthorization(migrationPassword);
	const commonSensitive = {
		credentials: [defaultPassword, migrationPassword, changedPassword],
		authorizationHeaders: [defaultAuthorization, migrationAuthorization, basicAuthorization(changedPassword)],
		magnets: [sensitiveMagnet],
		privateTrackers: [tracker, encodedTracker],
		destinationPaths: [alternateDestination, migrationDestination]
	};
	const sensitiveValues = collectSensitiveAuditValues(commonSensitive);
	const migrationEvidence = [];
	let page;

	if (selectedScenario !== 'journey') {
	await runMigrationEvidence(
		'durableQueue',
		() => seedPrivateFile('files/queue_intent.json', queueContent(migrationDestination)),
		defaultPassword,
		false,
		sensitiveValues
	);
	await runMigrationEvidence(
		'approvedDestination',
		() => seedPrivateFile('files/destination_catalog.txt', catalogContent(migrationDestination)),
		defaultPassword,
		true,
		sensitiveValues
	);
	await runMigrationEvidence(
		'nonDefaultPassword',
		() => seedPrivateFile('shared_prefs/webui_auth.xml', authPreferences(migrationPassword)),
		migrationPassword,
		false,
		sensitiveValues
	);

	progress('migration:incomplete-marker-precedence');
	clearOwnedPrivateState();
	seedPrivateFile('files/destination_catalog.txt', catalogContent(migrationDestination));
	seedPrivateFile('shared_prefs/consumer_onboarding.xml', onboardingPreferences());
	adbArgs('logcat', '-c');
	await startApp(defaultPassword);
	page = await openBrowser(defaultPassword);
	await assertPasswordStep(page);
	const incomplete = await onboardingStatus(defaultPassword);
	assert.equal(incomplete.completed, false);
	assert.equal(incomplete.hasApprovedDestination, true);
	assert.equal(incomplete.passwordDecision, 'pending');
	await resetScenario(defaultPassword, sensitiveValues);
	migrationEvidence.push('durableQueue', 'approvedDestination', 'nonDefaultPassword', 'incompleteMarkerPrecedence');
	}

	if (selectedScenario === 'migration') {
		const coverage = { resumePoints: [], migrationEvidence, recoveryStates: [] };
		assertMigrationCoverage(coverage);
		evidence = {
			scenario: 'M6 one-time onboarding migration and incomplete-marker precedence',
			environment: 'emulator-only', serial, devicePort, coverage, physicalLanClaim: false
		};
	}

	if (selectedScenario !== 'migration') {
	progress('interruption-and-credential');
	adbArgs('logcat', '-c');
	await startApp(defaultPassword);
	const resumePoints = [];
	page = await openBrowser(defaultPassword);
	await assertDestinationStep(page);

	await page.reload({ waitUntil: 'domcontentloaded' });
	await assertDestinationStep(page);
	resumePoints.push('destination:refresh');
	page = await openBrowser(defaultPassword);
	await assertDestinationStep(page);
	resumePoints.push('destination:browser-close');
	await browserContext.close();
	browserContext = undefined;
	await restartApp(defaultPassword);
	page = await openBrowser(defaultPassword);
	await assertDestinationStep(page);
	resumePoints.push('destination:process-restart');

	const rejectedMutation = await request('/api/torrents/magnet', defaultPassword, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ magnetUri: sensitiveMagnet, destinationPath: alternateDestination })
	});
	assert.equal(rejectedMutation.status, 409, 'Incomplete onboarding accepted a sensitive torrent mutation.');

	await page.getByRole('button', { name: 'Choose another folder' }).click();
	await page.getByLabel('Or paste an absolute path').fill(alternateDestination);
	await page.getByRole('button', { name: 'Check folder' }).click();
	await page.getByText(alternateDestination, { exact: true }).waitFor();
	await page.getByRole('button', { name: 'Use this folder' }).click();
	await assertPasswordStep(page);
	assert.equal((await onboardingStatus(defaultPassword)).hasApprovedDestination, true);

	await page.reload({ waitUntil: 'domcontentloaded' });
	await assertPasswordStep(page);
	resumePoints.push('password:refresh');
	page = await openBrowser(defaultPassword);
	await assertPasswordStep(page);
	resumePoints.push('password:browser-close');
	await browserContext.close();
	browserContext = undefined;
	await restartApp(defaultPassword);
	page = await openBrowser(defaultPassword);
	await assertPasswordStep(page);
	resumePoints.push('password:process-restart');

	await page.getByRole('button', { name: 'Choose another password' }).click();
	assert.equal(await page.getByLabel('Current Password').count(), 0);
	await page.getByLabel('New Password').fill(changedPassword);
	await page.getByLabel('Confirm Password').fill(changedPassword);
	const navigationStarted = page.waitForRequest((requestValue) => requestValue.isNavigationRequest());
	await page.getByRole('button', { name: 'Change Password' }).click();
	await navigationStarted;
	await page.close();
	resumePoints.push('reauthentication:browser-close');

	page = await openBrowser(changedPassword);
	await assertNormalWebUi(page);
	const changedStatus = await onboardingStatus(changedPassword);
	assert.equal(changedStatus.completed, true);
	assert.equal(changedStatus.passwordDecision, 'changed');
	await browserContext.close();
	browserContext = undefined;
	await restartApp(changedPassword);
	const staleAfterChange = (await request('/api/onboarding/status', defaultPassword)).status;
	const newAfterChange = (await request('/api/onboarding/status', changedPassword)).status;
	page = await openBrowser(changedPassword);
	await assertNormalWebUi(page);
	assert.equal((await onboardingStatus(changedPassword)).passwordDecision, 'changed');

	androidUi.run('shell', 'am', 'start', '--activity-reorder-to-front', '-n', activityName);
	await androidUi.waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Android Password Reset surface');
	await tapVisibleAndroidAction('Reset WebUI Password');
	const resetHierarchy = await androidUi.hierarchy();
	assertPasswordResetDialog(resetHierarchy);
	await androidUi.tapText('Reset Password');
	await waitFor(async () => (await request('/api/onboarding/status', defaultPassword)).ok, 'default credential after Password Reset', 10_000);
	const changedAfterReset = (await request('/api/onboarding/status', changedPassword)).status;
	const defaultAfterReset = (await request('/api/onboarding/status', defaultPassword)).status;
	const credentialStatuses = { staleAfterChange, newAfterChange, changedAfterReset, defaultAfterReset };
	assertCredentialTransition(credentialStatuses);
	page = await openBrowser(defaultPassword);
	await assertNormalWebUi(page);

	const recoveryStates = [];
	await androidUi.setAllFilesAccess(false);
	assert(completedMarkerIsDurable(), 'Permission loss cleared durable onboarding completion.');
	await observePermissionRecoveryState();
	recoveryStates.push('permissionLoss');

	await tapVisibleAndroidAction('Grant All Files Access');
	await androidUi.setCurrentAllFilesAccess(true);
	await startApp(defaultPassword);
	page = await openBrowser(defaultPassword);
	await assertNormalWebUi(page);

	adbArgs('shell', 'sm', 'unmount', removableVolumeId);
	await waitFor(
		() => adbArgs('shell', 'sm', 'list-volumes', 'all').split('\n').some((line) => line.startsWith(`${removableVolumeId} unmounted`)),
		'removable Storage Volume unavailability'
	);
	await page.reload({ waitUntil: 'domcontentloaded' });
	await assertNormalWebUi(page);
	assert.equal((await onboardingStatus(defaultPassword)).completed, true);
	recoveryStates.push('unavailableStorage');

	const removeDestination = await request(`/api/storage/destinations/${encodeURIComponent(alternateDestination)}`, defaultPassword, { method: 'DELETE' });
	assert(removeDestination.ok, `Last-destination removal failed with HTTP ${removeDestination.status}.`);
	const noDestinationStatus = await onboardingStatus(defaultPassword);
	assert.equal(noDestinationStatus.completed, true);
	assert.equal(noDestinationStatus.hasApprovedDestination, false);
	await page.reload({ waitUntil: 'domcontentloaded' });
	await assertNormalWebUi(page);
	recoveryStates.push('lastDestinationRemoval');

	await crashAndRestartApp(defaultPassword, 'completed-onboarding daemon failure');
	page = await openBrowser(defaultPassword);
	await assertNormalWebUi(page);
	assert.equal((await onboardingStatus(defaultPassword)).completed, true);
	recoveryStates.push('daemonFailure');

	const coverage = { resumePoints, migrationEvidence, recoveryStates };
	if (selectedScenario === 'all') assertInterruptionCoverage(coverage);
	else assertResumeAndRecoveryCoverage(coverage);
	await auditScenarioLogs(sensitiveValues);

	evidence = {
		scenario: selectedScenario === 'journey'
			? 'M6 onboarding interruption, credential recovery, and durable completion'
			: 'M6 onboarding interruption, migration, credential recovery, and durable completion',
		environment: 'emulator-only',
		serial,
		apiLevel: adbArgs('shell', 'getprop', 'ro.build.version.sdk'),
		abi: adbArgs('shell', 'getprop', 'ro.product.cpu.abi'),
		devicePort,
		alternateDestination,
		coverage,
		credentialStatuses,
		androidBootstrapResumedAfterProcessStop: true,
		physicalLanClaim: false
	};
	}
} catch (error) {
	primaryFailure = error;
} finally {
	try { await stopDaemon(defaultPassword); } catch { androidUi.run('shell', 'am', 'force-stop', packageName); }
	try { await browserContext?.close(); } catch (error) { cleanupFailures.push(error); }
	try { await browser?.close(); } catch (error) { cleanupFailures.push(error); }
	try {
		if (removableVolumeId) {
			const volumes = adbArgs('shell', 'sm', 'list-volumes', 'all');
			if (volumes.split('\n').some((line) => line.startsWith(`${removableVolumeId} unmounted`))) {
				adbArgs('shell', 'sm', 'mount', removableVolumeId);
				await waitFor(
					() => adbArgs('shell', 'sm', 'list-volumes', 'all').split('\n').some((line) => line.startsWith(`${removableVolumeId} mounted`)),
					'removable Storage Volume remount for cleanup'
				);
			}
		}
		if (removableFixtureRoot) adbArgs('shell', 'rm', '-rf', removableFixtureRoot);
		adbArgs('shell', 'rm', '-rf', fixtureRoot);
	} catch (error) { cleanupFailures.push(new Error('Ticket-owned destination cleanup failed', { cause: error })); }
	try {
		assert.match(adbArgs('shell', 'pm', 'clear', packageName), /Success/);
	} catch (error) { cleanupFailures.push(new Error('Application data cleanup failed', { cause: error })); }
	try {
		adbArgs('shell', 'sm', 'set-virtual-disk', 'false');
		await waitFor(() => adbArgs('shell', 'sm', 'list-disks') === '', 'virtual storage cleanup', 30_000);
	} catch (error) { cleanupFailures.push(new Error('Virtual storage cleanup failed', { cause: error })); }
	try {
		if (forwardInstalled) adbArgs('forward', '--remove', `tcp:${hostPort}`);
		forwardInstalled = false;
	} catch (error) { cleanupFailures.push(new Error('ADB forwarding cleanup failed', { cause: error })); }
	try {
		const teardownEvidence = {
			daemonStopped: adbArgs('shell', `pidof '${packageName}' || true`) === '',
			listenerAbsent: !listenerActive(),
			forwardAbsent: !adbArgs('forward', '--list').includes(`tcp:${hostPort} tcp:${devicePort}`),
			fixtureAbsent: adbArgs(
				'shell',
				`if [ -e ${shellSingleQuote(fixtureRoot)} ]${removableFixtureRoot ? ` || [ -e ${shellSingleQuote(removableFixtureRoot)} ]` : ''}; then echo present; fi`
			) === '',
			privateStateAbsent: privateStateIsAbsent(),
			virtualStorageDisabled: adbArgs('shell', 'sm', 'list-disks') === ''
		};
		assertTeardownComplete(teardownEvidence);
		if (evidence) evidence.teardown = teardownEvidence;
	} catch (error) { cleanupFailures.push(new Error('Independent teardown audit failed', { cause: error })); }
}

if (primaryFailure) {
	if (cleanupFailures.length) primaryFailure.cleanupFailures = cleanupFailures;
	throw primaryFailure;
}
if (cleanupFailures.length) throw new AggregateError(cleanupFailures, 'M6 interruption teardown failed.');
console.log(JSON.stringify(evidence));
