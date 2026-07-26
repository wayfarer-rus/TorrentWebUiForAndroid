import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createAndroidUi } from './m4-android-ui.mjs';
import {
	assertCanonicalPathMatch,
	assertHostForwardAvailable,
	assertNoDefaultPasswordInRenderedContent,
	assertSensitiveValuesAbsent,
	assertTeardownComplete,
	assertWithinSetupBudget
} from './m6-clean-install-policy.mjs';

const e2eDirectory = fileURLToPath(new URL('.', import.meta.url));
const repositoryRoot = resolve(e2eDirectory, '../..');
const packageName = 'com.andreiefimov.torrentwebui';
const activityName = `${packageName}/.MainActivity`;
const devicePort = process.env.M6_DEVICE_PORT ?? '8080';
const hostPort = process.env.M6_HOST_PORT ?? '18082';
const adb = process.env.ADB ?? `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;
const apk = resolve(repositoryRoot, 'app/build/outputs/apk/debug/app-debug.apk');
const baseUrl = `http://127.0.0.1:${hostPort}`;
const defaultPassword = 'start123';
const authorization = `Basic ${Buffer.from(`browser:${defaultPassword}`).toString('base64')}`;

function adbRun(...args) {
	const command = args.join(' ');
	const forbidden = [
		new RegExp(['pm', 'grant'].join('\\s+'), 'i'),
		new RegExp(['app', 'ops'].join('\\s*'), 'i')
	];
	assert(forbidden.every((pattern) => !pattern.test(command)), 'Permission shell grants are forbidden.');
	return execFileSync(adb, args, {
		encoding: 'utf8',
		stdio: ['ignore', 'pipe', 'pipe']
	}).trim();
}

async function waitFor(check, description, timeoutMs = 30_000) {
	const deadline = Date.now() + timeoutMs;
	let lastError;
	while (Date.now() < deadline) {
		try {
			const result = await check();
			if (result) return result;
		} catch (error) {
			lastError = error;
		}
		await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
	}
	throw new Error(`Timed out waiting for ${description}`, { cause: lastError });
}

assert(existsSync(apk), `Debug APK is missing: ${apk}`);
const devices = adbRun('devices').split('\n').slice(1)
	.map((line) => line.trim().split(/\s+/))
	.filter((parts) => parts[1] === 'device');
assert.equal(devices.length, 1, `Expected exactly one connected Android device, found ${devices.length}.`);
const serial = process.env.ADB_SERIAL ?? devices[0][0];
assert.equal(serial, devices[0][0], `ADB_SERIAL ${serial} is not the connected device.`);
const adbArgs = (...args) => adbRun('-s', serial, ...args);
assert.equal(adbArgs('shell', 'getprop', 'ro.kernel.qemu'), '1', 'Clean-install acceptance refuses physical devices.');
assert.equal(adbArgs('shell', 'getprop', 'ro.boot.qemu.avd_name'), 'emulator_skill', 'The isolated emulator_skill AVD is required.');

const androidUi = createAndroidUi({ adb, serial, packageName });
const listenerPattern = new RegExp(`:${devicePort}\\s`);
let browser;
let browserContext;
let recommendedPath = null;
let primaryFailure = null;
const cleanupFailures = [];
let forwardInstalled = false;
let evidence = null;
const appProcessIds = new Set();

function listenerActive() {
	return listenerPattern.test(adbArgs('shell', 'ss', '-tln'));
}

async function authenticatedFetch(path, options = {}) {
	return fetch(`${baseUrl}${path}`, {
		...options,
		headers: { Authorization: authorization, ...(options.headers ?? {}) },
		signal: options.signal ?? AbortSignal.timeout(5_000)
	});
}

async function tapStartDownloadsInAndroidUi() {
	for (let attempt = 0; attempt < 6; attempt += 1) {
		const hierarchy = await androidUi.hierarchy();
		if (/<node[^>]*text="Start downloads"[^>]*enabled="true"[^>]*>/.test(hierarchy)) {
			await androidUi.tapText('Start downloads');
			return;
		}
		androidUi.run('shell', 'input', 'swipe', '540', '1900', '540', '650', '250');
		await new Promise((resolveDelay) => setTimeout(resolveDelay, 300));
	}
	assert.fail('Start downloads was not visible and enabled after scrolling the Android fallback UI.');
}

