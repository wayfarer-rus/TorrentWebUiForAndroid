import { expect, test } from '@playwright/test';

const destination = '/storage/primary/Downloads';

function download(id, name, state = 'finished') {
	return { id, name, state, progress: 1, downloadRate: 0, uploadRate: 0, peers: 0, savePath: destination };
}

async function installRemovalApi(page, snapshots) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ completed: true, passwordDecision: 'deferred', hasApprovedDestination: true, readiness: 'Ready' })
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ state: 'Ready' }) }));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({ contentType: 'application/json', body: '[]' }));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify([destination]) }));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ path: destination }) }));
	await page.route('**/api/torrents', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(snapshots.current) }));
}

function card(page, name) {
	return page.locator('article', { has: page.getByRole('heading', { name }) });
}

test('Remove from list confirms retained downloaded and partial files, supports cancellation, and waits for acknowledgement', async ({ page }) => {
	const snapshots = { current: [download(1, 'Family album'), download(2, 'Podcast archive', 'paused')] };
	let acknowledgeRemoval;
	const removalAcknowledgement = new Promise((resolve) => { acknowledgeRemoval = resolve; });
	const outcomes = [];
	await installRemovalApi(page, snapshots);
	await page.route(/\/api\/torrents\/1\?deleteFiles=false$/, async (route) => {
		outcomes.push({ retainedFiles: true, deleteFiles: false });
		await removalAcknowledgement;
		snapshots.current = [download(2, 'Podcast archive', 'paused')];
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok', queueId: 'queue-1' }) });
	});

	await page.goto('/');
	const familyCard = card(page, 'Family album');
	const podcastCard = card(page, 'Podcast archive');
	const removeFromList = familyCard.getByRole('button', { name: 'Remove from list' });
	await removeFromList.focus();
	await removeFromList.press('Enter');

	const confirmation = page.getByRole('alertdialog');
	await expect(confirmation).toContainText('Remove Family album from list?');
	await expect(confirmation).toContainText('Downloaded and partial files will remain in its Download Folder.');
	await expect(confirmation.getByRole('button', { name: 'Remove from list' })).toBeFocused();
	await confirmation.getByRole('button', { name: 'Cancel' }).click();
	await expect(confirmation).toHaveCount(0);
	await expect(removeFromList).toBeFocused();

	await removeFromList.click();
	await confirmation.getByRole('button', { name: 'Remove from list' }).click();
	await expect(confirmation.getByRole('button', { name: 'Removing from list…' })).toBeDisabled();
	await expect(familyCard.getByRole('button', { name: 'Removing from list…' })).toBeDisabled();
	await expect(familyCard.getByRole('button', { name: 'Remove and delete files' })).toBeEnabled();
	await expect(familyCard).toBeVisible();
	await expect(podcastCard.getByRole('button', { name: 'Resume' })).toBeEnabled();
	expect(outcomes).toEqual([{ retainedFiles: true, deleteFiles: false }]);

	acknowledgeRemoval();
	await expect(familyCard).toHaveCount(0);
	await expect(podcastCard).toBeVisible();
});

test('Remove and delete files has a distinct destructive confirmation and waits for the deleted-files outcome', async ({ page }) => {
	const snapshots = { current: [download(3, 'Old recordings')] };
	const outcomes = [];
	await installRemovalApi(page, snapshots);
	await page.route(/\/api\/torrents\/3\?deleteFiles=true$/, async (route) => {
		outcomes.push({ deletedFiles: true, deleteFiles: true });
		snapshots.current = [];
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok', queueId: 'queue-3' }) });
	});

	await page.goto('/');
	const recordingsCard = card(page, 'Old recordings');
	const destructiveAction = recordingsCard.getByRole('button', { name: 'Remove and delete files' });
	await destructiveAction.click();
	const confirmation = page.getByRole('alertdialog');
	await expect(confirmation).toContainText('Remove Old recordings and delete files?');
	await expect(confirmation).toContainText('Downloaded and partial files for this Download will be deleted from its Download Folder.');
	await expect(confirmation).not.toContainText(/undo|recover/i);
	await confirmation.getByRole('button', { name: 'Remove and delete files' }).click();

	expect(outcomes).toEqual([{ deletedFiles: true, deleteFiles: true }]);
	await expect(recordingsCard).toHaveCount(0);
});

test('Removal failure restores the initiating action, keeps the card, and hides backend diagnostics', async ({ page }) => {
	const snapshots = { current: [download(4, 'School photos')] };
	await installRemovalApi(page, snapshots);
	await page.route(/\/api\/torrents\/4\?deleteFiles=true$/, (route) => route.fulfill({
		status: 409,
		contentType: 'application/json',
		body: JSON.stringify({ error: 'private native removal diagnostic' })
	}));

	await page.goto('/');
	const photosCard = card(page, 'School photos');
	const destructiveAction = photosCard.getByRole('button', { name: 'Remove and delete files' });
	await destructiveAction.focus();
	await destructiveAction.press('Enter');
	await page.getByRole('alertdialog').getByRole('button', { name: 'Remove and delete files' }).click();

	await expect(page.getByRole('alert')).toHaveText('This Download and its files could not be removed. Try again.');
	await expect(photosCard).toBeVisible();
	await expect(destructiveAction).toBeEnabled();
	await expect(destructiveAction).toBeFocused();
	await expect(page.locator('body')).not.toContainText('private native removal diagnostic');
});
