import { expect, test } from '@playwright/test';

const destination = '/storage/primary/Downloads';

function download(id, name, state, progress = 0.25, extra = {}) {
	return {
		id,
		name,
		state,
		progress,
		downloadRate: 0,
		uploadRate: 0,
		peers: 0,
		savePath: destination,
		...extra
	};
}

async function installQueueApi(page, downloads) {
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

function sectionNames(page, labelledBy) {
	return page.locator(`section[aria-labelledby="${labelledBy}"] article h3`).allTextContents();
}

test('consumer queue maps backend states, promotes actionable Downloads, and preserves backend order within each section', async ({ page }) => {
	const longName = 'A very long family Download name '.repeat(16);
	await installQueueApi(page, [
		download(91, 'Preparing metadata', 'downloading_metadata'),
		download(92, 'Metadata only', 'metadata'),
		download(93, 'Restore check hyphenated', 'restore-check'),
		download(12, 'Paused by household', 'paused'),
		download(73, 'Finished first', 'finished', 1),
		download(74, 'Completed state literal', 'completed', 1),
		download(75, 'Finished folder unavailable', 'finished', 1, { destinationStatus: 'destination_unavailable' }),
		download(76, 'Completed folder unavailable', 'completed', 1, { destinationStatus: 'destination_unavailable' }),
		download(77, 'Sharing folder unavailable', 'seeding', 1, { destinationStatus: 'destination_unavailable' }),
		download(2, 'Storage unavailable', 'downloading', .3, { destinationStatus: 'destination_unavailable' }),
		download(22, 'Storage conflict', 'paused', .3, { destinationStatus: 'storage_conflict' }),
		download(55, 'Allocating space', 'allocating'),
		download(7, 'Sharing family album', 'seeding', 1),
		download(66, 'Move interrupted', 'paused', .6, { moveState: 'move-interrupted' }),
		download(19, 'Downloading now', 'downloading'),
		download(100, 'Checking files', 'checking_files'),
		download(3, 'Queued for checking', 'queued_for_checking'),
		download(8, 'Restore check', 'restore_check'),
		download(4, 'Resume check', 'checking_resume_data'),
		download(5, 'Pausing safely', 'pause_requested'),
		download(6, 'Moving files now', 'move_storage'),
		download(16, 'Moving files hyphenated', 'move-storage'),
		download(17, 'Journaled move', 'paused', .5, { moveState: 'journal-persisted' }),
		download(9, 'Copying move', 'paused', .5, { moveState: 'copying' }),
		download(18, 'Verifying move', 'paused', .5, { moveState: 'verifying' }),
		download(20, 'Queue update move', 'paused', .5, { moveState: 'queue-updated' }),
		download(21, 'Source removal move', 'paused', .5, { moveState: 'source-removed' }),
		download(10, 'Move conflict', 'downloading', .5, { moveState: 'storage-conflict' }),
		download(11, 'Native error', 'error'),
		download(23, 'Native underscore error', 'native_error'),
		download(24, 'Native hyphen error', 'native-error'),
		download(13, 'Unknown backend state', 'unknown(42)'),
		download(14, longName, 'downloading')
	]);
	await page.goto('/');

	await expect(page.getByRole('heading', { name: 'Needs Attention' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Active' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Completed', exact: true })).toBeVisible();
	await expect(sectionNames(page, 'needs-attention-heading')).resolves.toEqual([
		'Storage unavailable', 'Storage conflict', 'Move interrupted', 'Move conflict', 'Native error', 'Native underscore error', 'Native hyphen error', 'Unknown backend state'
	]);
	await expect(sectionNames(page, 'active-heading')).resolves.toEqual([
		'Preparing metadata', 'Metadata only', 'Restore check hyphenated', 'Paused by household', 'Allocating space', 'Downloading now', 'Checking files',
		'Queued for checking', 'Restore check', 'Resume check', 'Pausing safely', 'Moving files now', 'Moving files hyphenated', 'Journaled move', 'Copying move', 'Verifying move', 'Queue update move', 'Source removal move', longName.trim()
	]);
	await expect(sectionNames(page, 'completed-heading')).resolves.toEqual([
		'Finished first', 'Completed state literal', 'Finished folder unavailable', 'Completed folder unavailable', 'Sharing folder unavailable', 'Sharing family album'
	]);

	for (const [name, consumerState] of [
		['Preparing metadata', 'Preparing'], ['Metadata only', 'Preparing'], ['Restore check hyphenated', 'Preparing'], ['Paused by household', 'Paused'], ['Finished first', 'Complete'], ['Completed state literal', 'Complete'], ['Finished folder unavailable', 'Complete'], ['Completed folder unavailable', 'Complete'], ['Sharing folder unavailable', 'Complete'],
		['Storage unavailable', 'Needs attention'], ['Storage conflict', 'Needs attention'], ['Allocating space', 'Preparing'], ['Sharing family album', 'Complete'],
		['Move interrupted', 'Needs attention'], ['Downloading now', 'Downloading'], ['Checking files', 'Preparing'],
		['Queued for checking', 'Preparing'], ['Restore check', 'Preparing'], ['Resume check', 'Preparing'],
		['Pausing safely', 'Pausing'], ['Moving files now', 'Moving files'], ['Moving files hyphenated', 'Moving files'], ['Journaled move', 'Moving files'], ['Copying move', 'Moving files'], ['Verifying move', 'Moving files'], ['Queue update move', 'Moving files'], ['Source removal move', 'Moving files'],
		['Move conflict', 'Needs attention'], ['Native error', 'Needs attention'], ['Native underscore error', 'Needs attention'], ['Native hyphen error', 'Needs attention'], ['Unknown backend state', 'Needs attention']
	]) {
		const card = page.locator('article', { has: page.getByRole('heading', { name, exact: true }) });
		await expect(card.getByText(consumerState, { exact: true })).toBeVisible();
	}

	await expect(page.locator('article', { has: page.getByRole('heading', { name: 'Sharing folder unavailable' }) }))
		.toContainText('This Download Folder is unavailable.');

	const progress = page.getByRole('progressbar', { name: 'Downloading now: Downloading, 25.0% complete' });
	await expect(progress).toHaveAttribute('value', '25');
	await expect(page.locator('body')).not.toContainText(/download speed|upload speed|peers/i);
	const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
	expect(dimensions.width).toBeLessThanOrEqual(dimensions.viewport);
});

test('consumer queue covers empty queue, active-only, and completed-only empty states without an empty attention section', async ({ page }) => {
	await installQueueApi(page, []);
	await page.goto('/');
	await expect(page.getByText('Add your first Download to see its progress here.')).toBeVisible();
	await expect(page.getByText('Completed Downloads will appear here.')).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Needs Attention' })).toHaveCount(0);

	await page.unroute('**/api/torrents');
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify([download(1, 'Only active', 'downloading')])
	}));
	await page.reload();
	await expect(page.getByRole('heading', { name: 'Needs Attention' })).toHaveCount(0);
	await expect(page.getByText('Completed Downloads will appear here.')).toBeVisible();

	await page.unroute('**/api/torrents');
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify([download(2, 'Only complete', 'finished', 1)])
	}));
	await page.reload();
	await expect(page.getByText('Nothing is active right now.')).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Needs Attention' })).toHaveCount(0);
});
