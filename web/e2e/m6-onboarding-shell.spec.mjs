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
	await expect(page.getByRole('heading', { name: 'Getting Downloads ready' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Downloads are unavailable' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Add Download' })).toHaveCount(0);
	await expect.poll(() => statusRequests, { timeout: 4000 }).toBeGreaterThan(1);
	await expect.poll(() => normalApiRequests).toBe(0);

	readiness = 'Ready';
	await expect(page.getByRole('heading', { name: 'Choose a download folder' })).toBeVisible({
		timeout: 4000
	});

	await page.reload();
	await expect(page.getByRole('heading', { name: 'Choose a download folder' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Add Download' })).toHaveCount(0);
});

test('readiness holding state survives refresh and browser closure before advancing', async ({ page, context }) => {
	const state = {
		completed: false,
		passwordDecision: 'pending',
		hasApprovedDestination: false,
		readiness: 'Service unavailable'
	};
	await context.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(state)
	}));
	await context.route('**/api/onboarding/recommended-destination', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ path: '/storage/primary/Download/Torrents' })
	}));

	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'Downloads are unavailable' })).toBeVisible();
	await page.reload();
	await expect(page.getByRole('heading', { name: 'Downloads are unavailable' })).toBeVisible();
	await page.close();

	const reopened = await context.newPage();
	await reopened.goto('/');
	await expect(reopened.getByRole('heading', { name: 'Downloads are unavailable' })).toBeVisible();
	state.readiness = 'Ready';
	await expect(reopened.getByRole('heading', { name: 'Choose a download folder' })).toBeVisible({ timeout: 4000 });
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
	await expect(page.getByRole('heading', { name: 'Add Download' })).toBeVisible();

	state.readiness = 'Service unavailable';
	state.hasApprovedDestination = false;
	await page.reload();
	await expect(page.getByRole('heading', { name: 'Add Download' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Getting Downloads ready' })).toHaveCount(0);
});

test('alternate onboarding browser navigates primary and removable canonical paths', async ({ page }) => {
	const state = {
		completed: false,
		passwordDecision: 'pending',
		hasApprovedDestination: false,
		readiness: 'Ready'
	};
	let approvedPath = null;
	let validationInput = null;
	let removableConnected = false;

	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(state)
	}));
	await page.route('**/api/onboarding/recommended-destination', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({ path: '/storage/primary/Download/Torrents' })
	}));
	await page.route('**/api/storage/permission', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ state: 'Ready' })
	}));
	await page.route('**/api/storage/volumes', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify([
			{ path: '/storage/primary', isRemovable: false },
			{ path: '/storage/USB', isRemovable: true }
		])
	}));
	await page.route('**/api/storage/catalog', (route) => route.fulfill({
		contentType: 'application/json', body: '[]'
	}));
	await page.route('**/api/storage/latest-selected', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify({ path: null })
	}));
	await page.route('**/api/storage/children', (route) => {
		const parent = route.request().postDataJSON().path;
		if (parent === '/storage/USB' && !removableConnected) {
			return route.fulfill({
				status: 409,
				contentType: 'application/json',
				body: JSON.stringify({ error: 'This storage volume is no longer available.' })
			});
		}
		const children = parent === '/storage/primary'
			? ['/storage/primary/Movies']
			: parent === '/storage/USB' ? ['/storage/USB/Movies'] : [];
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify(children) });
	});
	await page.route('**/api/storage/validate', (route) => {
		validationInput = route.request().postDataJSON().path;
		return route.fulfill({
			contentType: 'application/json',
			body: JSON.stringify({
				path: validationInput,
				canonicalPath: '/storage/USB/Movies',
				isValid: true
			})
		});
	});
	await page.route('**/api/storage/destinations', (route) => {
		approvedPath = route.request().postDataJSON().path;
		state.hasApprovedDestination = true;
		return route.fulfill({
			contentType: 'application/json',
			body: JSON.stringify({ status: 'ok', path: approvedPath })
		});
	});

	await page.goto('/');
	await page.getByRole('button', { name: 'Choose another folder' }).click();
	await expect(page.getByText('/storage/primary', { exact: true })).toBeVisible();
	await expect(page.getByText('/storage/USB', { exact: true })).toBeVisible();

	await page.getByRole('button', { name: '/storage/primary' }).click();
	await expect(page.getByRole('button', { name: '/storage/primary/Movies' })).toBeVisible();
	await page.getByRole('button', { name: '/storage/USB' }).click();
	await expect(page.getByText('This storage volume is no longer available.')).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Password choice' })).toHaveCount(0);

	removableConnected = true;
	await page.getByRole('button', { name: '/storage/USB' }).click();
	await page.getByRole('button', { name: '/storage/USB/Movies' }).click();
	await page.getByRole('button', { name: 'Check this folder' }).click();
	await expect(page.getByText('Verified canonical path')).toBeVisible();

	await page.reload();
	await expect(page.getByText('Verified canonical path')).toHaveCount(0);
	await expect(page.getByRole('heading', { name: 'Password choice' })).toHaveCount(0);
	await page.getByRole('button', { name: 'Choose another folder' }).click();
	await page.getByLabel('Or paste an absolute path').fill('content://documents/tree/USB');
	await page.getByRole('button', { name: 'Check folder' }).click();
	await expect(page.getByText('Enter an absolute filesystem path.')).toBeVisible();
	await page.getByLabel('Or paste an absolute path').fill('/storage/USB/Alias');
	await page.getByRole('button', { name: 'Check folder' }).click();
	await expect(page.getByText('/storage/USB/Movies', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: 'Use this folder' }).click();

	await expect.poll(() => validationInput).toBe('/storage/USB/Alias');
	await expect.poll(() => approvedPath).toBe('/storage/USB/Movies');
	await expect(page.getByRole('heading', { name: 'Password choice' })).toBeVisible();
});

