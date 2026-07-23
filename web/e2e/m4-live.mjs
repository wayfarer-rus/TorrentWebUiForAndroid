import assert from 'node:assert/strict';
import { chromium } from '@playwright/test';
import { createAndroidUi } from './m4-android-ui.mjs';
import { loadOfficialTorrents } from './m4-official-torrents.mjs';

const baseUrl = process.env.WEBUI_BASE_URL ?? 'http://127.0.0.1:8081';
const password = process.env.WEBUI_PASSWORD;
const firstDestination = process.env.M4_FIRST_DESTINATION;
const secondDestination = process.env.M4_SECOND_DESTINATION;
const moveDestination = process.env.M4_MOVE_DESTINATION;
const conflictDestination = process.env.M4_CONFLICT_DESTINATION;
const partialDestination = process.env.M4_PARTIAL_DESTINATION;
const addValidDestination = process.env.M4_ADD_VALID_DESTINATION;
const addCorruptDestination = process.env.M4_ADD_CORRUPT_DESTINATION;
const addUnrelatedDestination = process.env.M4_ADD_UNRELATED_DESTINATION;
const interruptedDestination = process.env.M4_INTERRUPTED_DESTINATION;
const interruptedVolumeId = process.env.M4_INTERRUPTED_VOLUME_ID;
const stagedReusePayload = process.env.M4_STAGED_REUSE_PAYLOAD;
const fixtureMagnet = process.env.M4_FIXTURE_MAGNET;
const fixtureName = process.env.M4_FIXTURE_NAME;
const reuseMagnet = process.env.M4_REUSE_MAGNET;
const reuseName = process.env.M4_REUSE_NAME;
const partialMagnet = process.env.M4_PARTIAL_MAGNET;
const partialName = process.env.M4_PARTIAL_NAME;
const addValidMagnet = process.env.M4_ADD_VALID_MAGNET;
const addValidName = process.env.M4_ADD_VALID_NAME;
const addCorruptMagnet = process.env.M4_ADD_CORRUPT_MAGNET;
const addCorruptName = process.env.M4_ADD_CORRUPT_NAME;
const addUnrelatedMagnet = process.env.M4_ADD_UNRELATED_MAGNET;
const addUnrelatedName = process.env.M4_ADD_UNRELATED_NAME;
const interruptedMagnet = process.env.M4_INTERRUPTED_MAGNET;
const interruptedName = process.env.M4_INTERRUPTED_NAME;
const fixtureControlUrl = process.env.M4_FIXTURE_CONTROL_URL;
const adbObservedFirstDestination = process.env.M4_ADB_FIRST_DESTINATION;
const adbObservedSecondDestination = process.env.M4_ADB_SECOND_DESTINATION;
const adbObservedMoveDestination = process.env.M4_ADB_MOVE_DESTINATION;
const adbObservedConflictDestination = process.env.M4_ADB_CONFLICT_DESTINATION;
const adbObservedPartialDestination = process.env.M4_ADB_PARTIAL_DESTINATION;
const adbObservedAddValidDestination = process.env.M4_ADB_ADD_VALID_DESTINATION;
const adbObservedAddCorruptDestination = process.env.M4_ADB_ADD_CORRUPT_DESTINATION;
const adbObservedAddUnrelatedDestination = process.env.M4_ADB_ADD_UNRELATED_DESTINATION;
const adbObservedInterruptedDestination = process.env.M4_ADB_INTERRUPTED_DESTINATION;
const runOfficialTorrentSmoke = process.env.M4_OFFICIAL_TORRENT_SMOKE === 'true';
const officialDestinationsJson = process.env.M4_OFFICIAL_DESTINATIONS;
const adbOfficialDestinationsJson = process.env.M4_ADB_OFFICIAL_DESTINATIONS;
let officialDestinations = {};
let adbOfficialDestinations = {};
const addedTorrentIds = [];
const approvedDestinations = [];
const cleanupFailures = [];
const basicAuthorization = `Basic ${Buffer.from(`:${password}`).toString('base64')}`;
const moveGateHold = '.torrentwebui-m4-move-hold';
const moveGateReached = '.torrentwebui-m4-move-reached';
const moveGateRelease = '.torrentwebui-m4-move-release';
const officialPayloadLimitKiB = 8 * 1024;
const officialPauseTriggerKiB = 512;
const officialResumeWindowMs = 1_000;
const officialSizePollMs = 25;
let androidUi = null;
let browser = null;
let context = null;
let page = null;

async function apiResponse(path, options = {}) {
	const response = await page.request.fetch(`${baseUrl}${path}`, options);
	const text = await response.text();
	let body = null;
	try { body = text ? JSON.parse(text) : null; } catch { body = text; }
	return { response, body };
}

async function api(path, options = {}) {
	const { response, body } = await apiResponse(path, options);
	assert(response.ok(), `${options.method ?? 'GET'} ${path} failed (${response.status()}): ${JSON.stringify(body)}`);
	return body;
}

async function poll(check, description, timeoutMs = 30_000) {
	const deadline = Date.now() + timeoutMs;
	let lastValue;
	while (Date.now() < deadline) {
		lastValue = await check();
		if (lastValue) return lastValue;
		await new Promise((resolve) => setTimeout(resolve, 250));
	}
	assert.fail(`Timed out waiting for ${description}; last value=${JSON.stringify(lastValue)}`);
}

function officialDestinationSizeKiB(label, destinationPath) {
	const sizeKiB = Number.parseInt(androidUi.run('shell', 'du', '-sk', destinationPath).split(/\s+/)[0], 10);
	assert(Number.isFinite(sizeKiB), `${label} destination size could not be measured.`);
	return sizeKiB;
}

