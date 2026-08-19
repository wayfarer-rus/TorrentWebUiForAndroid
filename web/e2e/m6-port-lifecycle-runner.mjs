import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createAndroidUi } from './m4-android-ui.mjs';
import {
	assertHostForwardAvailable,
	assertSensitiveValuesAbsent
} from './m6-clean-install-policy.mjs';
import {
	assertPortLifecycleCoverage,
	assertPortTeardown,
	assertPortTransition
} from './m6-port-lifecycle-policy.mjs';
import {
	createAdbRunner,
	replaceVisibleAndroidTextField,
	seedRunAsFile,
	shellSingleQuote,
	tapVisibleAndroidAction,
	waitFor
} from './m6-real-apk-tools.mjs';
import { COMPLETED_ONBOARDING_RECORD } from './onboarding-private-state-fixture.mjs';

const e2eDirectory = fileURLToPath(new URL('.', import.meta.url));
const repositoryRoot = resolve(e2eDirectory, '../..');
const packageName = 'com.andreiefimov.torrentwebui';
const activityName = `${packageName}/.MainActivity`;
const adb = process.env.ADB ?? `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;
const apk = resolve(repositoryRoot, 'app/build/outputs/apk/debug/app-debug.apk');
const defaultPort = 8080;
const switchedPort = Number(process.env.M6_SWITCHED_PORT ?? 18084);
const occupiedPort = Number(process.env.M6_OCCUPIED_PORT ?? 18085);
const persistencePort = Number(process.env.M6_PERSISTENCE_PORT ?? 18086);
const coldUnavailablePort = Number(process.env.M6_COLD_UNAVAILABLE_PORT ?? 18087);
const defaultHostPort = String(process.env.M6_DEFAULT_HOST_PORT ?? 18090);
const switchedHostPort = String(process.env.M6_SWITCHED_HOST_PORT ?? 18091);
const defaultPassword = 'start123';
const queueId = 'm6-ticket-10-queue';
const magnet = 'magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef&dn=m6-port-intent';
const fixtureName = `TorrentWebUi-M6-10-${process.pid}`;

const adbRun = createAdbRunner(adb);
const authorization = `Basic ${Buffer.from(`browser:${defaultPassword}`).toString('base64')}`;

assert(existsSync(apk), `Debug APK is missing: ${apk}`);
const devices = adbRun('devices').split('\n').slice(1)
	.map((line) => line.trim().split(/\s+/))
	.filter((parts) => parts[1] === 'device');
assert.equal(devices.length, 1, `Expected exactly one connected Android device, found ${devices.length}.`);
const serial = process.env.ADB_SERIAL ?? devices[0][0];
assert.equal(serial, devices[0][0], `ADB_SERIAL ${serial} is not the connected device.`);
const adbArgs = (...args) => adbRun('-s', serial, ...args);
assert.equal(adbArgs('shell', 'getprop', 'ro.kernel.qemu'), '1', 'Port acceptance refuses physical devices.');
assert.equal(adbArgs('shell', 'getprop', 'ro.boot.qemu.avd_name'), 'emulator_skill', 'The isolated emulator_skill AVD is required.');

const androidUi = createAndroidUi({ adb, serial, packageName });
const externalStorage = adbArgs('shell', 'readlink', '-f', adbArgs('shell', 'printenv', 'EXTERNAL_STORAGE'));
const fixtureRoot = `${externalStorage}/Download/${fixtureName}`;
const destination = `${fixtureRoot}/downloads`;
const appProcessIds = new Set();
const checks = {};
const cleanupFailures = [];
let browser;
let browserContext;
let defaultForwardInstalled = false;
let switchedForwardInstalled = false;
let occupiedPid;
let coldOccupiedPid;
let primaryFailure;
let evidence;

function listenerActive(port) {
	return new RegExp(`:${port}\\s`).test(adbArgs('shell', 'ss', '-tln'));
}

function recordAppProcessIds() {
	const output = adbArgs('shell', `pidof '${packageName}' || true`);
	assert(output, 'The application process is not running.');
	output.split(/\s+/).filter(Boolean).forEach((pid) => appProcessIds.add(pid));
}

function recordedAppLogs() {
	return [...appProcessIds].map((pid) => adbArgs('logcat', '-d', `--pid=${pid}`)).join('\n');
}

async function request(hostPort, path, options = {}) {
	return fetch(`http://127.0.0.1:${hostPort}${path}`, {
		...options,
		headers: { Authorization: authorization, ...(options.headers ?? {}) },
		signal: options.signal ?? AbortSignal.timeout(5_000)
	});
}

