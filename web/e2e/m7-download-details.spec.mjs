import { expect, test } from '@playwright/test';

const destination = '/storage/primary/Downloads';
const longPath = `${destination}/${'very-long-folder-name-'.repeat(22)}final-download-folder`;

function download(id, name, state, extra = {}) {
	return {
		id,
		name,
		state,
		progress: .625,
		downloadRate: 1024,
		uploadRate: 2048,
		peers: 3,
		savePath: destination,
		...extra
	};
}

async function installDetailsApi(page, downloads) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({
			completed: true,
			passwordDecision: 'deferred',
			hasApprovedDestination: true,
			readiness: 'Ready'
		})
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: 'Ready' })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify([destination])
	}));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ path: destination })
	}));
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(downloads)
	}));
}

function historyPrimitiveValues(value) {
	if (value === null || value === undefined) return [];
	if (Array.isArray(value)) return value.flatMap(historyPrimitiveValues);
	if (typeof value === 'object') return Object.values(value).flatMap(historyPrimitiveValues);
	return [String(value)];
}

function historyPrivacy(page, sensitiveValues) {
	return page.evaluate(() => ({
		url: `${window.location.pathname}${window.location.search}${window.location.hash}`,
		state: window.history.state
	})).then(({ url, state }) => {
		const stateValues = historyPrimitiveValues(state);
		const urlTokens = url.split(/[^A-Za-z0-9._~-]+/).filter(Boolean);
		for (const value of sensitiveValues) {
			if (/^\d+$/.test(value)) {
				expect(urlTokens).not.toContain(value);
				expect(stateValues).not.toContain(value);
			} else {
				expect(url).not.toContain(value);
				for (const stateValue of stateValues) expect(stateValue).not.toContain(value);
			}
		}
	});
}

test('desktop Details is a right drawer with technical data only in the secondary surface', async ({ page }) => {
	const name = 'Family archive';
	await page.setViewportSize({ width: 1440, height: 900 });
	await installDetailsApi(page, [
		download(41, name, 'seeding', { destinationPath: longPath }),
		download(42, 'Completed album', 'finished')
	]);
	await page.goto('/');

	const card = page.locator('article', { has: page.getByRole('heading', { name }) });
	const opener = card.getByRole('button', { name: 'View details' });
	await expect(page.getByRole('button', { name: 'View details' })).toHaveCount(2);
	await expect(card).not.toContainText(/download speed|upload speed|peer information|sharing/i);
	await opener.focus();
	await opener.press('Enter');

	const dialog = page.getByRole('dialog', { name: `${name} details` });
	await expect(dialog).toBeVisible();
	await expect(dialog).toBeFocused();
	await expect(dialog).toContainText('62.5% complete · Complete');
	await expect(dialog).toContainText('1.0 KB/s');
	await expect(dialog).toContainText('2.0 KB/s');
	await expect(dialog).toContainText('3 connected');
	await expect(dialog).toContainText('Sharing with 3 connected peers');
	await expect(dialog).toContainText(longPath);
	await expect(page).toHaveURL('/');
	await historyPrivacy(page, [name, longPath, '41', '1024', '2048']);

	const box = await dialog.boundingBox();
	expect(box?.x).toBeGreaterThan(800);
	expect(Math.round(box?.height ?? 0)).toBe(900);
	const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
	expect(dimensions.width).toBeLessThanOrEqual(dimensions.viewport);

	await page.keyboard.press('Escape');
	await expect(dialog).toHaveCount(0);
	await expect(opener).toBeFocused();
});

test('phone Details fills the viewport and explicit close restores the card opener', async ({ page }) => {
	const name = 'A long Download name '.repeat(24);
	await page.setViewportSize({ width: 360, height: 800 });
	await installDetailsApi(page, [download(51, name, 'downloading', { destinationPath: longPath })]);
	await page.goto('/');

	const opener = page.getByRole('button', { name: 'View details' });
	await opener.click();
	const dialog = page.getByRole('dialog');
	await expect(dialog).toBeVisible();
	const box = await dialog.boundingBox();
	expect(Math.round(box?.x ?? -1)).toBe(0);
	expect(Math.round(box?.y ?? -1)).toBe(0);
	expect(Math.round(box?.width ?? 0)).toBe(360);
	expect(Math.round(box?.height ?? 0)).toBe(800);
	const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
	expect(dimensions.width).toBeLessThanOrEqual(dimensions.viewport);

	await dialog.getByRole('button', { name: 'Close details' }).click();
	await expect(dialog).toHaveCount(0);
	await expect(opener).toBeFocused();
});

test('Back closes Details on the home screen, and reload never reopens it', async ({ page }) => {
	const name = 'Private family archive';
	await installDetailsApi(page, [download(61, name, 'paused', { destinationPath: longPath })]);
	await page.goto('/');
	const opener = page.getByRole('button', { name: 'View details' });
	await opener.click();
	await expect(page.getByRole('dialog')).toBeVisible();
	await historyPrivacy(page, [name, longPath, '61', '1024']);

	await page.goBack();
	await expect(page).toHaveURL('/');
	await expect(page.getByRole('dialog')).toHaveCount(0);
	await expect(opener).toBeFocused();

	await opener.click();
	await expect(page.getByRole('dialog')).toBeVisible();
	await page.reload();
	await expect(page).toHaveURL('/');
	await expect(page.getByRole('dialog')).toHaveCount(0);
	await historyPrivacy(page, [name, longPath, '61', '1024']);
});
