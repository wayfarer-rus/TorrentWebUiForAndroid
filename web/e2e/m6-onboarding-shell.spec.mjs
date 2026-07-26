import { expect, test } from '@playwright/test';

test('incomplete onboarding hides normal controls, resumes, and advances after readiness polling', async ({ page }) => {
	let readiness = 'Service unavailable';
	let statusRequests = 0;
	let normalApiRequests = 0;

	await page.route('**/api/onboarding/status', async (route) => {
		statusRequests += 1;
		await route.fulfill({
			contentType: 'application/json',
			body: JSON.stringify({
				completed: false,
				passwordDecision: 'pending',
				hasApprovedDestination: false,
				readiness
			})
		});
	});
	await page.route('**/api/torrents**', async (route) => {
		normalApiRequests += 1;
		await route.fulfill({ contentType: 'application/json', body: '[]' });
	});

	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'Consumer Onboarding' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Service unavailable' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Add Torrent' })).toHaveCount(0);
	await expect.poll(() => statusRequests, { timeout: 4000 }).toBeGreaterThan(1);
	await expect.poll(() => normalApiRequests).toBe(0);

	readiness = 'Ready';
	await expect(page.getByRole('heading', { name: 'Choose a download folder' })).toBeVisible({
		timeout: 4000
	});

	await page.reload();
	await expect(page.getByRole('heading', { name: 'Choose a download folder' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Add Torrent' })).toHaveCount(0);
});

test('recommended destination and Set it later complete onboarding durably', async ({ page }) => {
	const state = {
		completed: false,
		passwordDecision: 'pending',
		hasApprovedDestination: false,
		readiness: 'Ready'
	};
	let recommendedConfirmations = 0;

	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(state)
	}));
	await page.route('**/api/onboarding/recommended-destination', (route) => {
		if (route.request().method() === 'POST') {
			recommendedConfirmations += 1;
			state.hasApprovedDestination = true;
		}
		return route.fulfill({
			contentType: 'application/json',
			body: JSON.stringify({ status: 'ok', path: '/storage/primary/Download/Torrents' })
		});
	});
	await page.route('**/api/onboarding/password/defer', (route) => {
		state.passwordDecision = 'deferred';
		state.completed = true;
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify(state) });
	});
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: '[]'
	}));
	await page.route('**/api/storage/**', (route) => route.fulfill({
		contentType: 'application/json', body: route.request().url().endsWith('/permission')
			? JSON.stringify({ state: 'Ready' })
			: '[]'
	}));

	await page.goto('/');
	await expect(page.getByText('/storage/primary/Download/Torrents')).toBeVisible();
	await expect(page.getByText('start123')).toHaveCount(0);
	await expect.poll(() => recommendedConfirmations).toBe(0);

	await page.getByRole('button', { name: 'Use this folder' }).click();
	await expect(page.getByRole('heading', { name: 'Password choice' })).toBeVisible();
	await expect(page.getByText('start123')).toHaveCount(0);

	await page.getByRole('button', { name: 'Set it later' }).click();
	await expect(page.getByRole('heading', { name: 'Add Torrent' })).toBeVisible();

	state.readiness = 'Service unavailable';
	state.hasApprovedDestination = false;
	await page.reload();
	await expect(page.getByRole('heading', { name: 'Add Torrent' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Consumer Onboarding' })).toHaveCount(0);
});

test('onboarding status requests time out instead of hanging', async ({ page }) => {
	await page.addInitScript(() => {
		const realFetch = window.fetch.bind(window);
		window.fetch = (input, init = {}) => {
			if (String(input).endsWith('/api/onboarding/status')) {
				return new Promise((resolve, reject) => {
					init.signal?.addEventListener('abort', () => {
						reject(new DOMException('Aborted', 'AbortError'));
					}, { once: true });
				});
			}
			return realFetch(input, init);
		};
	});

	await page.goto('/');
	await expect(page.getByText('The onboarding status request timed out. Try again.')).toBeVisible({
		timeout: 7000
	});
	await expect(page.getByRole('heading', { name: 'Add Torrent' })).toHaveCount(0);
});

test('completed onboarding starts the normal WebUI', async ({ page }) => {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({
			completed: true,
			passwordDecision: 'deferred',
			hasApprovedDestination: true,
			readiness: 'Ready'
		})
	}));
	await page.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: '[]'
	}));
	await page.route('**/api/storage/**', (route) => route.fulfill({
		contentType: 'application/json', body: route.request().url().endsWith('/permission')
			? JSON.stringify({ state: 'Ready' })
			: '[]'
	}));

	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'Add Torrent' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Consumer Onboarding' })).toHaveCount(0);
});
