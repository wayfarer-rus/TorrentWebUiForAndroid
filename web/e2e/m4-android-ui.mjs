import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const SETTINGS_ACTION = 'android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION';
const APP_SETTINGS_LABEL = 'TorrentWebUiForAndroid';

function boundsCenter(node) {
	const match = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
	assert(match, `UI node has no bounds: ${node}`);
	return [Math.floor((Number(match[1]) + Number(match[3])) / 2), Math.floor((Number(match[2]) + Number(match[4])) / 2)];
}

function escapeRegExp(value) {
	return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function createAndroidUi({ adb, serial, packageName }) {
	function run(...args) {
		return execFileSync(adb, ['-s', serial, ...args], {
			encoding: 'utf8',
			stdio: ['ignore', 'pipe', 'pipe'],
			timeout: 30_000,
			killSignal: 'SIGKILL'
		}).trim();
	}

	async function hierarchy() {
		run('shell', 'uiautomator', 'dump', '/sdcard/torrent-webui-m4-window.xml');
		return run('exec-out', 'cat', '/sdcard/torrent-webui-m4-window.xml');
	}

	async function waitForNode(pattern, description, timeoutMs = 10_000) {
		const deadline = Date.now() + timeoutMs;
		let lastHierarchy = '';
		while (Date.now() < deadline) {
			lastHierarchy = await hierarchy();
			const match = lastHierarchy.match(pattern);
			if (match) return match[0];
			await new Promise((resolve) => setTimeout(resolve, 250));
		}
		assert.fail(`Timed out waiting for Android UI node ${description}. Hierarchy excerpt: ${lastHierarchy.slice(0, 500)}`);
	}

	async function tapText(text) {
		const pattern = new RegExp(`<node[^>]*text="${escapeRegExp(text)}"[^>]*>`);
		const node = await waitForNode(pattern, JSON.stringify(text));
		const [x, y] = boundsCenter(node);
		run('shell', 'input', 'tap', String(x), String(y));
	}

	function scrollBidirectionally(currentHierarchy) {
		const atBottom = currentHierarchy.includes('text="Start downloads"') || currentHierarchy.includes('text="Stop downloads"');
		const [fromY, toY] = atBottom ? ['650', '1900'] : ['1900', '650'];
		run('shell', 'input', 'swipe', '540', fromY, '540', toY, '250');
	}

	async function tapVisibleText(text, attempts = 8) {
		const pattern = new RegExp(`<node[^>]*text="${escapeRegExp(text)}"[^>]*>`);
		for (let attempt = 0; attempt < attempts; attempt += 1) {
			const current = await hierarchy();
			const node = current.match(pattern)?.[0];
			if (node) {
				const [x, y] = boundsCenter(node);
				run('shell', 'input', 'tap', String(x), String(y));
				return;
			}
			scrollBidirectionally(current);
			await new Promise((resolve) => setTimeout(resolve, 250));
		}
		assert.fail(`${text} was not visible after scrolling the Android UI.`);
	}

	async function waitForVisibleNode(pattern, description, attempts = 12) {
		let lastHierarchy = '';
		for (let attempt = 0; attempt < attempts; attempt += 1) {
			lastHierarchy = await hierarchy();
			const match = lastHierarchy.match(pattern);
			if (match) return match[0];
			scrollBidirectionally(lastHierarchy);
			await new Promise((resolve) => setTimeout(resolve, 250));
		}
		assert.fail(`Timed out waiting for visible Android UI node ${description}. Hierarchy excerpt: ${lastHierarchy.slice(0, 500)}`);
	}

	async function grantNotificationIfRequested() {
		try {
			const node = await waitForNode(/<node[^>]*text="(?:Allow|ALLOW)"[^>]*clickable="true"[^>]*>/, 'notification Allow button', 5_000);
			const [x, y] = boundsCenter(node);
			run('shell', 'input', 'tap', String(x), String(y));
		} catch {
			// Already granted installations advance directly to All Files Access settings.
		}
	}

	async function openAllFilesSettings() {
		run('shell', 'am', 'start', '-a', SETTINGS_ACTION);
		await tapText(APP_SETTINGS_LABEL);
		return waitForNode(/<node[^>]*checkable="true"[^>]*clickable="true"[^>]*>/, 'All Files Access switch');
	}

	async function setCurrentAllFilesAccess(enabled) {
		let node = await waitForNode(
			/<node[^>]*checkable="true"[^>]*clickable="true"[^>]*>/,
			'open All Files Access switch'
		);
		assert.match(node, /enabled="true"/, 'All Files Access switch is disabled.');
		const checked = /checked="true"/.test(node);
		if (checked !== enabled) {
			const [x, y] = boundsCenter(node);
			run('shell', 'input', 'tap', String(x), String(y));
			node = await waitForNode(
				new RegExp(`<node[^>]*checkable="true"[^>]*checked="${enabled}"[^>]*clickable="true"[^>]*>`),
				`All Files Access checked=${enabled}`
			);
		}
		assert.equal(/checked="true"/.test(node), enabled);
		if (enabled) {
			await returnToBlockedApp('Torrent Daemon after All Files Access checked=true');
		} else {
			run('shell', 'input', 'keyevent', 'KEYCODE_BACK');
		}
	}

	async function setAllFilesAccess(enabled) {
		await openAllFilesSettings();
		await setCurrentAllFilesAccess(enabled);
	}

	async function launchApp() {
		run('shell', 'am', 'start', '-n', `${packageName}/.MainActivity`);
		await waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Torrent Daemon screen');
	}

	async function returnToBlockedApp(description) {
		await new Promise((resolve) => setTimeout(resolve, 500));
		for (let attempt = 0; attempt < 4; attempt += 1) {
			run('shell', 'input', 'keyevent', 'KEYCODE_BACK');
			try {
				await waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, description, 3_000);
				return;
			} catch {
				// Walk through any generic Settings list left under the per-app detail screen.
			}
		}
		run('shell', 'am', 'start', '--activity-reorder-to-front', '-n', `${packageName}/.MainActivity`);
		await waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, description);
	}

	async function enterRuntimeRevokedApp() {
		for (let attempt = 0; attempt < 3; attempt += 1) {
			run('shell', 'input', 'keyevent', 'KEYCODE_HOME');
			run('shell', 'am', 'start', '-n', `${packageName}/.MainActivity`);
			let appVisible = false;
			try {
				await waitForNode(/<node[^>]*text="Torrent Daemon"[^>]*>/, 'Torrent Daemon after runtime revocation', 3_000);
				appVisible = true;
			} catch { /* The app may have opened Android Settings. */ }
			if (appVisible) {
				try {
					await waitForVisibleNode(
						/<node[^>]*text="Revoked[^\"]*storage operations blocked"[^>]*>/,
						'blocked runtime storage state',
						10
					);
					return;
				} catch {
					continue;
				}
			}
			await waitForNode(/<node[^>]*checkable="true"[^>]*checked="false"[^>]*>/, 'runtime-revoked All Files Access switch');
			try {
				await returnToBlockedApp('Torrent Daemon after runtime revocation');
				await waitForVisibleNode(/<node[^>]*text="Revoked[^\"]*storage operations blocked"[^>]*>/, 'blocked runtime storage state');
				return;
			} catch {
				// Retry the whole visible recovery path when Settings transitions slowly.
			}
		}
		assert.fail('Android did not expose its blocked runtime storage state.');
	}

	async function restoreAfterRuntimeRevocation() {
		await setAllFilesAccess(true);
		run('shell', 'am', 'start', '--activity-reorder-to-front', '-n', `${packageName}/.MainActivity`);
		await waitForVisibleNode(/<node[^>]*text="Ready"[^>]*>/, 'Ready storage state after runtime revocation');
		await tapVisibleText('Start downloads');
		try {
			await waitForVisibleNode(/text="Session started"[\s\S]*?text="yes"/, 'native session after permission restoration');
		} catch {
			run('shell', 'am', 'start', '--activity-reorder-to-front', '-n', `${packageName}/.MainActivity`);
			await tapVisibleText('Start downloads');
			await waitForVisibleNode(/text="Session started"[\s\S]*?text="yes"/, 'retried native session after permission restoration');
		}
	}

	async function assertStartupDenialAndRestore() {
		run('shell', 'am', 'force-stop', packageName);
		await setAllFilesAccess(false);
		run('shell', 'input', 'keyevent', 'KEYCODE_HOME');
		run('shell', 'am', 'start', '-n', `${packageName}/.MainActivity`);
		await grantNotificationIfRequested();
		try {
			await waitForNode(/<node[^>]*checkable="true"[^>]*checked="false"[^>]*>/, 'automatically opened denied All Files Access switch', 3_000);
		} catch {
			// API 36 can present the notification dialog after the first bounded wait.
			await grantNotificationIfRequested();
			run('shell', 'am', 'start', '-n', `${packageName}/.MainActivity`);
			await waitForNode(/<node[^>]*checkable="true"[^>]*checked="false"[^>]*>/, 'automatically opened denied All Files Access switch');
		}
		await returnToBlockedApp('Torrent Daemon screen');
		await waitForNode(/<node[^>]*text="Denied \(startup\)[^\"]*"[^>]*>/, 'Denied startup state');
		await tapVisibleText('Grant All Files Access');
		await waitForNode(/<node[^>]*checkable="true"[^>]*checked="false"[^>]*>/, 'denied All Files Access switch');
		run('shell', 'input', 'keyevent', 'KEYCODE_BACK');
		await waitForNode(/<node[^>]*text="Denied \(startup\)[^\"]*"[^>]*>/, 'Denied startup state after returning');
		await tapVisibleText('Grant All Files Access');
		let node = await waitForNode(/<node[^>]*checkable="true"[^>]*checked="false"[^>]*clickable="true"[^>]*>/, 'All Files Access restore switch');
		const [x, y] = boundsCenter(node);
		run('shell', 'input', 'tap', String(x), String(y));
		await waitForNode(/<node[^>]*checkable="true"[^>]*checked="true"[^>]*>/, 'restored All Files Access switch');
		run('shell', 'input', 'keyevent', 'KEYCODE_BACK');
		await waitForNode(/<node[^>]*text="Ready"[^>]*>/, 'Ready storage state');
		await tapVisibleText('Start downloads');
	}

	return {
		run, hierarchy, waitForNode, waitForVisibleNode, scrollBidirectionally, tapText, grantNotificationIfRequested,
		setCurrentAllFilesAccess, setAllFilesAccess, launchApp,
		enterRuntimeRevokedApp, restoreAfterRuntimeRevocation, assertStartupDenialAndRestore
	};
}
