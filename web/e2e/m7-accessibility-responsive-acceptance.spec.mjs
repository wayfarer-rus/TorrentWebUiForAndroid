import { expect, test } from '@playwright/test';

const source = '/storage/primary/Downloads';
const target = '/storage/USB/Family';
const longName = `Family archive ${'with a deliberately long name '.repeat(18)}`.trim();
const longPath = `${source}/${'a-very-long-canonical-folder-name/'.repeat(16)}final-folder`;

function download(id, name, state, extra = {}) {
	return {
		id,
		name,
		state,
		progress: .5,
		downloadRate: 0,
		uploadRate: 0,
		peers: 0,
		savePath: source,
		destinationPath: source,
		...extra
	};
}

async function installAuthenticatedApi(page, downloads) {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ completed: true, passwordDecision: 'deferred', hasApprovedDestination: true, readiness: 'Ready' })
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ state: 'Ready' }) }));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify([{ path: '/storage/primary', description: 'Internal storage', isRemovable: false }])
	}));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify([source, target]) }));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ path: source }) }));
	await page.route('**/api/storage/children', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify([source, target]) }));
	await page.route('**/api/torrents', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(downloads) }));
}

function card(page, name) {
	return page.getByRole('article', { name });
}

async function expectNoHorizontalPageScroll(page) {
	const dimensions = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth }));
	expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.innerWidth);
}

async function expectActionsReachable(page) {
	const buttons = page.getByRole('button');
	for (let index = 0; index < await buttons.count(); index += 1) {
		const button = buttons.nth(index);
		if (!await button.isVisible()) continue;
		await button.scrollIntoViewIfNeeded();
		const box = await button.boundingBox();
		expect(box?.x).toBeGreaterThanOrEqual(0);
		expect((box?.x ?? 0) + (box?.width ?? 0)).toBeLessThanOrEqual((await page.viewportSize()).width);
	}
}

async function expectPrivateBrowserState(page, values) {
	const state = await page.evaluate(() => ({
		url: `${location.pathname}${location.search}${location.hash}`,
		history: JSON.stringify(history.state)
	}));
	for (const value of values) {
		expect(state.url).not.toContain(value);
		expect(state.history).not.toContain(value);
	}
}

function contrastRatio(first, second) {
	const luminance = (color) => {
		const channels = color.match(/\d+(?:\.\d+)?/g).slice(0, 3).map(Number).map((channel) => {
			const normalized = channel / 255;
			return normalized <= .04045 ? normalized / 12.92 : ((normalized + .055) / 1.055) ** 2.4;
		});
		return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
	};
	const [lighter, darker] = [luminance(first), luminance(second)].sort((a, b) => b - a);
	return (lighter + .05) / (darker + .05);
}

test('home semantic outcomes remain responsive, touchable, motion-safe, and free of technical home jargon', async ({ page }) => {
	await page.emulateMedia({ reducedMotion: 'reduce' });
	await installAuthenticatedApi(page, [
		download(1, longName, 'downloading', { destinationPath: longPath }),
		download(2, 'Finished family album', 'finished')
	]);
	await page.goto('/');

	await expect(page.getByRole('heading', { name: 'Downloads' })).toBeVisible();
	await expect(page.getByRole('progressbar', { name: `${longName}: Downloading, 50.0% complete` })).toHaveAttribute('value', '50');
	await expect(card(page, longName).getByRole('textbox', { name: 'Download Folder' })).toHaveValue(longPath);
	await expect(page.locator('body')).not.toContainText(/tracker|peer|libtorrent|bandwidth|queue|seeding/i);

	for (const viewport of [
		{ width: 360, height: 800 },
		{ width: 768, height: 1024 },
		{ width: 1440, height: 900 }
	]) {
		await page.setViewportSize(viewport);
		await expectNoHorizontalPageScroll(page);
		await expectActionsReachable(page);
	}

	await page.setViewportSize({ width: 360, height: 800 });
	for (const control of [
		page.getByRole('button', { name: 'Add Download', exact: true }),
		page.getByRole('button', { name: 'Open settings' }),
		card(page, longName).getByRole('button', { name: 'Pause', exact: true }),
		card(page, longName).getByRole('button', { name: 'Move files' }),
		card(page, longName).getByRole('button', { name: 'Remove and delete files' })
	]) {
		await control.scrollIntoViewIfNeeded();
		const box = await control.boundingBox();
		expect(box?.width).toBeGreaterThanOrEqual(44);
		expect(box?.height).toBeGreaterThanOrEqual(44);
	}

	await page.keyboard.press('Tab');
	await expect(page.getByRole('button', { name: 'Open settings' })).toBeFocused();
	await page.keyboard.press('Tab');
	await expect(page.getByLabel('Download link')).toBeFocused();
	await page.keyboard.press('Shift+Tab');
	await expect(page.getByRole('button', { name: 'Open settings' })).toBeFocused();
	await expect(page.getByRole('button', { name: 'Open settings' }).evaluate((element) => getComputedStyle(element).outlineStyle)).resolves.not.toBe('none');
	const stateStyles = await card(page, longName).getByText('Downloading', { exact: true }).evaluate((element) => ({
		color: getComputedStyle(element).color,
		background: getComputedStyle(document.body).backgroundColor,
		motion: getComputedStyle(element).transitionDuration
	}));
	expect(contrastRatio(stateStyles.color, stateStyles.background)).toBeGreaterThanOrEqual(4.5);
	expect(Number.parseFloat(stateStyles.motion)).toBeLessThanOrEqual(.01);
});