async function resumeOfficialWithinPayloadBound(entry) {
	const failures = [];
	const pauseButton = entry.card.getByRole('button', { name: /Pause/ });
	let peakSizeKiB = officialDestinationSizeKiB(entry.source.label, entry.destinationPath);
	let activeSince = null;

	try {
		await entry.card.getByRole('button', { name: /Resume/ }).click();
		const resumeObservationDeadline = Date.now() + 5_000;
		while (Date.now() < resumeObservationDeadline) {
			const sizeKiB = officialDestinationSizeKiB(entry.source.label, entry.destinationPath);
			peakSizeKiB = Math.max(peakSizeKiB, sizeKiB);
			assert(sizeKiB <= officialPayloadLimitKiB,
				`${entry.source.label} exceeded the 8 MiB smoke-test payload bound while resumed.`);
			if (await pauseButton.isVisible()) {
				activeSince ??= Date.now();
				if (sizeKiB >= officialPauseTriggerKiB || Date.now() - activeSince >= officialResumeWindowMs) break;
			}
			await new Promise((resolve) => setTimeout(resolve, officialSizePollMs));
		}
		assert(activeSince !== null, `${entry.source.label} never exposed an active state after resume.`);
	} catch (error) {
		failures.push(error);
	}

	try {
		await pauseButton.click({ timeout: 5_000 });
	} catch (error) {
		failures.push(new Error(`${entry.source.label} could not be re-paused through the WebUI.`, { cause: error }));
		try {
			await api(`/api/torrents/${entry.result.id}/pause`, { method: 'PUT' });
		} catch (fallbackError) {
			failures.push(new Error(`${entry.source.label} failure-closed API pause also failed.`, { cause: fallbackError }));
		}
	}

	try {
		await poll(async () => {
			const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === entry.result.id);
			return torrent && ['paused', 'pause_requested'].includes(torrent.state) ? torrent : null;
		}, `official ${entry.source.label} re-pause`);
		const finalSizeKiB = officialDestinationSizeKiB(entry.source.label, entry.destinationPath);
		peakSizeKiB = Math.max(peakSizeKiB, finalSizeKiB);
		assert(finalSizeKiB <= officialPayloadLimitKiB,
			`${entry.source.label} exceeded the 8 MiB smoke-test payload bound after re-pause.`);
	} catch (error) {
		failures.push(error);
	}

	if (failures.length) throw new AggregateError(failures, `${entry.source.label} bounded resume failed.`);
	return peakSizeKiB;
}

async function assertUnauthenticatedWebSocketRejected() {
	const socketUrl = baseUrl.replace(/^http/, 'ws') + '/ws/progress';
	await new Promise((resolve, reject) => {
		const socket = new WebSocket(socketUrl);
		let settled = false;
		const finish = (callback) => {
			if (settled) return;
			settled = true;
			clearTimeout(timeout);
			callback();
		};
		const timeout = setTimeout(() => finish(() => reject(new Error('Unauthenticated WebSocket handshake did not finish.'))), 5_000);
		socket.addEventListener('open', () => finish(() => {
			socket.close();
			reject(new Error('Unauthenticated WebSocket handshake unexpectedly succeeded.'));
		}));
		socket.addEventListener('error', () => finish(resolve));
		socket.addEventListener('close', () => finish(resolve));
	});
}

async function startAuthenticatedWebSocketObserver() {
	await page.evaluate(() => new Promise((resolve, reject) => {
		const previous = window.__m4WebSocketObserver;
		if (previous?.socket) previous.socket.close();
		const observer = { frames: [], parseErrors: [], socket: null };
		const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
		const socket = new WebSocket(`${protocol}://${location.host}/ws/progress`);
		observer.socket = socket;
		window.__m4WebSocketObserver = observer;
		const timeout = setTimeout(() => reject(new Error('Authenticated WebSocket did not open.')), 5_000);
		socket.onopen = () => {
			clearTimeout(timeout);
			resolve();
		};
		socket.onmessage = (event) => {
			try { observer.frames.push(JSON.parse(event.data)); }
			catch (error) { observer.parseErrors.push(String(error)); }
		};
		socket.onerror = () => {};
	}));
	await poll(async () => page.evaluate(() =>
		window.__m4WebSocketObserver?.frames.some((frame) => frame.type === 'torrents')
	), 'first authenticated WebSocket snapshot');
}

async function waitForWebSocketTorrent(torrentId, predicate, description) {
	return poll(async () => {
		const state = await page.evaluate((id) => {
			const observer = window.__m4WebSocketObserver;
			const snapshots = (observer?.frames ?? []).filter((frame) => frame.type === 'torrents');
			const torrent = snapshots.flatMap((frame) => frame.data ?? []).filter((item) => item.id === id).at(-1) ?? null;
			return { torrent, parseErrors: observer?.parseErrors ?? [] };
		}, torrentId);
		assert.deepEqual(state.parseErrors, [], 'A WebSocket frame was not one valid JSON value.');
		return state.torrent && predicate(state.torrent) ? state.torrent : null;
	}, description, 45_000);
}

async function cleanupStaleOwnedState() {
	const torrents = await api('/api/torrents');
	for (const torrent of torrents) {
		if (torrent.destinationPath?.includes('TorrentWebUi-M4-')) {
			const response = await page.request.fetch(`${baseUrl}/api/torrents/${torrent.id}?deleteFiles=true`, { method: 'DELETE' });
			assert(response.ok(), `Unable to remove stale owned torrent ${torrent.id}: HTTP ${response.status()}`);
		}
	}
	const catalog = await api('/api/storage/catalog');
	for (const destination of catalog.filter((path) => path.includes('TorrentWebUi-M4-'))) {
		const response = await page.request.fetch(`${baseUrl}/api/storage/destinations/${encodeURIComponent(destination)}`, { method: 'DELETE' });
		assert(response.ok() || response.status() === 404, `Unable to remove stale owned destination: HTTP ${response.status()}`);
	}
}

async function approveDestination(path) {
	if (await page.getByRole('button', { name: 'Choose another folder' }).count()) {
		await page.getByRole('button', { name: 'Choose another folder' }).click();
	}
	await page.locator('#destination-path').fill(path);
	const validationResponse = page.waitForResponse((response) =>
		response.url().endsWith('/api/storage/validate') && response.request().method() === 'POST'
	);
	await page.locator('.paste-path').getByRole('button', { name: 'Check folder' }).click();
	assert.equal((await validationResponse).status(), 200, `The backend rejected ${path}.`);
	const storageReload = page.waitForResponse((response) =>
		response.url().endsWith('/api/storage/latest-selected') && response.request().method() === 'GET'
	);
	await page.getByRole('button', { name: 'Use this folder' }).click();
	await storageReload;
	await page.getByText(`Downloads will be saved to ${path}`).waitFor();
	if (!approvedDestinations.includes(path)) approvedDestinations.push(path);
}

