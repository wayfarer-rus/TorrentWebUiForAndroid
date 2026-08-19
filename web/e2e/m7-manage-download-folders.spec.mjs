import { expect, test } from '@playwright/test';

const primary = '/storage/primary';
const existing = `${primary}/Downloads`;
const removable = '/storage/USB';
const removableChild = `${removable}/Movies`;
const removableAlias = `${removable}/Aliases/Family movies`;
const temporary = `${primary}/Temporary`;

async function installFolderApi(page, options = {}) {
	let catalog = options.catalog ?? [existing, temporary];
	let permission = options.permission ?? 'Ready';
	const approved = [];
	const forgotten = [];

	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ completed: true, passwordDecision: 'deferred', hasApprovedDestination: true, readiness: 'Ready' })
	}));
	await page.route('**/api/torrents', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: permission })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify([
			{ path: primary, description: 'Internal storage', isRemovable: false },
			{ path: removable, description: 'USB drive', isRemovable: true }
		])
	}));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(catalog)
	}));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ path: catalog[0] ?? null })
	}));
	await page.route('**/api/storage/children', (route) => {
		const { path } = route.request().postDataJSON();
		if (path === removable && options.removableUnavailable) {
			return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'drive disappeared' }) });
		}
		const children = path === primary ? [existing, temporary] : path === removable ? [removableChild] : [];
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify(children) });
	});
	await page.route('**/api/storage/validate', (route) => {
		const { path } = route.request().postDataJSON();
		if (path === removableAlias) {
			return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ isValid: true, canonicalPath: removableChild }) });
		}
		return route.fulfill({
			contentType: 'application/json',
			body: JSON.stringify({ isValid: false, canonicalPath: null, rejectionReason: 'Path is outside reported storage volumes' })
		});
	});
	await page.route('**/api/storage/destinations', (route) => {
		const { path } = route.request().postDataJSON();
		if (route.request().method() === 'POST') {
			approved.push(path);
			catalog = [...new Set([...catalog, path])];
			return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok', path }) });
		}
		if (path === existing) {
			return route.fulfill({
				status: 409,
				contentType: 'application/json',
				body: JSON.stringify({ error: 'referenced' })
			});
		}
		forgotten.push(path);
		catalog = catalog.filter((entry) => entry !== path);
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok' }) });
	});

	return {
		approved,
		forgotten,
		setPermission: (value) => { permission = value; }
	};
}

async function openFolders(page) {
	await page.getByRole('button', { name: 'Open settings' }).click();
	const dialog = page.getByRole('dialog', { name: 'Settings' });
	await dialog.getByRole('button', { name: /Download folders/ }).click();
	await expect(dialog.getByRole('heading', { name: 'Download folders' })).toBeVisible();
	return dialog;
}

test('Settings browses only reported primary and removable volumes, canonicalizes, and durably approves a folder', async ({ page }) => {
	const api = await installFolderApi(page);
	await page.goto('/');
	const dialog = await openFolders(page);

	await dialog.getByRole('button', { name: 'Browse storage' }).click();
	await expect(dialog.getByRole('heading', { name: 'Browse storage' })).toBeVisible();
	await expect(dialog.getByRole('button', { name: /Internal storage/ })).toContainText(primary);
	await expect(dialog.getByRole('button', { name: /USB drive/ })).toContainText(removable);
	await dialog.getByRole('button', { name: /Internal storage/ }).click();
	await expect(dialog).toContainText(existing);
	await dialog.getByRole('button', { name: /USB drive/ }).click();
	await expect(dialog).toContainText(removableChild);

	await page.getByLabel('Or paste an absolute path').fill(removableAlias);
	await dialog.getByRole('button', { name: 'Check folder' }).click();
	await expect(dialog).toContainText('Verified canonical path');
	await expect(dialog).toContainText(removableChild);
	await dialog.getByRole('button', { name: 'Approve this folder' }).click();

	await expect(dialog.getByRole('heading', { name: 'Download folders' })).toBeVisible();
	await expect(dialog.getByLabel(`Approved Download Folder: ${removableChild}`)).toHaveValue(removableChild);
	expect(api.approved).toEqual([removableChild]);
});

test('Settings rejects document links and out-of-volume paths, and explains unavailable storage recovery', async ({ page }) => {
	await installFolderApi(page, { removableUnavailable: true });
	await page.goto('/');
	const dialog = await openFolders(page);
	await dialog.getByRole('button', { name: 'Browse storage' }).click();

	await page.getByLabel('Or paste an absolute path').fill('content://documents/tree/USB');
	await dialog.getByRole('button', { name: 'Check folder' }).click();
	await expect(dialog).toContainText('not a document link or alias');

	await page.getByLabel('Or paste an absolute path').fill('/outside/reported-volumes');
	await dialog.getByRole('button', { name: 'Check folder' }).click();
	await expect(dialog).toContainText('inside one of the reported storage volumes');

	await dialog.getByRole('button', { name: /USB drive/ }).click();
	await expect(dialog.getByRole('alert')).toContainText('storage volume is no longer available');
	await expect(dialog.getByRole('alert')).toContainText('Reconnect the storage');
});

test('Settings prevents forgetting a referenced folder but refreshes the shared catalog after a successful removal', async ({ page }) => {
	const api = await installFolderApi(page);
	await page.goto('/');
	const dialog = await openFolders(page);

	await dialog.getByRole('button', { name: `Forget ${existing}` }).click();
	await expect(dialog.getByRole('alert')).toHaveText('This Download Folder is still used by a Download. Move or remove that Download before forgetting this folder.');
	await expect(dialog.getByLabel(`Approved Download Folder: ${existing}`)).toHaveValue(existing);

	await dialog.getByRole('button', { name: `Forget ${temporary}` }).click();
	await expect(dialog.getByLabel(`Approved Download Folder: ${temporary}`)).toHaveCount(0);
	expect(api.forgotten).toEqual([temporary]);
});

test('folder browsing Back stays in Download folders and revoked storage gives Android recovery guidance', async ({ page }) => {
	const api = await installFolderApi(page);
	await page.goto('/');
	const dialog = await openFolders(page);
	await dialog.getByRole('button', { name: 'Browse storage' }).click();
	await dialog.getByRole('button', { name: 'Back to Download folders' }).click();
	await expect(dialog.getByRole('heading', { name: 'Download folders' })).toBeVisible();

	api.setPermission('RevokedRuntime');
	await dialog.getByRole('button', { name: 'Browse storage' }).click();
	await expect(dialog).toContainText('Storage access was removed');
	await expect(dialog).toContainText('restore storage access');
});
