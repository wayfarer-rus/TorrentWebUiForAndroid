import { expect, test } from '@playwright/test';

test('authenticated browser reaches Downloads and receives an acknowledged addition through the production page', async ({ page }) => {
	const authorization = 'Basic ' + Buffer.from(':household-passphrase').toString('base64');
	let added = false;
	const apiAuthorizations = [];

	await page.setExtraHTTPHeaders({ Authorization: authorization });
	await page.route('**/api/onboarding/status', async (route) => {
		apiAuthorizations.push(route.request().headers().authorization);
		await route.fulfill({
			contentType: 'application/json',
			body: JSON.stringify({
				completed: true,
				passwordDecision: 'deferred',
				hasApprovedDestination: true,
				readiness: 'Ready'
			})
		});
	});
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: 'Ready' })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({
		contentType: 'application/json', body: '[]'
	}));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(['/storage/primary/Downloads'])
	}));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ path: '/storage/primary/Downloads' })
	}));
	await page.route('**/api/torrents/magnet', async (route) => {
		apiAuthorizations.push(route.request().headers().authorization);
		expect(route.request().postDataJSON()).toEqual({
			magnet: 'magnet:?xt=urn:btih:fixture&dn=Family+Album',
			destinationPath: '/storage/primary/Downloads'
		});
		added = true;
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 7 }) });
	});
	await page.route('**/api/torrents', async (route) => {
		apiAuthorizations.push(route.request().headers().authorization);
		await route.fulfill({
			contentType: 'application/json',
			body: JSON.stringify(added ? [{
				id: 7,
				name: 'Family Album',
				state: 'downloading',
				progress: 0,
				downloadRate: 0,
				uploadRate: 0,
				peers: 0,
				savePath: '',
				destinationPath: '/storage/primary/Downloads'
			}] : [])
		});
	});

	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'Add Download' })).toBeVisible();
	await expect(page.locator('h1')).toHaveText('Downloads');
	await expect(page.getByRole('radio', { name: '/storage/primary/Downloads' })).toBeChecked();

	await page.getByPlaceholder('Paste your Download link').fill('magnet:?xt=urn:btih:fixture&dn=Family+Album');
	await page.getByRole('button', { name: 'Add Download', exact: true }).click();
	await expect(page.getByRole('heading', { name: 'Active' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Family Album' })).toBeVisible();
	expect(apiAuthorizations).toContain(authorization);
});