async function addMagnet(magnet, expectedName, expectedStatus = 'ok') {
	const addResponse = page.waitForResponse((response) =>
		response.url().endsWith('/api/torrents/magnet') && response.request().method() === 'POST'
	);
	await page.locator('.add-torrent input').fill(magnet);
	await page.locator('.add-torrent').getByRole('button', { name: 'Add' }).click();
	const response = await addResponse;
	const result = await response.json();
	assert.equal(response.status(), 200, `Fixture add failed: ${JSON.stringify(result)}`);
	assert.equal(result.status, expectedStatus);
	assert.equal(typeof result.id, 'number', 'The server did not return a native runtime ID.');
	addedTorrentIds.push(result.id);
	const card = page.locator('.torrent-card').filter({ hasText: expectedName }).first();
	await card.waitFor();
	return { result, card };
}

async function addPausedOfficial(source, destinationPath) {
	const response = await page.request.fetch(`${baseUrl}/api/torrents/magnet`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		data: { magnet: source.magnet, destinationPath, startPaused: true },
		timeout: 10_000
	});
	let result = null;
	try { result = await response.json(); } catch { /* status-only failure below */ }
	if (typeof result?.id === 'number') addedTorrentIds.push(result.id);
	assert.equal(response.status(), 200, `${source.label} paused add failed with HTTP ${response.status()}.`);
	assert.equal(result?.status, 'ok', `${source.label} paused add did not return ok.`);
	assert.equal(typeof result?.id, 'number', `${source.label} paused add returned no native runtime ID.`);
	return { source, destinationPath, result };
}

async function removeTorrentAndWait(torrentId) {
	const response = await page.request.fetch(`${baseUrl}/api/torrents/${torrentId}?deleteFiles=true`, { method: 'DELETE' });
	assert(response.ok() || response.status() === 404, `Torrent removal failed with HTTP ${response.status()}.`);
	await poll(async () => !(await api('/api/torrents')).some((torrent) => torrent.id === torrentId), `torrent ${torrentId} removal`);
	const trackedIndex = addedTorrentIds.indexOf(torrentId);
	if (trackedIndex >= 0) addedTorrentIds.splice(trackedIndex, 1);
}

async function waitForTorrentComplete(torrentId, timeoutMs = 60_000) {
	return poll(async () => {
		const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === torrentId);
		return torrent && torrent.progress >= 0.999 ? torrent : null;
	}, `deterministic fixture ${torrentId} to verify as complete`, timeoutMs);
}

async function waitForCompletedMove(torrentId, expectedDestination, timeoutMs = 45_000) {
	return poll(async () => {
		const status = await api(`/api/torrents/${torrentId}/move/status`);
		const destination = await api(`/api/torrents/${torrentId}/destination`);
		assert.notEqual(status.phase, 'move-interrupted', 'The deterministic completed-move fixture was interrupted.');
		assert.notEqual(status.phase, 'storage-conflict', 'The deterministic completed-move fixture entered storage conflict.');
		if (status.phase === 'none' && destination.canonicalPath === expectedDestination) {
			assert.equal(destination.path, expectedDestination);
			return destination;
		}
		return null;
	}, `move completion at ${expectedDestination}`, timeoutMs);
}