test('password change rejects mismatch and short values then reloads for reauthentication', async ({ page }) => {
	const state = {
		completed: false,
		passwordDecision: 'pending',
		hasApprovedDestination: true,
		readiness: 'Ready'
	};
	let passwordRequests = 0;
	let statusRequests = 0;

	await page.route('**/api/onboarding/status', (route) => {
		statusRequests += 1;
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify(state) });
	});
	await page.route('**/api/onboarding/password', (route) => {
		passwordRequests += 1;
		const body = route.request().postDataJSON();
		if (body.newPassword.length < 4) {
			return route.fulfill({
				status: 400,
				contentType: 'application/json',
				body: JSON.stringify({ error: 'New password must be at least 4 characters' })
			});
		}
		expect(Object.keys(body)).toEqual(['newPassword']);
		state.passwordDecision = 'changed';
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
	await expect(page.getByRole('button', { name: 'Choose another password' })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Set it later' })).toBeVisible();
	await expect(page.getByText('start123')).toHaveCount(0);
	await page.getByRole('button', { name: 'Choose another password' }).click();
	await expect(page.getByLabel('Current Password')).toHaveCount(0);

	await page.getByLabel('New Password').fill('valid-password');
	await page.getByLabel('Confirm Password').fill('different-password');
	await page.getByRole('button', { name: 'Change Password' }).click();
	await expect(page.getByText('The Password confirmation does not match.')).toBeVisible();
	await expect.poll(() => passwordRequests).toBe(0);

	await page.getByLabel('New Password').fill('abc');
	await page.getByLabel('Confirm Password').fill('abc');
	await page.getByRole('button', { name: 'Change Password' }).click();
	await expect(page.getByText('New password must be at least 4 characters')).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Getting Downloads ready' })).toBeVisible();

	await page.getByLabel('New Password').fill('household passphrase');
	await page.getByLabel('Confirm Password').fill('household passphrase');
	await page.getByRole('button', { name: 'Change Password' }).click();
	await expect(page.getByRole('heading', { name: 'Add Download' })).toBeVisible();
	await expect.poll(() => statusRequests).toBeGreaterThan(1);
	await expect.poll(() => passwordRequests).toBe(2);
});

test('closing during password reauthentication retains completed backend progress', async ({ page, context }) => {
	const state = {
		completed: false,
		passwordDecision: 'pending',
		hasApprovedDestination: true,
		readiness: 'Ready'
	};
	await context.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json', body: JSON.stringify(state)
	}));
	await context.route('**/api/onboarding/password', (route) => {
		state.completed = true;
		state.passwordDecision = 'changed';
		return route.fulfill({ contentType: 'application/json', body: JSON.stringify(state) });
	});
	await context.route('**/api/torrents', (route) => route.fulfill({
		contentType: 'application/json', body: '[]'
	}));
	await context.route('**/api/storage/**', (route) => route.fulfill({
		contentType: 'application/json', body: route.request().url().endsWith('/permission')
			? JSON.stringify({ state: 'Ready' })
			: '[]'
	}));

	await page.goto('/');
	await page.getByRole('button', { name: 'Choose another password' }).click();
	await page.getByLabel('New Password').fill('household passphrase');
	await page.getByLabel('Confirm Password').fill('household passphrase');
	const reloadStarted = page.waitForRequest((request) => request.isNavigationRequest());
	await page.getByRole('button', { name: 'Change Password' }).click();
	await reloadStarted;
	await page.close();

	const reopened = await context.newPage();
	await reopened.goto('/');
	await expect(reopened.getByRole('heading', { name: 'Add Download' })).toBeVisible();
	await expect(reopened.getByRole('heading', { name: 'Getting Downloads ready' })).toHaveCount(0);
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
	await expect(page.getByRole('heading', { name: 'Add Download' })).toHaveCount(0);
});

test('completed onboarding keeps normal controls during a later daemon failure state', async ({ page }) => {
	await page.route('**/api/onboarding/status', (route) => route.fulfill({
		contentType: 'application/json',
		body: JSON.stringify({
			completed: true,
			passwordDecision: 'changed',
			hasApprovedDestination: false,
			readiness: 'Service unavailable'
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
	await expect(page.getByRole('heading', { name: 'Add Download' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Getting Downloads ready' })).toHaveCount(0);
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
	await expect(page.getByRole('heading', { name: 'Add Download' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Getting Downloads ready' })).toHaveCount(0);
});
