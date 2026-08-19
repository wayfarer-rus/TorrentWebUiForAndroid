import { expect, test } from '@playwright/test';

const destination = '/storage/primary/Downloads';

function download(id, name, state) {
	return {
		id,
		name,
		state,
		progress: .25,
		downloadRate: 0,
		uploadRate: 0,
		peers: 0,
		savePath: destination
	};
}

async function installDownloadApi(page, snapshots) {
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
		contentType: 'application/json', body: JSON.stringify(snapshots.current)
	}));
}

test('Pause waits for durable acknowledgement, prevents duplicates, and does not block neighboring cards', async ({ page }) => {
	const snapshots = {
		current: [
			download(1, 'Family album', 'downloading'),
			download(2, 'Podcast archive', 'paused'),
			download(3, 'Already pausing', 'pause_requested')
		]
	};
	let pauseRequests = 0;
	let acknowledgePause;
	const pauseAcknowledgement = new Promise((resolve) => { acknowledgePause = resolve; });
	await installDownloadApi(page, snapshots);
	await page.route('**/api/torrents/1/pause', async (route) => {
		pauseRequests += 1;
		await pauseAcknowledgement;
		snapshots.current = [
			download(1, 'Family album', 'pause_requested'),
			download(2, 'Podcast archive', 'paused'),
			download(3, 'Already pausing', 'pause_requested')
		];
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok', queueId: 'queue-1' }) });
	});

	await page.goto('/');
	const familyCard = page.locator('article', { has: page.getByRole('heading', { name: 'Family album' }) });
	const podcastCard = page.locator('article', { has: page.getByRole('heading', { name: 'Podcast archive' }) });
	const pause = familyCard.getByRole('button', { name: 'Pause', exact: true });
	const familyAction = familyCard.locator('button.primary-button');
	const resume = podcastCard.getByRole('button', { name: 'Resume', exact: true });

	await expect(pause).toBeVisible();
	await expect(resume).toBeEnabled();
	await expect(page.locator('article', { has: page.getByRole('heading', { name: 'Already pausing' }) }).getByRole('button', { name: /^(Pause|Resume)$/ })).toHaveCount(0);
	await pause.focus();
	await pause.press('Enter');
	await expect(familyAction).toHaveText('Pausing…');
	await expect(familyAction).toBeDisabled();
	await expect(familyCard.getByText('Downloading', { exact: true })).toBeVisible();
	await expect(resume).toBeEnabled();

	// A synthetic repeated activation reaches the handler even though normal UI input is disabled.
	await familyAction.dispatchEvent('click');
	expect(pauseRequests).toBe(1);

	acknowledgePause();
	await expect(familyCard.getByText('Pausing', { exact: true })).toBeVisible();
	await expect(familyCard.getByRole('button', { name: /^(Pause|Resume)$/ })).toHaveCount(0);
	await expect(page.locator('#download-1-card')).toBeFocused();
	await expect(resume).toBeEnabled();
});

test('Pause failure restores the same focused action with consumer guidance', async ({ page }) => {
	const snapshots = { current: [download(1, 'Family album', 'downloading')] };
	await installDownloadApi(page, snapshots);
	await page.route('**/api/torrents/1/pause', (route) => route.fulfill({
		status: 409,
		contentType: 'application/json',
		body: JSON.stringify({ error: 'native implementation detail that must not be shown' })
	}));

	await page.goto('/');
	const pause = page.getByRole('button', { name: 'Pause', exact: true });
	await pause.focus();
	await pause.press('Enter');
	await expect(page.getByRole('alert')).toHaveText('This Download could not be paused. Try again.');
	await expect(pause).toBeEnabled();
	await expect(pause).toBeFocused();
	await expect(page.locator('body')).not.toContainText('native implementation detail that must not be shown');
});

test('Resume waits for acknowledgement before the backend snapshot presents Downloading', async ({ page }) => {
	const snapshots = { current: [download(1, 'Family album', 'paused')] };
	let acknowledgeResume;
	const resumeAcknowledgement = new Promise((resolve) => { acknowledgeResume = resolve; });
	await installDownloadApi(page, snapshots);
	await page.route('**/api/torrents/1/resume', async (route) => {
		await resumeAcknowledgement;
		snapshots.current = [download(1, 'Family album', 'downloading')];
		await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ok', queueId: 'queue-1' }) });
	});

	await page.goto('/');
	const card = page.locator('article', { has: page.getByRole('heading', { name: 'Family album' }) });
	const resume = card.getByRole('button', { name: 'Resume', exact: true });
	const action = card.locator('button.primary-button');
	await resume.focus();
	await resume.press('Enter');
	await expect(action).toHaveText('Resuming…');
	await expect(action).toBeDisabled();
	await expect(page.getByText('Paused', { exact: true })).toBeVisible();

	acknowledgeResume();
	await expect(page.getByText('Downloading', { exact: true })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
	await expect(page.locator('#download-1-card')).toBeFocused();
});