async function waitForStartedSessionInAndroidUi() {
	for (let attempt = 0; attempt < 8; attempt += 1) {
		const hierarchy = await androidUi.hierarchy();
		if (/text="Session started"[\s\S]*?text="yes"/.test(hierarchy)) return;
		androidUi.run('shell', 'input', 'swipe', '540', '650', '540', '1900', '250');
		await new Promise((resolveDelay) => setTimeout(resolveDelay, 300));
	}
	assert.fail('Android fallback UI did not report Session started=yes.');
}

function installOwnedForward() {
	assertHostForwardAvailable(adbArgs('forward', '--list'), serial, hostPort);
	adbArgs('forward', '--no-rebind', `tcp:${hostPort}`, `tcp:${devicePort}`);
	forwardInstalled = true;
}

function recordAppProcessIds() {
	const output = adbArgs('shell', `pidof '${packageName}' || true`);
	assert(output, 'The application process is not running.');
	output.split(/\s+/).filter(Boolean).forEach((pid) => appProcessIds.add(pid));
}

async function stopDaemonIfRunning() {
	if (!listenerActive()) return;
	if (!forwardInstalled) installOwnedForward();
	const response = await authenticatedFetch('/api/daemon/stop', { method: 'POST' });
	assert(response.ok, `Daemon stop failed with HTTP ${response.status}.`);
	await waitFor(() => !listenerActive(), 'daemon and Ktor shutdown', 15_000);
}

