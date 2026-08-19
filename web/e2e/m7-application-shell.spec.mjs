import { expect, test } from '@playwright/test';

function completedOnboarding() {
	return {
		completed: true,
		passwordDecision: 'deferred',
		hasApprovedDestination: true,
		readiness: 'Ready'
	};
}

async function installShellApi(page, downloads) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(completedOnboarding())
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: 'Ready' })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(['/storage/primary/Downloads'])
	}));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ path: '/storage/primary/Downloads' })
	}));
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(downloads)
	}));
}

test('Instrument shell keeps the empty first-Download call to action usable without desktop-width overflow', async ({ page }) => {
	await installShellApi(page, []);
	await page.goto('/');

	await expect(page.locator('h1')).toHaveText('Downloads');
	await expect(page.getByRole('heading', { name: 'Add Download' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Ready when you are' })).toBeVisible();
	await expect(page.getByText('Add your first Download to see its progress here.')).toBeVisible();
	await expect(page.getByText('THROWAWAY PROTOTYPE')).toHaveCount(0);
	await expect(page.locator('body')).not.toContainText(/torrent webui|peers|libtorrent/i);

	for (const viewport of [
		{ width: 360, height: 800 },
		{ width: 768, height: 1024 },
		{ width: 1440, height: 900 }
	]) {
		await page.setViewportSize(viewport);
		const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
		expect(dimensions.width, `${viewport.width}px viewport must not horizontally scroll`).toBeLessThanOrEqual(dimensions.viewport);
		const action = page.getByRole('button', { name: 'Add Download', exact: true });
		await action.scrollIntoViewIfNeeded();
		await expect(action).toBeVisible();
	}
});

test('Instrument shell uses quiet status treatments for completed-only and no-completed queues', async ({ page }) => {
	await installShellApi(page, [{
		id: 4,
		name: 'Family album',
		state: 'finished',
		progress: 1,
		downloadRate: 0,
		uploadRate: 0,
		peers: 0,
		savePath: '/storage/primary/Downloads'
	}]);
	await page.goto('/');
	await expect(page.getByText('Nothing is active right now.')).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Completed' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Family album' })).toBeVisible();

	await page.unroute('**/api/torrents');
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify([{ id: 5, name: 'New album', state: 'downloading', progress: .2, downloadRate: 0, uploadRate: 0, peers: 0, savePath: '' }])
	}));
	await page.reload();
	await expect(page.getByText('Completed Downloads will appear here.')).toBeVisible();
});
