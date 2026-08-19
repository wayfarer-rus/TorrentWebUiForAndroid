import { expect, test } from '@playwright/test';

const source = '/storage/primary/Downloads';
const target = '/storage/USB/Family';
const otherTarget = '/storage/primary/Archive';

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

async function installMoveApi(page, snapshots, catalog = [source, target, otherTarget]) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ completed: true, passwordDecision: 'deferred', hasApprovedDestination: true, readiness: 'Ready' })
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: 'Ready' })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(catalog) }));
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

test('Move files confirms complete canonical paths, waits for acknowledgement, and leaves unrelated cards usable', async ({ page }) => {
	const snapshots = {
		current: [
			download(1, 'Family album', 'downloading'),
			download(2, 'Podcast archive', 'paused', { destinationPath: otherTarget, savePath: otherTarget })
		]
	};
	let acknowledgeMove;
	const moveAcknowledgement = new Promise((resolve) => { acknowledgeMove = resolve; });
	let moveRequests = 0;
	let resumeRequests = 0;
	await installMoveApi(page, snapshots);
	await page.route('**/api/torrents/1/move/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ phase: 'copying', sourcePath: source, targetPath: target })
	}));
	await page.route('**/api/torrents/1/move', async (route) => {
		moveRequests += 1;
		expect(route.request().postDataJSON()).toEqual({ destinationPath: target });
		await moveAcknowledgement;
		snapshots.current = [
			download(1, 'Family album', 'paused', { moveState: 'copying' }),
			download(2, 'Podcast archive', 'paused', { destinationPath: otherTarget, savePath: otherTarget })
		];
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok', phase: 'moving' }) });
	});
	await page.route('**/api/torrents/2/resume', (route) => {
		resumeRequests += 1;
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok' }) });
	});

	await page.goto('/');
	const familyCard = card(page, 'Family album');
	const podcastCard = card(page, 'Podcast archive');
	await familyCard.getByRole('button', { name: 'Move files' }).click();
	await expect(familyCard.getByText('Move to an approved Download Folder')).toBeVisible();
	await expect(familyCard.getByRole('radio')).toHaveCount(2);
	await expect(familyCard.getByRole('radio', { name: source })).toHaveCount(0);
	await familyCard.getByRole('radio', { name: target }).check();
	await familyCard.getByRole('button', { name: 'Continue' }).click();

	const confirmation = familyCard.getByRole('dialog');
	await expect(confirmation).toContainText('Move Family album?');
	await expect(confirmation).toContainText(source);
	await expect(confirmation).toContainText(target);
	await confirmation.getByRole('button', { name: 'Move files' }).click();
	await expect(confirmation.getByRole('button', { name: 'Starting move…' })).toBeDisabled();
	await expect(familyCard.getByText('Moving files', { exact: true })).toHaveCount(0);
	await expect(podcastCard.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
	await podcastCard.getByRole('button', { name: 'Resume', exact: true }).click();
	expect(resumeRequests).toBe(1);
	expect(moveRequests).toBe(1);

	acknowledgeMove();
	await expect(familyCard.getByText('Moving files', { exact: true }).first()).toBeVisible();
	await expect(familyCard.getByRole('button', { name: 'Move files' })).toBeDisabled();
	await expect(familyCard).toContainText('Move request accepted. Moving files.');
	await expect(familyCard.getByLabel('Download Folder')).toHaveValue(source);
	await expect(podcastCard.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();

	// The folder remains source-authoritative until the backend's completed snapshot arrives.
	snapshots.current = [
		download(1, 'Family album', 'finished', { destinationPath: target, savePath: target }),
		download(2, 'Podcast archive', 'paused', { destinationPath: otherTarget, savePath: otherTarget })
	];
	await page.reload();
	await expect(card(page, 'Family album').getByLabel('Download Folder')).toHaveValue(target);
});