test('secondary surfaces and confirmations provide keyboard focus entry, restoration, and private history', async ({ page }) => {
	const privateId = '97';
	await page.setViewportSize({ width: 360, height: 800 });
	await installAuthenticatedApi(page, [download(97, longName, 'paused', { destinationPath: longPath })]);
	await page.goto('/');

	const downloadCard = card(page, longName);
	const details = downloadCard.getByRole('button', { name: 'View details' });
	await details.focus();
	await details.press('Enter');
	const detailsDialog = page.getByRole('dialog', { name: `${longName} details` });
	await expect(detailsDialog).toBeFocused();
	await expectPrivateBrowserState(page, [longName, longPath, privateId]);
	await page.keyboard.press('Escape');
	await expect(details).toBeFocused();

	const settings = page.getByRole('button', { name: 'Open settings' });
	await settings.click();
	const settingsDialog = page.getByRole('dialog', { name: 'Settings' });
	await expect(settingsDialog).toBeFocused();
	await settingsDialog.getByRole('button', { name: /Download folders/ }).click();
	const browseStorage = settingsDialog.getByRole('button', { name: 'Browse storage' });
	await browseStorage.click();
	const folderBack = settingsDialog.getByRole('button', { name: 'Back to Download folders' });
	await expect(folderBack).toBeFocused();
	await folderBack.press('Enter');
	await expect(browseStorage).toBeFocused();
	await page.keyboard.press('Escape');
	await expect(settings).toBeFocused();

	const moveFiles = downloadCard.getByRole('button', { name: 'Move files' });
	await moveFiles.click();
	await downloadCard.getByRole('radio', { name: target }).check();
	const continueMove = downloadCard.getByRole('button', { name: 'Continue' });
	await continueMove.press('Enter');
	const moveDialog = downloadCard.getByRole('dialog', { name: `Move ${longName}?` });
	await expect(moveDialog.getByRole('button', { name: 'Move files' })).toBeFocused();
	await page.goBack();
	await expect(moveDialog).toHaveCount(0);
	await expect(continueMove).toBeFocused();

	const remove = downloadCard.getByRole('button', { name: 'Remove from list' });
	await remove.click();
	const removal = page.getByRole('alertdialog', { name: `Remove ${longName} from list?` });
	await expect(removal.getByRole('button', { name: 'Remove from list' })).toBeFocused();
	await page.goBack();
	await expect(removal).toHaveCount(0);
	await expect(remove).toBeFocused();

	const removeAndDelete = downloadCard.getByRole('button', { name: 'Remove and delete files' });
	await removeAndDelete.click();
	const destructiveRemoval = page.getByRole('alertdialog', { name: `Remove ${longName} and delete files?` });
	await expect(destructiveRemoval.getByRole('button', { name: 'Remove and delete files' })).toBeFocused();
	await page.goBack();
	await expect(destructiveRemoval).toHaveCount(0);
	await expect(removeAndDelete).toBeFocused();
	await expectPrivateBrowserState(page, [longName, longPath, privateId]);

	await settings.click();
	await page.goBack();
	await expect(page.getByRole('dialog', { name: 'Settings' })).toHaveCount(0);
	await page.reload();
	await expect(page.getByRole('dialog')).toHaveCount(0);
	await expectPrivateBrowserState(page, [longName, longPath, privateId]);
});
