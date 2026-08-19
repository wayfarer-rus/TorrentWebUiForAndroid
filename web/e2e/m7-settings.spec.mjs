import { expect, test } from '@playwright/test';

function completedOnboarding() {
	return {
		completed: true,
		passwordDecision: 'deferred',
		hasApprovedDestination: true,
		readiness: 'Ready'
	};
}

async function installSettingsApi(page) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(completedOnboarding())
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: 'Ready' })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ path: null })
	}));
	await page.route('**/api/torrents', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
}

async function expectPrivateHistory(page, values) {
	const location = await page.evaluate(() => ({
		url: `${window.location.pathname}${window.location.search}${window.location.hash}`,
		state: JSON.stringify(window.history.state)
	}));
	for (const value of values) {
		expect(location.url).not.toContain(value);
		expect(location.state).not.toContain(value);
	}
}

test('desktop Settings is a focused right drawer with only household entries and Back closes it', async ({ page }) => {
	await page.setViewportSize({ width: 1440, height: 900 });
	await installSettingsApi(page);
	await page.goto('/');

	const opener = page.getByRole('button', { name: 'Open settings' });
	await opener.focus();
	await opener.press('Enter');
	const dialog = page.getByRole('dialog', { name: 'Settings' });
	await expect(dialog).toBeVisible();
	await expect(dialog).toBeFocused();
	await expect(dialog.getByRole('button')).toHaveCount(4);
	await expect(dialog.getByRole('button', { name: /Download folders/ })).toBeVisible();
	await expect(dialog.getByRole('button', { name: /Password/ })).toBeVisible();
	await expect(dialog.getByRole('button', { name: /About/ })).toBeVisible();
	await expect(dialog).not.toContainText(/tracker|bandwidth|queue|network|theme|notification|android/i);
	await dialog.getByRole('button', { name: /Download folders/ }).click();
	await expect(dialog.getByRole('heading', { name: 'Download folders' })).toBeVisible();
	await dialog.getByRole('button', { name: 'Back to Settings' }).click();

	const box = await dialog.boundingBox();
	expect(box?.x).toBeGreaterThan(900);
	expect(Math.round(box?.height ?? 0)).toBe(900);
	await expectPrivateHistory(page, ['current secret', 'new secret']);

	await dialog.getByRole('button', { name: /About/ }).click();
	await expect(dialog.getByRole('heading', { name: 'About Downloads' })).toBeVisible();
	await expect(dialog).toContainText('household manage downloads');
	await expect(dialog).toContainText('local network');
	await expect(dialog).not.toContainText(/libtorrent|android device model/i);
	await dialog.getByRole('button', { name: 'Back to Settings' }).click();

	await page.goBack();
	await expect(dialog).toHaveCount(0);
	await expect(opener).toBeFocused();
});

test('phone Settings fills the viewport and Escape closes it without horizontal overflow', async ({ page }) => {
	await page.setViewportSize({ width: 360, height: 800 });
	await installSettingsApi(page);
	await page.goto('/');

	const opener = page.getByRole('button', { name: 'Open settings' });
	await opener.click();
	const dialog = page.getByRole('dialog', { name: 'Settings' });
	const box = await dialog.boundingBox();
	expect(Math.round(box?.x ?? -1)).toBe(0);
	expect(Math.round(box?.y ?? -1)).toBe(0);
	expect(Math.round(box?.width ?? 0)).toBe(360);
	expect(Math.round(box?.height ?? 0)).toBe(800);
	const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
	expect(dimensions.width).toBeLessThanOrEqual(dimensions.viewport);

	await page.keyboard.press('Escape');
	await expect(dialog).toHaveCount(0);
	await expect(opener).toBeFocused();

	await opener.click();
	await page.getByRole('dialog', { name: 'Settings' }).getByRole('button', { name: 'Close settings' }).click();
	await expect(page.getByRole('dialog', { name: 'Settings' })).toHaveCount(0);
	await expect(opener).toBeFocused();
});

test('Password keeps server failures, submits private values by POST, and reloads for browser reauthentication', async ({ page }) => {
	await installSettingsApi(page);
	let passwordAttempts = 0;
	await page.route('**/api/settings/password', (route) => {
		passwordAttempts += 1;
		const request = route.request();
		expect(new URL(request.url()).pathname).toBe('/api/settings/password');
		expect(new URL(request.url()).search).toBe('');
		expect(request.headers().authorization).toMatch(/^Basic /);
		expect(request.postDataJSON()).toEqual({ currentPassword: 'current secret', newPassword: 'new secret' });
		if (passwordAttempts === 1) {
			return route.fulfill({
				status: 400,
				contentType: 'application/json',
				body: JSON.stringify({ error: 'Current Password is incorrect' })
			});
		}
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok' }) });
	});
	await page.goto('/');
	await page.getByRole('button', { name: 'Open settings' }).click();
	const dialog = page.getByRole('dialog', { name: 'Settings' });
	await dialog.getByRole('button', { name: /Password/ }).click();
	await page.getByLabel('Current Password').fill('current secret');
	await page.getByLabel('New Password').fill('new secret');
	await expectPrivateHistory(page, ['current secret', 'new secret']);

	await page.getByRole('button', { name: 'Change Password' }).click();
	await expect(dialog.getByRole('alert')).toHaveText('Current Password is incorrect');
	await expectPrivateHistory(page, ['current secret', 'new secret']);

	const reload = page.waitForRequest((request) => request.isNavigationRequest() && new URL(request.url()).pathname === '/');
	await page.getByRole('button', { name: 'Change Password' }).click();
	await reload;
	await page.waitForLoadState('domcontentloaded');
	await expect(page).toHaveURL('/');
	await expect(page.getByRole('dialog', { name: 'Settings' })).toHaveCount(0);
	await expect.poll(() => passwordAttempts).toBe(2);
	await expectPrivateHistory(page, ['current secret', 'new secret']);
});