function installForward(hostPort, devicePort) {
	assertHostForwardAvailable(adbArgs('forward', '--list'), serial, hostPort);
	adbArgs('forward', '--no-rebind', `tcp:${hostPort}`, `tcp:${devicePort}`);
	if (devicePort === defaultPort) defaultForwardInstalled = true;
	if (devicePort === switchedPort) switchedForwardInstalled = true;
}

async function startAppOnExpectedPort(port) {
	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await androidUi.waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Torrent Daemon screen', 15_000);
	await new Promise((resolveDelay) => setTimeout(resolveDelay, 500));
	if (!listenerActive(port)) {
		let started = false;
		for (let attempt = 0; attempt < 3 && !started; attempt += 1) {
			await tapVisibleAndroidAction(androidUi, 'Start downloads');
			try {
				await waitFor(() => listenerActive(port), `Ktor listener on ${port}`, 10_000);
				started = true;
			} catch {
				androidUi.run('shell', 'am', 'start', '--activity-reorder-to-front', '-n', activityName);
			}
		}
		assert(started, `Ktor listener on ${port} did not start after three visible Android retries.`);
	}
	recordAppProcessIds();
}

async function stopDaemon(hostPort) {
	const response = await request(hostPort, '/api/daemon/stop', { method: 'POST' });
	assert(response.ok, `Daemon stop failed with HTTP ${response.status}.`);
	await waitFor(
		() => !listenerActive(defaultPort) && !listenerActive(switchedPort),
		'daemon and WebUI listener shutdown',
		15_000
	);
}

function portPreferences(port) {
	return `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<int name="configured_port" value="${port}" />\n</map>\n`;
}

function queueContent() {
	return JSON.stringify({
		version: 2,
		entries: [{
			magnetUri: magnet,
			isPaused: true,
			destinationPath: destination,
			queueId,
			storagePauseRequired: false
		}]
	});
}

function catalogContent() {
	return `@latest|${destination}\n${destination}|1`;
}

function clearOwnedPrivateState() {
	androidUi.run('shell', 'am', 'force-stop', packageName);
	adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		'rm -rf files/* shared_prefs/* databases/* no_backup/* cache/* code_cache/*; mkdir -p files shared_prefs'
	);
}

function privateStateIsAbsent() {
	const remaining = adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		[
			'files/queue_intent.json',
			'files/destination_catalog.txt',
			'shared_prefs/consumer_onboarding.xml',
			'shared_prefs/webui_auth.xml',
			'shared_prefs/webui_port_prefs.xml'
		].map((path) => `[ -e '${path}' ] && echo '${path}'`).join('; ') + '; true'
	);
	return remaining === '';
}

async function portHierarchy(configured, effective, errorPattern) {
	const status = new RegExp(
		`text="Configured"[\\s\\S]*?text="${configured}"[\\s\\S]*?text="Effective"[\\s\\S]*?text="${effective}"`
	);
	let statusObserved = false;
	let errorObserved = !errorPattern;
	let lastHierarchy = '';
	try {
		return await waitFor(async () => {
			lastHierarchy = await androidUi.hierarchy();
			statusObserved ||= status.test(lastHierarchy);
			errorObserved ||= Boolean(errorPattern?.test(lastHierarchy));
			if (statusObserved && errorObserved) return lastHierarchy;
			androidUi.scrollBidirectionally(lastHierarchy);
			return false;
		}, `Android WebUI Port status ${configured}/${effective}`, 30_000);
	} catch (error) {
		throw new Error(
			`Android WebUI Port evidence incomplete: status=${statusObserved} error=${errorObserved}; hierarchy=${lastHierarchy.slice(0, 1_000)}`,
			{ cause: error }
		);
	}
}

async function applyPort(input) {
	await replaceVisibleAndroidTextField(androidUi, String(input));
	androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_BACK');
	await tapVisibleAndroidAction(androidUi, 'Apply WebUI Port');
}

function startOccupiedListener(port) {
	const pid = adbArgs(
		'shell',
		`nc -l -p ${port} sleep 300 >/dev/null 2>&1 & echo $!`
	);
	assert.match(pid, /^\d+$/, `Unable to start the owned occupied-port listener on ${port}.`);
	return waitFor(() => listenerActive(port), `owned occupied listener on ${port}`).then(() => pid);
}

function stopOccupiedListener(pid, port) {
	if (pid) adbArgs('shell', 'kill', pid);
	return waitFor(() => !listenerActive(port), `owned occupied listener cleanup on ${port}`, 10_000);
}

