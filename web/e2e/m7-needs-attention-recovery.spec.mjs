import { expect, test } from '@playwright/test';

const source = '/storage/primary/Downloads';
const target = '/storage/USB/Family';

function download(id, name, state, extra = {}) {
	return {
		id,
		name,
		state,
		progress: .4,
		downloadRate: 0,
		uploadRate: 0,
		peers: 0,
		savePath: source,
		destinationPath: source,
		...extra
	};
}

async function installApi(page, snapshots, catalog = [source, target]) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ completed: true, passwordDecision: 'deferred', hasApprovedDestination: true, readiness: 'Ready' })
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: 'Ready' })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(catalog)
	}));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ path: source })
	}));
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(snapshots.current)
	}));
}

function card(page, name) {
	return page.locator('article', { has: page.getByRole('heading', { name }) });
}

test('Needs Attention maps storage, move, native, and unknown states to curated recovery without backend diagnostics', async ({ page }) => {
	const secret = 'private tracker token and native stack trace';
	const snapshots = {
		current: [
			download(1, 'Missing USB', 'downloading', { destinationStatus: 'destination_unavailable' }),
			download(2, 'Folder conflict', 'paused', { destinationStatus: 'storage_conflict' }),
			download(3, 'Interrupted move', 'paused', { moveState: 'move-interrupted' }),
			download(4, 'Conflicting move', 'paused', { moveState: 'storage-conflict' }),
			download(5, 'Native failure', 'native_error', { error: secret }),
			download(6, 'Unexpected state', 'unknown_backend_state', { error: secret }),
			download(7, 'Healthy neighbor', 'paused')
		]
	};
	await installApi(page, snapshots);
	await page.route('**/api/torrents/3/move/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ phase: 'move-interrupted', sourcePath: source, targetPath: target })
	}));
	await page.route('**/api/torrents/4/move/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ phase: 'storage-conflict', sourcePath: source, targetPath: target })
	}));

	await page.goto('/');
	await expect(card(page, 'Missing USB')).toContainText('This Download Folder is unavailable. Reconnect storage or restore storage access in the Android app, then choose the folder in Settings.');
	await expect(card(page, 'Missing USB').getByRole('button', { name: 'Fix problem' })).toBeVisible();
	await expect(card(page, 'Folder conflict')).toContainText('This Download Folder needs attention. In Settings, choose a different approved Download Folder or restore storage access in the Android app.');
	await expect(card(page, 'Interrupted move')).toContainText('The move stopped before completion. The Download Folder has not changed and source files were not removed.');
	await expect(card(page, 'Interrupted move').getByRole('button', { name: 'Retry move' })).toBeVisible();
	await expect(card(page, 'Conflicting move')).toContainText('Files already exist at the target. Nothing was overwritten. Resolve the conflict before retrying the move.');
	await expect(card(page, 'Conflicting move').getByRole('button', { name: 'Retry move' })).toBeVisible();
	await expect(card(page, 'Native failure')).toContainText('This Download needs attention. Check Downloads in the Android app, then return here.');
	await expect(card(page, 'Unexpected state')).toContainText('This Download needs attention. Check Downloads in the Android app, then return here.');
	await expect(card(page, 'Native failure').getByRole('button', { name: 'Fix problem' })).toHaveCount(0);
	await expect(card(page, 'Healthy neighbor').getByRole('button', { name: 'Resume' })).toBeEnabled();
	await expect(page.locator('body')).not.toContainText(secret);

	await card(page, 'Missing USB').getByRole('button', { name: 'Fix problem' }).click();
	await expect(page.getByRole('dialog', { name: 'Settings' }).getByRole('heading', { name: 'Download folders' })).toBeVisible();
});

test('a recoverable move with no approved target makes Cancel move the primary safe action', async ({ page }) => {
	const snapshots = { current: [download(1, 'Interrupted move', 'paused', { moveState: 'move-interrupted' })] };
	await installApi(page, snapshots, [source]);
	await page.route('**/api/torrents/1/move/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ phase: 'move-interrupted', sourcePath: source, targetPath: target })
	}));

	await page.goto('/');
	const interrupted = card(page, 'Interrupted move');
	await expect(interrupted.getByRole('button', { name: 'Retry move' })).toHaveCount(0);
	await expect(interrupted.getByRole('button', { name: 'Cancel move' })).toHaveClass(/primary-button/);
	await expect(interrupted).toContainText('The interrupted target cannot be retried safely. Cancel this move, then choose another approved Download Folder if needed.');
});

test('a live-update disconnect retains stale cards and a replacement snapshot reconnects without duplicates', async ({ page }) => {
	const snapshots = { current: [download(1, 'Last known Download', 'paused')] };
	await installApi(page, snapshots);
	const sockets = [];
	await page.routeWebSocket('/ws/progress', (socket) => sockets.push(socket));

	await page.goto('/');
	await expect(card(page, 'Last known Download')).toBeVisible();
	await expect.poll(() => sockets.length).toBe(1);
	await sockets[0].close({ code: 1011, reason: 'simulated interruption' });

	await expect(page.getByLabel('Service status')).toHaveText('Updates paused — reconnecting…');
	await expect(card(page, 'Last known Download')).toBeVisible();
	await expect.poll(() => sockets.length, { timeout: 5_000 }).toBe(2);

	sockets[1].send(JSON.stringify({
		type: 'torrents',
		data: [
			download(2, 'Fresh Download', 'downloading'),
			download(2, 'Duplicate backend record', 'downloading')
		]
	}));
	await expect(page.getByLabel('Service status')).toHaveText('Running');
	await expect(card(page, 'Last known Download')).toHaveCount(0);
	await expect(card(page, 'Fresh Download')).toHaveCount(1);
	await expect(card(page, 'Duplicate backend record')).toHaveCount(0);
});