test('interrupted moves expose only valid recovery, make conflicts actionable, and cancellation keeps the source destination', async ({ page }) => {
	const snapshots = {
		current: [download(1, 'Family album', 'paused', { moveState: 'move-interrupted' })]
	};
	let retryRequests = 0;
	await installMoveApi(page, snapshots);
	await page.route('**/api/torrents/1/move/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ phase: 'move-interrupted', sourcePath: source, targetPath: target })
	}));
	await page.route('**/api/torrents/1/move/retry', (route) => {
		retryRequests += 1;
		expect(route.request().postDataJSON()).toEqual({ destinationPath: target });
		return route.fulfill({
			status: 409,
			contentType: 'application/json',
			body: JSON.stringify({ status: 'storage_conflict', phase: 'storage-conflict', error: 'private backend diagnostic' })
		});
	});
	await page.route('**/api/torrents/1/move/cancel', (route) => {
		snapshots.current = [download(1, 'Family album', 'paused')];
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok' }) });
	});

	await page.goto('/');
	const familyCard = card(page, 'Family album');
	await expect(familyCard.getByRole('button', { name: 'Retry move' })).toBeVisible();
	await expect(familyCard.getByRole('button', { name: 'Cancel move' })).toBeVisible();
	await familyCard.getByRole('button', { name: 'Retry move' }).click();
	expect(retryRequests).toBe(1);
	await expect(familyCard.getByText('Needs attention: files already exist at the target. Nothing was overwritten. Resolve the conflict or choose a different approved Download Folder.', { exact: true })).toBeVisible();
	await expect(familyCard).not.toContainText('private backend diagnostic');
	await expect(familyCard.getByLabel('Download Folder')).toHaveValue(source);

	await familyCard.getByRole('button', { name: 'Cancel move' }).click();
	await expect(familyCard).toContainText('Move cancelled. The Download Folder has not changed and files were not removed.');
	await expect(familyCard.getByLabel('Download Folder')).toHaveValue(source);
	await expect(familyCard.getByRole('button', { name: 'Retry move' })).toHaveCount(0);
	await expect(familyCard.getByRole('button', { name: 'Cancel move' })).toHaveCount(0);
});

test('recovery with no longer-approved target offers cancellation but not an invalid retry', async ({ page }) => {
	const snapshots = { current: [download(1, 'Family album', 'paused', { moveState: 'move-interrupted' })] };
	await installMoveApi(page, snapshots, [source, target]);
	await page.route('**/api/torrents/1/move/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ phase: 'move-interrupted', sourcePath: source, targetPath: '/storage/removed-target' })
	}));

	await page.goto('/');
	const familyCard = card(page, 'Family album');
	await expect(familyCard.getByRole('button', { name: 'Retry move' })).toHaveCount(0);
	await expect(familyCard.getByRole('button', { name: 'Cancel move' })).toBeVisible();
	await expect(familyCard).toContainText('The interrupted target cannot be retried safely. Cancel this move, then choose another approved Download Folder if needed.');
	await expect(familyCard.getByRole('button', { name: 'Cancel move' })).toHaveClass(/primary-button/);
});

test('unavailable target storage gives recovery guidance without claiming that files moved', async ({ page }) => {
	const snapshots = { current: [download(1, 'Family album', 'downloading')] };
	await installMoveApi(page, snapshots, [source, target]);
	await page.route('**/api/torrents/1/move', (route) => route.fulfill({
		status: 503,
		contentType: 'application/json',
		body: JSON.stringify({ error: 'internal storage fixture detail' })
	}));

	await page.goto('/');
	const familyCard = card(page, 'Family album');
	await familyCard.getByRole('button', { name: 'Move files' }).click();
	await familyCard.getByRole('button', { name: 'Continue' }).click();
	await familyCard.getByRole('dialog').getByRole('button', { name: 'Move files' }).click();
	await expect(familyCard.getByRole('alert')).toHaveText('The target storage is unavailable. Reconnect it or restore storage access in the Android app, then try again.');
	await expect(familyCard.getByLabel('Download Folder')).toHaveValue(source);
	await expect(familyCard).not.toContainText('internal storage fixture detail');
});