async function assertDurableQueueReachable(hostPort, runtimeId) {
	const response = await request(hostPort, '/api/torrents');
	assert(response.ok, `Authenticated queue read failed with HTTP ${response.status}.`);
	const queue = await response.json();
	assert.equal(queue.length, 1, 'The previous authenticated server lost the durable transfer intent.');
	assert.equal(queue[0].queueId, queueId);
	assert.equal(queue[0].id, runtimeId);
	return true;
}

async function openBrowserPage(hostPort) {
	const page = await browserContext.newPage();
	await page.goto(`http://127.0.0.1:${hostPort}`, { waitUntil: 'domcontentloaded' });
	await page.getByRole('heading', { name: 'Add Download' }).waitFor();
	await page.evaluate(() => new Promise((resolve, reject) => {
		const socket = new WebSocket(`ws://${location.host}/ws/progress`);
		window.__m6PortSocket = { socket, opened: false, closed: false, frames: [] };
		const timeout = setTimeout(() => reject(new Error('Authenticated WebSocket did not open.')), 5_000);
		socket.onopen = () => {
			window.__m6PortSocket.opened = true;
			clearTimeout(timeout);
			resolve();
		};
		socket.onmessage = (event) => window.__m6PortSocket.frames.push(JSON.parse(event.data));
		socket.onclose = () => { window.__m6PortSocket.closed = true; };
		socket.onerror = () => {};
	}));
	await waitFor(
		() => page.evaluate(() => window.__m6PortSocket.frames.some((frame) => frame.type === 'torrents')),
		`authenticated WebSocket snapshot through host port ${hostPort}`
	);
	return page;
}