function assertRecommendedFixtureInitiallyAbsent() {
	const externalStorage = adbArgs('shell', 'printenv', 'EXTERNAL_STORAGE');
	assert(externalStorage.startsWith('/'), 'Android did not report an external storage root.');
	const canonicalRoot = adbArgs('shell', 'readlink', '-f', externalStorage);
	const candidate = `${canonicalRoot}/Download/Torrents`;
	const existing = adbArgs(
		'shell',
		`if [ -e '${candidate}' ]; then echo present; fi`
	);
	assert.equal(existing, '', `Recommended Destination fixture already exists: ${candidate}`);
	return candidate;
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

try {
	try { adbArgs('uninstall', packageName); } catch { /* Fresh emulator may not have the package. */ }
	assert.match(adbArgs('install', apk), /Success/, 'APK installation failed.');
	assert.match(adbArgs('shell', 'pm', 'clear', packageName), /Success/, 'Unable to clear application data.');
	adbArgs('logcat', '-c');
	const expectedRecommendedPath = assertRecommendedFixtureInitiallyAbsent();

	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await androidUi.waitForNode(
		/<node[^>]*(?:text="(?:Allow|ALLOW)"|text="Torrent Daemon")[^>]*>/,
		'first actionable Android Startup Bootstrap screen',
		15_000
	);
	const setupStartedAt = Date.now();
	await androidUi.grantNotificationIfRequested();
	await androidUi.setCurrentAllFilesAccess(true);
	await androidUi.waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Torrent Daemon screen');
	await androidUi.waitForNode(/<node[^>]*text="Ready"[^>]*>/, 'Ready storage state', 20_000);
	await waitFor(() => listenerActive(), 'default-port Ktor listener', 30_000);
	await waitForStartedSessionInAndroidUi();
	recordAppProcessIds();

	installOwnedForward();
	await waitFor(async () => {
		const response = await authenticatedFetch('/health');
		return response.ok;
	}, 'authenticated Ktor health');

	browser = await chromium.launch({ headless: process.env.M6_HEADED !== 'true' });
	browserContext = await browser.newContext({
		httpCredentials: { username: 'browser', password: defaultPassword }
	});
	const page = await browserContext.newPage();
	await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
	await page.getByRole('heading', { name: 'Consumer Onboarding' }).waitFor();
	const initialStatus = await page.evaluate(async () => (await fetch('/api/onboarding/status')).json());
	assert.equal(initialStatus.readiness, 'Ready', 'Browser did not observe Ready onboarding status.');
	assertNoDefaultPasswordInRenderedContent(await page.locator('body').innerText());

	recommendedPath = (await page.locator('main.onboarding-shell code').first().innerText()).trim();
	assert.equal(recommendedPath, expectedRecommendedPath, 'Backend recommendation differs from Android primary storage fixture path.');
	await page.getByRole('button', { name: 'Use this folder' }).click();
	await page.getByRole('heading', { name: 'Password choice' }).waitFor();
	const adbCanonicalPath = adbArgs('shell', 'readlink', '-f', recommendedPath);
	assertCanonicalPathMatch(recommendedPath, adbCanonicalPath);
	assertNoDefaultPasswordInRenderedContent(await page.locator('body').innerText());

	await page.getByRole('button', { name: 'Set it later' }).click();
	await page.getByRole('heading', { name: 'Add Torrent' }).waitFor();
	const setupCompletedAt = Date.now();
	const durationMs = assertWithinSetupBudget(setupStartedAt, setupCompletedAt);
	assertNoDefaultPasswordInRenderedContent(await page.locator('body').innerText());
	const completedStatus = await page.evaluate(async () => (await fetch('/api/onboarding/status')).json());
	assert.equal(completedStatus.completed, true);
	assert.equal(completedStatus.passwordDecision, 'deferred');

	await page.reload({ waitUntil: 'domcontentloaded' });
	await page.getByRole('heading', { name: 'Add Torrent' }).waitFor();
	assertNoDefaultPasswordInRenderedContent(await page.locator('body').innerText());

	const stopResponse = await page.evaluate(async () => {
		const response = await fetch('/api/daemon/stop', { method: 'POST' });
		return response.status;
	});
	assert.equal(stopResponse, 200, 'Browser could not stop the daemon for restart validation.');
	await waitFor(() => !listenerActive(), 'daemon stop before restart', 15_000);
	androidUi.run('shell', 'am', 'force-stop', packageName);
	await androidUi.launchApp();
	await tapStartDownloadsInAndroidUi();
	await waitFor(() => listenerActive(), 'Ktor listener after daemon restart', 30_000);
	await waitForStartedSessionInAndroidUi();
	recordAppProcessIds();
	await waitFor(async () => (await authenticatedFetch('/health')).ok, 'Ktor health after daemon restart');
	await page.reload({ waitUntil: 'domcontentloaded' });
	await page.getByRole('heading', { name: 'Add Torrent' }).waitFor();
	assertNoDefaultPasswordInRenderedContent(await page.locator('body').innerText());

	const appLogs = [...appProcessIds]
		.map((pid) => adbArgs('logcat', '-d', `--pid=${pid}`))
		.join('\n');
	assertSensitiveValuesAbsent(appLogs, [defaultPassword, recommendedPath]);

	evidence = {
		scenario: 'M6 clean-install Recommended Destination and password deferral',
		environment: 'emulator-only',
		serial,
		apiLevel: adbArgs('shell', 'getprop', 'ro.build.version.sdk'),
		abi: adbArgs('shell', 'getprop', 'ro.product.cpu.abi'),
		devicePort,
		durationMs,
		recommendedPath,
		canonicalPathMatched: true,
		completionSurvivedRefresh: true,
		completionSurvivedDaemonRestart: true,
		physicalLanClaim: false
	};
} catch (error) {
	primaryFailure = error;
} finally {
	try { await stopDaemonIfRunning(); } catch (error) { cleanupFailures.push(error); }
	try { await browserContext?.close(); } catch (error) { cleanupFailures.push(error); }
	try { await browser?.close(); } catch (error) { cleanupFailures.push(error); }
	try {
		if (recommendedPath) {
			adbArgs('shell', `if [ -d '${recommendedPath}' ]; then rmdir '${recommendedPath}'; fi`);
		}
	} catch (error) { cleanupFailures.push(new Error('Recommended Destination cleanup failed', { cause: error })); }
	try {
		assert.match(adbArgs('shell', 'pm', 'clear', packageName), /Success/);
	} catch (error) { cleanupFailures.push(new Error('Application data cleanup failed', { cause: error })); }
	try { adbArgs('shell', 'sm', 'set-virtual-disk', 'false'); } catch (error) { cleanupFailures.push(new Error('Virtual storage cleanup failed', { cause: error })); }
	try {
		if (forwardInstalled) adbArgs('forward', '--remove', `tcp:${hostPort}`);
		forwardInstalled = false;
	} catch (error) { cleanupFailures.push(new Error('ADB forwarding cleanup failed', { cause: error })); }
	try {
		const teardownEvidence = {
			daemonStopped: adbArgs('shell', `pidof '${packageName}' || true`) === '',
			listenerAbsent: !listenerActive(),
			forwardAbsent: !adbArgs('forward', '--list').includes(`tcp:${hostPort} tcp:${devicePort}`),
			fixtureAbsent: !recommendedPath || adbArgs('shell', `if [ -e '${recommendedPath}' ]; then echo present; fi`) === '',
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
if (cleanupFailures.length) throw new AggregateError(cleanupFailures, 'M6 clean-install teardown failed.');
console.log(JSON.stringify(evidence));