let primaryFailure = null;
try {
	for (const [name, value] of Object.entries({
		password, firstDestination, secondDestination, moveDestination, conflictDestination, partialDestination,
		addValidDestination, addCorruptDestination, addUnrelatedDestination, interruptedDestination,
		interruptedVolumeId, stagedReusePayload, fixtureMagnet, fixtureName, reuseMagnet, reuseName,
		partialMagnet, partialName, addValidMagnet, addValidName, addCorruptMagnet, addCorruptName,
		addUnrelatedMagnet, addUnrelatedName, interruptedMagnet, interruptedName, fixtureControlUrl
	})) assert(value, `${name} must be set by the host runner.`);
	assert.equal(firstDestination, adbObservedFirstDestination, 'The first API path differs from the ADB-observed canonical path.');
	assert.equal(secondDestination, adbObservedSecondDestination, 'The second API path differs from the ADB-observed canonical path.');
	assert.equal(moveDestination, adbObservedMoveDestination, 'The move API path differs from the ADB-observed canonical path.');
	assert.equal(conflictDestination, adbObservedConflictDestination, 'The conflict API path differs from the ADB-observed canonical path.');
	assert.equal(partialDestination, adbObservedPartialDestination, 'The partial API path differs from the ADB-observed canonical path.');
	assert.equal(addValidDestination, adbObservedAddValidDestination, 'The add-reuse API path differs from ADB.');
	assert.equal(addCorruptDestination, adbObservedAddCorruptDestination, 'The add-conflict API path differs from ADB.');
	assert.equal(addUnrelatedDestination, adbObservedAddUnrelatedDestination, 'The add-unrelated API path differs from ADB.');
	assert.equal(interruptedDestination, adbObservedInterruptedDestination, 'The removable API path differs from the ADB-observed canonical path.');
	if (runOfficialTorrentSmoke) {
		assert(officialDestinationsJson, 'M4_OFFICIAL_DESTINATIONS must be set for the optional official smoke.');
		assert(adbOfficialDestinationsJson, 'M4_ADB_OFFICIAL_DESTINATIONS must be set for the optional official smoke.');
		officialDestinations = JSON.parse(officialDestinationsJson);
		adbOfficialDestinations = JSON.parse(adbOfficialDestinationsJson);
		assert.deepEqual(officialDestinations, adbOfficialDestinations, 'Official-smoke API paths differ from ADB-observed canonical paths.');
	}

	androidUi = createAndroidUi({
		adb: process.env.M4_ADB,
		serial: process.env.M4_ADB_SERIAL,
		packageName: 'com.andreiefimov.torrentwebui'
	});
	browser = await chromium.launch({ headless: true });
	context = await browser.newContext({ httpCredentials: { username: '', password } });
	page = await context.newPage();

	assert.equal((await api('/health')).status, 'ok');
	await assertUnauthenticatedWebSocketRejected();
	await cleanupStaleOwnedState();
	assert.deepEqual(await api('/api/torrents'), [], 'M4 acceptance requires an isolated empty queue.');

	await page.goto(baseUrl, { waitUntil: 'networkidle' });
	await page.getByRole('heading', { name: 'Torrent WebUI' }).waitFor();
	await startAuthenticatedWebSocketObserver();
	await page.getByRole('heading', { name: 'Download folder' }).waitFor();
	assert.equal((await api('/api/storage/permission')).state, 'Ready');

	const volumes = await api('/api/storage/volumes');
	assert(volumes.some((volume) => firstDestination.startsWith(`${volume.path}/`) || firstDestination === volume.path));
	assert(volumes.some((volume) => interruptedDestination.startsWith(`${volume.path}/`) || interruptedDestination === volume.path));
	const rejectedUri = await api('/api/storage/validate', {
		method: 'POST', headers: { 'Content-Type': 'application/json' },
		data: { path: 'content://com.android.externalstorage/documents/test' }
	});
	assert.equal(rejectedUri.isValid, false, 'The API accepted a SAF document URI.');

	if (runOfficialTorrentSmoke) {
		const officialTorrents = await loadOfficialTorrents();
		assert.deepEqual(
			officialTorrents.map((source) => source.label).sort(),
			Object.keys(officialDestinations).sort(),
			'Official source and destination matrices differ.'
		);
		for (const destination of Object.values(officialDestinations)) {
			if (!approvedDestinations.includes(destination)) approvedDestinations.push(destination);
		}
		const archSource = officialTorrents.find((source) => source.label === 'Arch Linux');
		assert(archSource, 'The required official Arch source is absent.');
		await approveDestination(officialDestinations['Arch Linux']);
		await page.route('**/api/torrents/magnet', async (route) => {
			const body = route.request().postDataJSON();
			if (body.magnet !== archSource.magnet) return route.continue();
			await route.continue({ postData: JSON.stringify({ ...body, startPaused: true }) });
		});
		let arch;
		try {
			arch = await addMagnet(archSource.magnet, archSource.name);
		} finally {
			await page.unroute('**/api/torrents/magnet');
		}
		const companionResults = await Promise.allSettled(
			officialTorrents
				.filter((source) => source !== archSource)
				.map((source) => addPausedOfficial(source, officialDestinations[source.label]))
		);
		const companionFailures = companionResults.filter((result) => result.status === 'rejected');
		if (companionFailures.length) {
			throw new AggregateError(companionFailures.map((result) => result.reason), 'Paused official torrent adds failed.');
		}
		const officialEntries = [
			{ source: archSource, destinationPath: officialDestinations['Arch Linux'], result: arch.result },
			...companionResults.map((result) => result.value)
		];
		assert.equal(new Set(officialEntries.map((entry) => entry.result.id)).size, officialEntries.length, 'Official adds did not receive distinct runtime IDs.');
		await poll(async () => (await page.locator('.torrent-card').count()) === officialEntries.length, 'all official torrents to be visible together');
		for (const entry of officialEntries) {
			const snapshot = (await api('/api/torrents')).find((torrent) => torrent.id === entry.result.id);
			assert(snapshot, `${entry.source.label} is absent from the authenticated queue.`);
			assert.equal(snapshot.destinationPath, entry.destinationPath, `${entry.source.label} did not keep its explicit canonical destination.`);
			assert(['paused', 'pause_requested'].includes(snapshot.state), `${entry.source.label} did not start paused.`);
			await page.locator('.torrent-card').filter({ hasText: entry.source.name }).first().waitFor();
			await waitForWebSocketTorrent(
				entry.result.id,
				(torrent) => ['paused', 'pause_requested'].includes(torrent.state),
				`${entry.source.label} paused WebSocket snapshot`
			);
		}
		const archPeakSizeKiB = await resumeOfficialWithinPayloadBound({
			source: archSource,
			destinationPath: officialDestinations['Arch Linux'],
			result: arch.result,
			card: arch.card
		});
		for (const entry of officialEntries) {
			const sizeKiB = officialDestinationSizeKiB(entry.source.label, entry.destinationPath);
			assert(sizeKiB <= officialPayloadLimitKiB, `${entry.source.label} exceeded the 8 MiB smoke-test payload bound.`);
		}
		const companions = officialEntries.filter((entry) => entry.source !== archSource);
		const removedThroughUi = companions.shift();
		const removedResponse = page.waitForResponse((response) =>
			response.url().includes(`/api/torrents/${removedThroughUi.result.id}?`) && response.request().method() === 'DELETE'
		);
		await page.locator('.torrent-card').filter({ hasText: removedThroughUi.source.name }).first()
			.getByRole('button', { name: /Remove & Delete Files/ }).click();
		assert.equal((await removedResponse).status(), 200, 'Official UI removal failed.');
		await poll(async () => !(await api('/api/torrents')).some((torrent) => torrent.id === removedThroughUi.result.id), 'official UI removal');
		const removedTrackedIndex = addedTorrentIds.indexOf(removedThroughUi.result.id);
		assert(removedTrackedIndex >= 0, 'UI-removed official torrent was not registered for cleanup.');
		addedTorrentIds.splice(removedTrackedIndex, 1);
		await Promise.all([...companions, { result: arch.result }].map((entry) => removeTorrentAndWait(entry.result.id)));
		console.log(`Optional M4 official native-entry smoke passed for ${officialEntries.map((entry) => entry.source.label).join(', ')}; all created distinct paused native entries, Arch alone exercised bounded resume/re-pause (peak ${archPeakSizeKiB} KiB), all remained under 8 MiB, and every entry was removed.`);
	}

	await approveDestination(secondDestination);
	const fixture = await addMagnet(fixtureMagnet, fixtureName);
	assert.equal((await api(`/api/torrents/${fixture.result.id}/destination`)).canonicalPath, secondDestination);
	await waitForTorrentComplete(fixture.result.id, Number(process.env.M4_FIXTURE_READY_TIMEOUT ?? 60_000));

	const catalog = await api('/api/storage/catalog');
	assert(catalog.includes(secondDestination));
	assert.equal((await api('/api/storage/latest-selected')).path, secondDestination);

	await approveDestination(moveDestination);
	await fixture.card.locator('select').selectOption(moveDestination);
	const [moveResponse] = await Promise.all([
		page.waitForResponse((response) => response.url().endsWith(`/api/torrents/${fixture.result.id}/move`) && response.request().method() === 'POST'),
		fixture.card.getByRole('button', { name: 'Move', exact: true }).click()
	]);
	const moveResult = await moveResponse.json();
	assert.equal(moveResponse.status(), 200, `Deterministic move was not accepted: ${JSON.stringify(moveResult)}`);
	assert.equal(moveResult.status, 'ok');
	await waitForCompletedMove(fixture.result.id, moveDestination);
	assert.equal(
		androidUi.run('shell', `if [ -f '${moveDestination}/unrelated-sibling' ]; then echo present; fi`),
		'present',
		'Valid target reuse removed an unrelated sibling.'
	);
	assert.equal(
		androidUi.run('shell', `if [ -e '${secondDestination}/${fixtureName}' ]; then echo present; else echo absent; fi`),
		'absent',
		'Verified target reuse did not remove the source after the durable queue update.'
	);

	await approveDestination(secondDestination);
	const partial = await addMagnet(partialMagnet, partialName);
	const partialBeforeMove = await poll(async () => {
		const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === partial.result.id);
		return torrent && torrent.progress > 0 && torrent.progress < 0.999 ? torrent : null;
	}, 'deterministic partial torrent before verification move', 60_000);
	assert.equal((await fetch(`${fixtureControlUrl}/control/block?name=${encodeURIComponent(partialName)}`, { method: 'POST' })).status, 204);
	assert(partialBeforeMove.progress < 0.999);
	await approveDestination(partialDestination);
	await partial.card.locator('select').selectOption(partialDestination);
	const [partialMoveResponse] = await Promise.all([
		page.waitForResponse((response) => response.url().endsWith(`/api/torrents/${partial.result.id}/move`) && response.request().method() === 'POST'),
		partial.card.getByRole('button', { name: 'Move', exact: true }).click()
	]);
	assert.equal(partialMoveResponse.status(), 200);
	await waitForCompletedMove(partial.result.id, partialDestination, 90_000);
	const partialAfterMove = (await api('/api/torrents')).find((torrent) => torrent.id === partial.result.id);
	assert(partialAfterMove && partialAfterMove.progress < 0.999, 'Partial verification incorrectly required torrent completion.');

	await approveDestination(firstDestination);
	androidUi.run('shell', 'mv', stagedReusePayload, `${firstDestination}/${reuseName}`);
	const reused = await addMagnet(reuseMagnet, reuseName);
	assert.equal((await api(`/api/torrents/${reused.result.id}/destination`)).canonicalPath, firstDestination);
	await waitForTorrentComplete(reused.result.id);

	// Preserve one explicit user pause separately from storage safety pauses.
	await partial.card.getByRole('button', { name: /Pause/ }).click();
	await poll(async () => {
		const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === partial.result.id);
		return torrent && ['paused', 'pause_requested'].includes(torrent.state) ? torrent : null;
	}, 'explicit pre-revocation user pause');

	await androidUi.setAllFilesAccess(false);
	await poll(async () => {
		try {
			await fetch(`${baseUrl}/health`, { headers: { Authorization: basicAuthorization }, signal: AbortSignal.timeout(500) });
			return false;
		} catch { return true; }
	}, 'native daemon shutdown after runtime permission revocation', 10_000);
	await androidUi.enterRuntimeRevokedApp();
	await poll(async () => {
		try {
			const response = await fetch(`${baseUrl}/health`, { headers: { Authorization: basicAuthorization }, signal: AbortSignal.timeout(500) });
			return response.ok;
		} catch { return false; }
	}, 'authenticated permission-blocked WebUI', 30_000);
	await page.goto(baseUrl, { waitUntil: 'networkidle' });
	assert.equal((await api('/api/storage/permission')).state, 'RevokedRuntime');
	await page.getByText(/Storage permission is required/).waitFor();
	assert.equal(await page.locator('.add-torrent').getByRole('button', { name: 'Add' }).isDisabled(), true);
	const blockedAdd = await apiResponse('/api/torrents/magnet', {
		method: 'POST', headers: { 'Content-Type': 'application/json' },
		data: { magnet: fixtureMagnet, destinationPath: firstDestination }
	});
	assert.equal(blockedAdd.response.status(), 503);
	const blockedMove = await apiResponse(`/api/torrents/${fixture.result.id}/move`, {
		method: 'POST', headers: { 'Content-Type': 'application/json' },
		data: { destinationPath: moveDestination }
	});
	assert.equal(blockedMove.response.status(), 503);

	await androidUi.restoreAfterRuntimeRevocation();
	await poll(async () => {
		try {
			const response = await fetch(`${baseUrl}/api/storage/permission`, {
				headers: { Authorization: basicAuthorization },
				signal: AbortSignal.timeout(500)
			});
			return response.ok && (await response.json()).state === 'Ready';
		} catch { return false; }
	}, 'storage-ready WebUI after visible permission restoration', 30_000);
	await page.goto(baseUrl, { waitUntil: 'networkidle' });
	await startAuthenticatedWebSocketObserver();
	assert.equal((await api('/api/storage/permission')).state, 'Ready');
	const recoveryHandles = [fixture, reused, partial];
	for (const handle of recoveryHandles) assert.equal(typeof handle.result.queueId, 'string', 'An add response omitted its durable queue ID.');
	const recoveredTorrents = await poll(async () => {
		const torrents = await api('/api/torrents');
		return recoveryHandles.every((handle) => {
			const torrent = torrents.find((candidate) => candidate.queueId === handle.result.queueId);
			return torrent && ['paused', 'pause_requested'].includes(torrent.state);
		}) ? torrents : null;
	}, 'durable safety/user pauses after permission restoration', 30_000);
	for (const handle of recoveryHandles) {
		const recovered = recoveredTorrents.find((torrent) => torrent.queueId === handle.result.queueId);
		assert(recovered, 'A durable queue identity was not recovered after permission loss.');
		const trackedIndex = addedTorrentIds.indexOf(handle.result.id);
		assert(trackedIndex >= 0, 'A recovered torrent was not registered for cleanup.');
		addedTorrentIds[trackedIndex] = recovered.id;
		handle.result.id = recovered.id;
	}
	await page.getByRole('heading', { name: 'Download folder' }).waitFor();
	const recoveredReuseCard = page.locator('.torrent-card').filter({ hasText: reuseName }).first();
	await recoveredReuseCard.getByRole('button', { name: /Resume/ }).click();
	await poll(async () => {
		const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === reused.result.id);
		return torrent && !['paused', 'pause_requested'].includes(torrent.state) ? torrent : null;
	}, 'explicit resume of previously active torrent after permission restoration');
	const stillUserPaused = (await api('/api/torrents')).find((torrent) => torrent.id === partial.result.id);
	assert(stillUserPaused && ['paused', 'pause_requested'].includes(stillUserPaused.state), 'User-paused torrent resumed during permission recovery.');

	await approveDestination(addUnrelatedDestination);
	const addUnrelated = await addMagnet(addUnrelatedMagnet, addUnrelatedName);
	await waitForTorrentComplete(addUnrelated.result.id);
	assert.equal(
		androidUi.run('shell', `if [ -f '${addUnrelatedDestination}/unrelated-sibling' ]; then echo present; fi`),
		'present',
		'Safe add removed an unrelated sibling.'
	);

	await approveDestination(addValidDestination);
	const addValid = await addMagnet(addValidMagnet, addValidName);
	await waitForTorrentComplete(addValid.result.id);
	assert.equal((await api(`/api/torrents/${addValid.result.id}/destination`)).status, 'ok');

	await approveDestination(addCorruptDestination);
	const addCorrupt = await addMagnet(addCorruptMagnet, addCorruptName, 'storage_conflict');
	await addCorrupt.card.getByText(/Storage conflict/).waitFor();
	assert.equal((await api(`/api/torrents/${addCorrupt.result.id}/destination`)).status, 'storage_conflict');
	const addCorruptSnapshot = (await api('/api/torrents')).find((torrent) => torrent.id === addCorrupt.result.id);
	assert(addCorruptSnapshot && ['paused', 'pause_requested'].includes(addCorruptSnapshot.state));
	assert.equal(
		androidUi.run('shell', 'cat', `${addCorruptDestination}/${addCorruptName}`),
		'corrupt',
		'Safe add overwrote incompatible existing bytes.'
	);

	await approveDestination(conflictDestination);
	assert.equal((await fetch(`${fixtureControlUrl}/control/block?name=${encodeURIComponent(fixtureName)}`, { method: 'POST' })).status, 204);
	const fixtureCard = page.locator('.torrent-card').filter({ hasText: fixtureName }).first();
	await fixtureCard.locator('select').selectOption(conflictDestination);
	const [conflictResponse] = await Promise.all([
		page.waitForResponse((response) => response.url().endsWith(`/api/torrents/${fixture.result.id}/move`) && response.request().method() === 'POST'),
		fixtureCard.getByRole('button', { name: 'Move', exact: true }).click()
	]);
	const conflictResult = await conflictResponse.json();
	assert.equal(conflictResponse.status(), 200);
	assert.equal(conflictResult.status, 'ok');
	await poll(
		async () => (await api(`/api/torrents/${fixture.result.id}/move/status`)).phase === 'storage-conflict',
		'piece-verified storage conflict',
		90_000
	);
	await fixtureCard.getByText(/Storage conflict/).waitFor();
	await new Promise((resolve) => setTimeout(resolve, 2_000));
	await fixtureCard.getByText(/Storage conflict/).waitFor();
	assert.equal((await api(`/api/torrents/${fixture.result.id}/move/status`)).phase, 'storage-conflict');
	await waitForWebSocketTorrent(fixture.result.id, (torrent) =>
		torrent.destinationPath === moveDestination && torrent.moveState === 'storage-conflict',
		'canonical storage-conflict WebSocket snapshot'
	);

	const [retryConflictResponse] = await Promise.all([
		page.waitForResponse((response) => response.url().endsWith(`/api/torrents/${fixture.result.id}/move/retry`) && response.request().method() === 'POST'),
		fixtureCard.getByRole('button', { name: 'Retry move', exact: true }).click()
	]);
	assert.equal(retryConflictResponse.status(), 200);
	assert.equal((await retryConflictResponse.json()).status, 'ok');
	await poll(
		async () => (await api(`/api/torrents/${fixture.result.id}/move/status`)).phase === 'storage-conflict',
		'retried incompatible target verification',
		90_000
	);
	const [cancelConflictResponse] = await Promise.all([
		page.waitForResponse((response) => response.url().endsWith(`/api/torrents/${fixture.result.id}/move/cancel`) && response.request().method() === 'POST'),
		fixtureCard.getByRole('button', { name: 'Cancel move', exact: true }).click()
	]);
	assert.equal(cancelConflictResponse.status(), 200);
	assert.equal((await fetch(`${fixtureControlUrl}/control/unblock?name=${encodeURIComponent(fixtureName)}`, { method: 'POST' })).status, 204);
	assert.equal((await api(`/api/torrents/${fixture.result.id}/move/status`)).phase, 'none');

	await approveDestination(secondDestination);
	const interrupted = await addMagnet(interruptedMagnet, interruptedName);
	await waitForTorrentComplete(interrupted.result.id, 90_000);
	await approveDestination(interruptedDestination);
	await interrupted.card.locator('select').selectOption(interruptedDestination);
	androidUi.run('shell', 'touch', `${secondDestination}/${moveGateHold}`);
	const interruptedMoveResponsePromise = page.waitForResponse((response) =>
		response.url().endsWith(`/api/torrents/${interrupted.result.id}/move`) && response.request().method() === 'POST'
	).then((response) => ({ response }), (error) => ({ error }));
	await interrupted.card.getByRole('button', { name: 'Move', exact: true }).click();
	await poll(async () => androidUi.run('shell', `if [ -f '${secondDestination}/${moveGateReached}' ]; then echo reached; fi`) === 'reached', 'fixture-controlled native move boundary');
	androidUi.run('shell', 'sm', 'unmount', interruptedVolumeId);
	androidUi.run('shell', 'touch', `${secondDestination}/${moveGateRelease}`);
	const interruptedMoveOutcome = await interruptedMoveResponsePromise;
	if (interruptedMoveOutcome.error) throw interruptedMoveOutcome.error;
	const interruptedMoveResponse = interruptedMoveOutcome.response;
	assert.equal(interruptedMoveResponse.status(), 200);
	await poll(async () => (await api(`/api/torrents/${interrupted.result.id}/move/status`)).phase === 'move-interrupted', 'native move failure journal finalization', 45_000);
	await page.locator('.torrent-card').filter({ hasText: interruptedName }).getByText(/Move interrupted/).waitFor();
	const failedMoveSnapshot = (await api('/api/torrents')).find((torrent) => torrent.id === interrupted.result.id);
	assert.equal(failedMoveSnapshot.destinationPath, secondDestination, 'REST lost the durable source destination after native move failure.');
	assert.equal(failedMoveSnapshot.savePath, secondDestination, 'Native status lost the source save path after move failure.');
	await waitForWebSocketTorrent(interrupted.result.id, (torrent) =>
		torrent.destinationPath === secondDestination && torrent.savePath === secondDestination &&
			torrent.moveState === 'move-interrupted' && ['paused', 'pause_requested'].includes(torrent.state),
		'canonical move-interrupted WebSocket snapshot after native failure'
	);

	androidUi.run('shell', 'sm', 'mount', interruptedVolumeId);
	await poll(async () => androidUi.run('shell', `if [ -d '${interruptedDestination}' ]; then echo ready; fi`) === 'ready', 'removable destination after native failure');
	androidUi.run('shell', `rm -rf '${interruptedDestination}'/*`);
	const interruptedCard = page.locator('.torrent-card').filter({ hasText: interruptedName }).first();
	const [retryInterruptedResponse] = await Promise.all([
		page.waitForResponse((response) => response.url().endsWith(`/api/torrents/${interrupted.result.id}/move/retry`) && response.request().method() === 'POST'),
		interruptedCard.getByRole('button', { name: 'Retry move', exact: true }).click()
	]);
	assert.equal(retryInterruptedResponse.status(), 200);
	await waitForCompletedMove(interrupted.result.id, interruptedDestination, 90_000);

	const cancelInterruptedDestination = `${interruptedDestination}-cancel`;
	androidUi.run('shell', 'mkdir', '-p', cancelInterruptedDestination);
	assert.equal(
		androidUi.run('shell', 'readlink', '-f', cancelInterruptedDestination),
		cancelInterruptedDestination,
		'ADB did not observe the interrupted-cancel target as canonical.'
	);
	await approveDestination(cancelInterruptedDestination);
	const reusedCard = page.locator('.torrent-card').filter({ hasText: reuseName }).first();
	await reusedCard.locator('select').selectOption(cancelInterruptedDestination);
	androidUi.run('shell', 'touch', `${firstDestination}/${moveGateHold}`);
	const cancelMoveResponsePromise = page.waitForResponse((response) =>
		response.url().endsWith(`/api/torrents/${reused.result.id}/move`) && response.request().method() === 'POST'
	).then((response) => ({ response }), (error) => ({ error }));
	await reusedCard.getByRole('button', { name: 'Move', exact: true }).click();
	await poll(
		async () => androidUi.run('shell', `if [ -f '${firstDestination}/${moveGateReached}' ]; then echo reached; fi`) === 'reached',
		'fixture-controlled native move boundary for explicit cancel'
	);
	androidUi.run('shell', 'sm', 'unmount', interruptedVolumeId);
	androidUi.run('shell', 'touch', `${firstDestination}/${moveGateRelease}`);
	const cancelMoveOutcome = await cancelMoveResponsePromise;
	if (cancelMoveOutcome.error) throw cancelMoveOutcome.error;
	assert.equal(cancelMoveOutcome.response.status(), 200);
	await poll(
		async () => (await api(`/api/torrents/${reused.result.id}/move/status`)).phase === 'move-interrupted',
		'native move failure before explicit cancel',
		45_000
	);
	await reusedCard.getByText(/Move interrupted/).waitFor();
	const cancelInterruptedSnapshot = (await api('/api/torrents')).find((torrent) => torrent.id === reused.result.id);
	assert.equal(cancelInterruptedSnapshot.destinationPath, firstDestination, 'Cancel fixture lost its durable source destination.');
	assert(['paused', 'pause_requested'].includes(cancelInterruptedSnapshot.state), 'Cancel fixture was not paused in move-interrupted state.');

	androidUi.run('shell', 'sm', 'mount', interruptedVolumeId);
	await poll(
		async () => androidUi.run('shell', `if [ -d '${cancelInterruptedDestination}' ]; then echo ready; fi`) === 'ready',
		'removable target before explicit interrupted cancel'
	);
	const [cancelInterruptedResponse] = await Promise.all([
		page.waitForResponse((response) => response.url().endsWith(`/api/torrents/${reused.result.id}/move/cancel`) && response.request().method() === 'POST'),
		reusedCard.getByRole('button', { name: 'Cancel move', exact: true }).click()
	]);
	assert.equal(cancelInterruptedResponse.status(), 200);
	assert.equal((await api(`/api/torrents/${reused.result.id}/move/status`)).phase, 'none');
	assert.equal((await api(`/api/torrents/${reused.result.id}/destination`)).canonicalPath, firstDestination);
	assert(!(await api('/api/storage/moves')).some((move) => move.torrentId === reused.result.id), 'Interrupted cancel left a durable move journal entry.');
	const removeCancelledTargetResponse = await page.request.fetch(
		`${baseUrl}/api/storage/destinations/${encodeURIComponent(cancelInterruptedDestination)}`,
		{ method: 'DELETE' }
	);
	assert.equal(removeCancelledTargetResponse.status(), 200, 'Interrupted cancel did not release the target catalog lock.');
	approvedDestinations.splice(approvedDestinations.indexOf(cancelInterruptedDestination), 1);
	await reusedCard.getByRole('button', { name: /Resume/ }).click();
	await poll(async () => {
		const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === reused.result.id);
		return torrent && !['paused', 'pause_requested'].includes(torrent.state) ? torrent : null;
	}, 'explicit resume after interrupted move cancellation');

	await interruptedCard.getByRole('button', { name: /Resume/ }).click();
	await poll(async () => {
		const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === interrupted.result.id);
		return torrent && !['paused', 'pause_requested'].includes(torrent.state) ? torrent : null;
	}, 'moved torrent active before unavailable-storage simulation');
	const unaffectedBeforeUnmount = (await api('/api/torrents')).find((torrent) => torrent.id === reused.result.id);
	assert(unaffectedBeforeUnmount && !['paused', 'pause_requested'].includes(unaffectedBeforeUnmount.state), 'Unaffected fixture was not active before unmount.');

	androidUi.run('shell', 'sm', 'unmount', interruptedVolumeId);
	const unavailable = await api(`/api/torrents/${interrupted.result.id}/destination`);
	assert.equal(unavailable.status, 'destination_unavailable');
	assert.equal(unavailable.valid, false);
	const unavailableSnapshot = (await api('/api/torrents')).find((torrent) => torrent.id === interrupted.result.id);
	assert.equal(unavailableSnapshot.destinationStatus, 'destination_unavailable');
	assert(['paused', 'pause_requested'].includes(unavailableSnapshot.state));
	const unavailableResume = await apiResponse(`/api/torrents/${interrupted.result.id}/resume`, { method: 'PUT' });
	assert.equal(unavailableResume.response.status(), 409, 'Resume was accepted while the durable destination was unavailable.');
	const pausedAfterRejectedResume = (await api('/api/torrents')).find((torrent) => torrent.id === interrupted.result.id);
	assert(pausedAfterRejectedResume && ['paused', 'pause_requested'].includes(pausedAfterRejectedResume.state));
	const unaffectedWhileUnmounted = (await api('/api/torrents')).find((torrent) => torrent.id === reused.result.id);
	assert(unaffectedWhileUnmounted && !['paused', 'pause_requested'].includes(unaffectedWhileUnmounted.state), 'Unavailable storage paused an unaffected torrent.');
	await page.reload({ waitUntil: 'networkidle' });
	await page.locator('.torrent-card').filter({ hasText: interruptedName }).getByText(/Destination unavailable/).waitFor();
	androidUi.run('shell', 'sm', 'mount', interruptedVolumeId);
	await poll(async () => (await api(`/api/torrents/${interrupted.result.id}/destination`)).status === 'ok', 'destination recovery after remount');
	const stillPausedAfterRemount = (await api('/api/torrents')).find((torrent) => torrent.id === interrupted.result.id);
	assert(stillPausedAfterRemount && ['paused', 'pause_requested'].includes(stillPausedAfterRemount.state), 'Affected torrent resumed without explicit user action after remount.');
	const recoveredCard = page.locator('.torrent-card').filter({ hasText: interruptedName }).first();
	await recoveredCard.getByRole('button', { name: /Resume/ }).click();
	await poll(async () => {
		const torrent = (await api('/api/torrents')).find((candidate) => candidate.id === interrupted.result.id);
		return torrent && !['paused', 'pause_requested'].includes(torrent.state) ? torrent : null;
	}, 'explicit WebUI resume after destination remount');
	const unaffectedAfterRecovery = (await api('/api/torrents')).find((torrent) => torrent.id === reused.result.id);
	assert(unaffectedAfterRecovery && !['paused', 'pause_requested'].includes(unaffectedAfterRecovery.state), 'Unaffected torrent was paused during storage recovery.');

	console.log('M4 deterministic live flow passed: authenticated parseable WebSocket frames, owned fixtures, destination reuse/differences, visible permission denial/revocation, canonical paths, collision-safe valid/corrupt/unrelated adds, completed/partial/conflict/native-failure moves, unavailable storage, explicit interrupted retry/cancel, explicit resume, and recovery.');
} catch (error) {
	primaryFailure = error;
} finally {
	if (androidUi && interruptedVolumeId) {
		try { androidUi.run('shell', 'sm', 'mount', interruptedVolumeId); } catch (error) { cleanupFailures.push(new Error('Removable volume cleanup remount failed', { cause: error })); }
	}
	if (page) {
		for (const torrentId of [...addedTorrentIds].reverse()) {
			try { await page.request.fetch(`${baseUrl}/api/torrents/${torrentId}/move/cancel`, { method: 'POST' }); } catch (error) { cleanupFailures.push(new Error(`Move cleanup failed for torrent ${torrentId}`, { cause: error })); }
			try {
				const response = await page.request.fetch(`${baseUrl}/api/torrents/${torrentId}?deleteFiles=true`, { method: 'DELETE' });
				if (!response.ok() && response.status() !== 404) cleanupFailures.push(new Error(`Torrent cleanup failed for ${torrentId}: HTTP ${response.status()}`));
			} catch (error) { cleanupFailures.push(new Error(`Torrent cleanup failed for ${torrentId}`, { cause: error })); }
		}
		for (const destination of [...approvedDestinations].reverse()) {
			try {
				const response = await page.request.fetch(`${baseUrl}/api/storage/destinations/${encodeURIComponent(destination)}`, { method: 'DELETE' });
				if (!response.ok() && response.status() !== 404) cleanupFailures.push(new Error(`Destination cleanup failed for ${destination}: HTTP ${response.status()}`));
			} catch (error) { cleanupFailures.push(new Error(`Destination cleanup failed for ${destination}`, { cause: error })); }
		}
		try {
			const remainingTorrents = await api('/api/torrents');
			const remainingMoves = await api('/api/storage/moves');
			const remainingCatalog = await api('/api/storage/catalog');
			for (const torrentId of addedTorrentIds) {
				if (remainingTorrents.some((torrent) => torrent.id === torrentId)) cleanupFailures.push(new Error(`Torrent/queue record still exists for ${torrentId}`));
				if (remainingMoves.some((move) => move.torrentId === torrentId)) cleanupFailures.push(new Error(`Move journal still exists for ${torrentId}`));
			}
			for (const destination of approvedDestinations) {
				if (remainingCatalog.includes(destination)) cleanupFailures.push(new Error(`Catalog entry still exists for ${destination}`));
			}
		} catch (error) { cleanupFailures.push(new Error('API cleanup verification failed', { cause: error })); }
	}
	try {
		const response = await fetch(`${baseUrl}/api/daemon/stop`, {
			method: 'POST',
			headers: { Authorization: basicAuthorization }
		});
		const body = await response.text();
		if (!response.ok) cleanupFailures.push(new Error(`Daemon stop failed: HTTP ${response.status} ${body}`));
	} catch (error) { cleanupFailures.push(new Error('Daemon stop request failed', { cause: error })); }
	try {
		await poll(async () => {
			try {
				await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(500) });
				return false;
			} catch { return true; }
		}, 'Ktor server shutdown', 10_000);
		if (androidUi) {
			const listeners = androidUi.run('shell', 'ss', '-tln');
			assert(!/:8080\s/.test(listeners), 'Ktor listener remains after daemon stop.');
		}
	} catch (error) { cleanupFailures.push(new Error('Independent daemon/server shutdown verification failed', { cause: error })); }
	if (browser) {
		try { await browser.close(); } catch (error) { cleanupFailures.push(new Error('Browser cleanup failed', { cause: error })); }
	}
}

if (primaryFailure) {
	if (cleanupFailures.length) primaryFailure.cleanupFailures = cleanupFailures;
	throw primaryFailure;
}
if (cleanupFailures.length) throw new AggregateError(cleanupFailures, 'M4 live cleanup failed.');