function persistedPort() {
	const xml = adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		'cat shared_prefs/webui_port_prefs.xml 2>/dev/null || true'
	);
	return Number(xml.match(/name="configured_port" value="(\d+)"/)?.[1] ?? defaultPort);
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
	try {
		await androidUi.setCurrentAllFilesAccess(true);
	} catch {
		await androidUi.grantNotificationIfRequested();
		await androidUi.setCurrentAllFilesAccess(true);
	}
	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await waitFor(() => listenerActive(defaultPort), 'initial default Ktor listener', 30_000);
	installForward(defaultHostPort, defaultPort);
	await waitFor(async () => (await request(defaultHostPort, '/health')).ok, 'initial authenticated health');
	await stopDaemon(defaultHostPort);

	clearOwnedPrivateState();
	adbArgs('shell', 'mkdir', '-p', destination);
	assert.equal(adbArgs('shell', 'readlink', '-f', destination), destination);
	seedRunAsFile(adbArgs, packageName, 'files/queue_intent.json', queueContent());
	seedRunAsFile(adbArgs, packageName, 'files/destination_catalog.txt', catalogContent());
	seedRunAsFile(adbArgs, packageName, 'shared_prefs/consumer_onboarding.xml', COMPLETED_ONBOARDING_RECORD);
	adbArgs('logcat', '-c');
	await startAppOnExpectedPort(defaultPort);
	await waitFor(async () => (await request(defaultHostPort, '/health')).ok, 'scenario authenticated health');
	checks.defaultStatus = Boolean(await portHierarchy(defaultPort, defaultPort));

	const unauthenticatedDefault = await fetch(`http://127.0.0.1:${defaultHostPort}/`, { signal: AbortSignal.timeout(5_000) });
	assert.equal(unauthenticatedDefault.status, 401, 'Default listener accepted unauthenticated WebUI access.');
	const beforeQueue = await (await request(defaultHostPort, '/api/torrents')).json();
	assert.equal(beforeQueue.length, 1, 'Seeded durable transfer intent was not restored.');
	assert.equal(beforeQueue[0].queueId, queueId);
	const runtimeId = beforeQueue[0].id;

	browser = await chromium.launch({ headless: process.env.M6_HEADED !== 'true' });
	browserContext = await browser.newContext({ httpCredentials: { username: 'browser', password: defaultPassword } });
	const oldPage = await openBrowserPage(defaultHostPort);

	await applyPort(switchedPort);
	await waitFor(() => listenerActive(switchedPort) && !listenerActive(defaultPort), 'atomic listener promotion');
	installForward(switchedHostPort, switchedPort);
	await portHierarchy(switchedPort, switchedPort);
	await waitFor(
		() => oldPage.evaluate(() => window.__m6PortSocket.closed),
		'old WebSocket closure after listener retirement'
	);
	await assert.rejects(
		fetch(`http://127.0.0.1:${defaultHostPort}/health`, { signal: AbortSignal.timeout(2_000) }),
		'Old host forwarding remained reachable after the port switch.'
	);

	const newPage = await openBrowserPage(switchedHostPort);
	const unauthenticatedSwitched = await fetch(`http://127.0.0.1:${switchedHostPort}/`, { signal: AbortSignal.timeout(5_000) });
	assert.equal(unauthenticatedSwitched.status, 401, 'Switched listener accepted unauthenticated WebUI access.');
	const afterQueue = await (await request(switchedHostPort, '/api/torrents')).json();
	assert.equal(afterQueue.length, 1);
	const nativeSessionInitializationCount = recordedAppLogs()
		.split('Native torrent session initialized').length - 1;
	const transition = {
		configuredPort: switchedPort,
		effectivePort: switchedPort,
		oldListenerActive: listenerActive(defaultPort),
		newListenerActive: listenerActive(switchedPort),
		queueIdentityPreserved: afterQueue[0].queueId === queueId,
		runtimeIdentityPreserved: afterQueue[0].id === runtimeId,
		nativeSessionInitializationCount
	};
	assertPortTransition(transition);
	checks.validSwitch = true;
	checks.sessionPreserved = nativeSessionInitializationCount === 1;
	checks.durableQueuePreserved = transition.queueIdentityPreserved && transition.runtimeIdentityPreserved;
	checks.browserReconnect = await newPage.getByRole('heading', { name: 'Add Download' }).isVisible();
	checks.webSocketReconnect = await newPage.evaluate(() => window.__m6PortSocket.opened);

	await applyPort(80);
	await portHierarchy(switchedPort, switchedPort, /Enter a WebUI port from 1024 to 65535\./);
	assert(listenerActive(switchedPort));
	checks.invalidInputRollback = await assertDurableQueueReachable(switchedHostPort, runtimeId);

	occupiedPid = await startOccupiedListener(occupiedPort);
	await applyPort(occupiedPort);
	await portHierarchy(switchedPort, switchedPort, new RegExp(`Port ${occupiedPort} is unavailable`));
	assert(listenerActive(switchedPort));
	assert.equal(persistedPort(), switchedPort);
	checks.occupiedPortRollback = await assertDurableQueueReachable(switchedHostPort, runtimeId);
	await stopOccupiedListener(occupiedPid, occupiedPort);
	occupiedPid = undefined;

	adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		'chmod 500 shared_prefs; chmod 400 shared_prefs/webui_port_prefs.xml'
	);
	await applyPort(persistencePort);
	await portHierarchy(switchedPort, switchedPort, /Could not save WebUI port\./);
	assert(listenerActive(switchedPort));
	assert(!listenerActive(persistencePort));
	checks.persistenceRollback = await assertDurableQueueReachable(switchedHostPort, runtimeId);
	adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		'chmod 700 shared_prefs; chmod 600 shared_prefs/webui_port_prefs.xml'
	);
	assert.equal(persistedPort(), switchedPort);

	await browserContext.close();
	browserContext = undefined;
	await stopDaemon(switchedHostPort);
	androidUi.run('shell', 'am', 'force-stop', packageName);
	seedRunAsFile(adbArgs, packageName, 'shared_prefs/webui_port_prefs.xml', portPreferences(coldUnavailablePort));
	coldOccupiedPid = await startOccupiedListener(coldUnavailablePort);
	androidUi.run('shell', 'am', 'start', '-n', activityName);
	await androidUi.waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'cold-start unavailable port Android screen', 15_000);
	await tapVisibleAndroidAction(androidUi, 'Start downloads');
	recordAppProcessIds();
	checks.coldStartUnavailable = Boolean(
		await portHierarchy(coldUnavailablePort, 'Unavailable', new RegExp(`WebUI could not start on configured port ${coldUnavailablePort}`))
	);
	assert(!listenerActive(defaultPort));
	assert(!listenerActive(switchedPort));
	assert.equal(persistedPort(), coldUnavailablePort);

	await stopOccupiedListener(coldOccupiedPid, coldUnavailablePort);
	coldOccupiedPid = undefined;
	androidUi.run('shell', 'am', 'force-stop', packageName);
	seedRunAsFile(adbArgs, packageName, 'shared_prefs/webui_port_prefs.xml', portPreferences(defaultPort));
	await startAppOnExpectedPort(defaultPort);
	await portHierarchy(defaultPort, defaultPort);
	await stopDaemon(defaultHostPort);

	assertPortLifecycleCoverage({ checks });
	const logs = recordedAppLogs();
	assertSensitiveValuesAbsent(logs, [defaultPassword, authorization, magnet, destination]);
	evidence = {
		scenario: 'M6 real-APK WebUI Port lifecycle',
		environment: 'emulator-only',
		serial,
		apiLevel: adbArgs('shell', 'getprop', 'ro.build.version.sdk'),
		abi: adbArgs('shell', 'getprop', 'ro.product.cpu.abi'),
		ports: { defaultPort, switchedPort, occupiedPort, persistencePort, coldUnavailablePort },
		checks,
		transition,
		physicalLanClaim: false
	};
} catch (error) {
	primaryFailure = error;
} finally {
	try {
		adbArgs(
			'exec-out', 'run-as', packageName, 'sh', '-c',
			'chmod 700 shared_prefs; chmod 600 shared_prefs/webui_port_prefs.xml 2>/dev/null || true'
		);
	} catch { /* App may not be installed yet. */ }
	try { if (occupiedPid) await stopOccupiedListener(occupiedPid, occupiedPort); } catch (error) { cleanupFailures.push(error); }
	try { if (coldOccupiedPid) await stopOccupiedListener(coldOccupiedPid, coldUnavailablePort); } catch (error) { cleanupFailures.push(error); }
	try {
		if (listenerActive(switchedPort) && switchedForwardInstalled) await stopDaemon(switchedHostPort);
		else if (listenerActive(defaultPort) && defaultForwardInstalled) await stopDaemon(defaultHostPort);
		else androidUi.run('shell', 'am', 'force-stop', packageName);
	} catch (error) {
		androidUi.run('shell', 'am', 'force-stop', packageName);
		cleanupFailures.push(new Error('Daemon cleanup failed', { cause: error }));
	}
	try { await browserContext?.close(); } catch (error) { cleanupFailures.push(error); }
	try { await browser?.close(); } catch (error) { cleanupFailures.push(error); }
	try {
		androidUi.run('shell', 'am', 'force-stop', packageName);
		seedRunAsFile(adbArgs, packageName, 'shared_prefs/webui_port_prefs.xml', portPreferences(defaultPort));
	} catch (error) { cleanupFailures.push(new Error('Default port restoration failed', { cause: error })); }
	try { adbArgs('shell', 'rm', '-rf', fixtureRoot); } catch (error) { cleanupFailures.push(new Error('Fixture cleanup failed', { cause: error })); }
	try { assert.match(adbArgs('shell', 'pm', 'clear', packageName), /Success/); } catch (error) { cleanupFailures.push(new Error('Application data cleanup failed', { cause: error })); }
	try {
		if (defaultForwardInstalled) adbArgs('forward', '--remove', `tcp:${defaultHostPort}`);
		if (switchedForwardInstalled) adbArgs('forward', '--remove', `tcp:${switchedHostPort}`);
		defaultForwardInstalled = false;
		switchedForwardInstalled = false;
	} catch (error) { cleanupFailures.push(new Error('ADB forwarding cleanup failed', { cause: error })); }
	try {
		const teardown = {
			configuredDefault: persistedPort() === defaultPort,
			defaultListenerAbsent: !listenerActive(defaultPort),
			switchedListenerAbsent: !listenerActive(switchedPort) && !listenerActive(persistencePort) && !listenerActive(coldUnavailablePort),
			occupiedListenerAbsent: !listenerActive(occupiedPort),
			forwardsAbsent: !adbArgs('forward', '--list').includes(`tcp:${defaultHostPort}`) && !adbArgs('forward', '--list').includes(`tcp:${switchedHostPort}`),
			privateStateAbsent: privateStateIsAbsent(),
			fixtureAbsent: adbArgs('shell', `if [ -e ${shellSingleQuote(fixtureRoot)} ]; then echo present; fi`) === '',
			daemonStopped: adbArgs('shell', `pidof '${packageName}' || true`) === ''
		};
		assertPortTeardown(teardown);
		if (evidence) evidence.teardown = teardown;
	} catch (error) { cleanupFailures.push(new Error('Independent port teardown audit failed', { cause: error })); }
}

if (primaryFailure) {
	if (cleanupFailures.length) primaryFailure.cleanupFailures = cleanupFailures;
	throw primaryFailure;
}
if (cleanupFailures.length) throw new AggregateError(cleanupFailures, 'M6 port lifecycle teardown failed.');
console.log(JSON.stringify(evidence));
