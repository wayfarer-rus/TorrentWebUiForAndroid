import { expect, test } from '@playwright/test';

const destination = '/storage/primary/Downloads';
const magnet = 'magnet:?xt=urn:btih:fixture&dn=Family+Album';

async function installAddDownloadApi(page, addHandler) {
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
	await page.route('**/api/torrents', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/torrents/magnet', addHandler);
}

test('Add Download waits for acknowledgement, then clears only the Download link and announces success', async ({ page }) => {
	let acknowledge;
	let addRequests = 0;
	const acknowledgement = new Promise((resolve) => { acknowledge = resolve; });
	await installAddDownloadApi(page, async (route) => {
		addRequests += 1;
		expect(route.request().postDataJSON()).toEqual({ magnet, destinationPath: destination });
		await acknowledgement;
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 7, status: 'ok' }) });
	});

	await page.goto('/');
	const link = page.getByLabel('Download link');
	const folder = page.getByRole('radio', { name: destination });
	await link.fill(magnet);
	await page.getByRole('button', { name: 'Add Download', exact: true }).click();

	await expect(page.getByRole('button', { name: 'Adding Download…' })).toBeDisabled();
	await expect(page.getByRole('status')).toHaveText('Adding Download…');
	await expect(link).toHaveValue(magnet);
	await expect(folder).toBeChecked();
	expect(addRequests).toBe(1);

	acknowledge();
	await expect(page.getByRole('status')).toHaveText('Download added.');
	await expect(link).toHaveValue('');
	await expect(folder).toBeChecked();
});

test('Add Download associates missing and invalid link corrections with the form without sending a request', async ({ page }) => {
	let addRequests = 0;
	await installAddDownloadApi(page, async (route) => {
		addRequests += 1;
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 7 }) });
	});

	await page.goto('/');
	const link = page.getByLabel('Download link');
	await page.getByRole('button', { name: 'Add Download', exact: true }).click();
	await expect(page.getByRole('alert')).toHaveText('Enter a Download link to continue.');
	expect(await link.getAttribute('aria-describedby')).toContain('add-form-feedback');

	for (const invalidLink of ['https://example.test/file.torrent', 'magnet:not-a-link']) {
		await link.fill(invalidLink);
		await page.getByRole('button', { name: 'Add Download', exact: true }).click();
		await expect(page.getByRole('alert')).toHaveText('Enter a valid magnet link that starts with magnet:?.');
	}
	expect(addRequests).toBe(0);
});

test('Add Download retains safe input and the selected Download Folder after rejected and network-failed submissions', async ({ page }) => {
	let attempts = 0;
	await installAddDownloadApi(page, async (route) => {
		attempts += 1;
		if (attempts === 1) {
			await route.fulfill({
				status: 400,
				contentType: 'application/json',
				body: JSON.stringify({ error: `Rejected ${magnet}` })
			});
			return;
		}
		await route.abort('failed');
	});

	await page.goto('/');
	const link = page.getByLabel('Download link');
	const folder = page.getByRole('radio', { name: destination });
	await link.fill(magnet);
	await page.getByRole('button', { name: 'Add Download', exact: true }).click();
	await expect(page.getByRole('alert')).toHaveText('We could not add that Download. Check the Download link and Download Folder, then try again.');
	await expect(link).toHaveValue(magnet);
	await expect(folder).toBeChecked();
	await expect(page.locator('body')).not.toContainText(`Rejected ${magnet}`);

	await page.getByRole('button', { name: 'Add Download', exact: true }).click();
	await expect(page.getByRole('alert')).toHaveText('Could not reach Downloads. Check your connection and try again.');
	await expect(link).toHaveValue(magnet);
	await expect(folder).toBeChecked();
});
