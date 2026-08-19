import { expect, test } from '@playwright/test';

const canonicalPath = `/storage/primary/Download/${'Family archive/'.repeat(18)}complete`;

function snapshot(overrides = {}) {
	return [{
		id: 41,
		name: 'Long-path Download',
		state: 'downloading',
		progress: .5,
		downloadRate: 0,
		uploadRate: 0,
		peers: 0,
		savePath: '/native/path/must-not-win',
		destinationPath: canonicalPath,
		...overrides
	}];
}

async function installApi(page, requestedUrls, downloads = snapshot()) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ completed: true, passwordDecision: 'deferred', hasApprovedDestination: true, readiness: 'Ready' })
	}));
	for (const path of ['/api/storage/permission', '/api/storage/volumes', '/api/storage/catalog', '/api/storage/latest-selected', '/api/torrents']) {
		await page.route(`**${path}`, (route) => {
			requestedUrls.push(route.request().url());
			const body = path === '/api/storage/permission' ? { state: 'Ready' }
				: path === '/api/storage/latest-selected' ? { path: null }
				: path === '/api/torrents' ? downloads : [];
			return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
		});
	}
}

async function cardPathInput(page) {
	return page.locator('article', { has: page.getByRole('heading', { name: 'Long-path Download' }) })
		.getByRole('textbox', { name: 'Download Folder' });
}

test('copies the sole backend canonical path in one keyboard-operable interaction', async ({ page }) => {
	const requestedUrls = [];
	const copied = [];
	await page.addInitScript(() => {
		Object.defineProperty(navigator, 'clipboard', {
			configurable: true,
			value: { writeText: (value) => { window.__copiedPaths.push(value); return Promise.resolve(); } }
		});
		window.__copiedPaths = [];
	});
	await installApi(page, requestedUrls);
	await page.goto('/');

	const input = await cardPathInput(page);
	await expect(input).toHaveValue(canonicalPath);
	await expect(input).not.toHaveValue('/native/path/must-not-win');
	expect(await input.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
	await page.getByRole('button', { name: 'Copy path' }).press('Enter');
	await expect(page.getByRole('status')).toContainText('Path copied.');
	copied.push(...await page.evaluate(() => window.__copiedPaths));
	expect(copied).toEqual([canonicalPath]);
	expect(requestedUrls.every((url) => !url.includes(encodeURIComponent(canonicalPath)) && !url.includes(canonicalPath))).toBe(true);
	expect(page.url()).not.toContain(canonicalPath);
	expect(await page.evaluate(() => JSON.stringify(history.state))).not.toContain(canonicalPath);
});

test('does not expose a native save path when the backend omits a verified destination', async ({ page }) => {
	const requestedUrls = [];
	const nativePath = '/native/path/must-not-win';
	await installApi(page, requestedUrls, snapshot({ destinationPath: null, savePath: nativePath }));
	await page.goto('/');

	const card = page.locator('article', { has: page.getByRole('heading', { name: 'Long-path Download' }) });
	await expect(card.getByText('Download Folder unavailable. Check the Android app to restore its verified folder.')).toBeVisible();
	await expect(card.getByRole('textbox', { name: 'Download Folder' })).toHaveCount(0);
	await expect(card).not.toContainText(nativePath);
	expect(requestedUrls.every((url) => !url.includes(nativePath))).toBe(true);
});

test('on LAN HTTP clipboard rejection selects the complete path and never claims success', async ({ page }) => {
	const requestedUrls = [];
	await page.addInitScript(() => {
		Object.defineProperty(navigator, 'clipboard', {
			configurable: true,
			value: { writeText: () => Promise.reject(new DOMException('Denied', 'NotAllowedError')) }
		});
	});
	await installApi(page, requestedUrls);
	await page.goto('/');

	const input = await cardPathInput(page);
	const copy = page.getByRole('button', { name: 'Copy path' });
	await copy.click();
	await expect(page.getByRole('status')).toContainText('Path selected. Use your system copy command.');
	await expect(page.getByRole('status')).not.toContainText('Path copied.');
	await expect(input).toBeFocused();
	await expect(input.evaluate((element) => ({
		value: element.value,
		selected: element.value.slice(element.selectionStart, element.selectionEnd)
	}))).resolves.toEqual({ value: canonicalPath, selected: canonicalPath });
	expect(page.url()).not.toContain(canonicalPath);
	expect(requestedUrls.every((url) => !url.includes(encodeURIComponent(canonicalPath)) && !url.includes(canonicalPath))).toBe(true);
});

test('clipboard unavailability also selects the complete canonical path in one touch-operable interaction', async ({ page }) => {
	const requestedUrls = [];
	await page.addInitScript(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined }));
	await installApi(page, requestedUrls);
	await page.goto('/');

	const input = await cardPathInput(page);
	await page.getByRole('button', { name: 'Copy path' }).click();
	await expect(page.getByRole('status')).toContainText('Path selected. Use your system copy command.');
	await expect(input.evaluate((element) => element.value.slice(element.selectionStart, element.selectionEnd))).resolves.toBe(canonicalPath);
});
