import { defineConfig } from '@playwright/test';

export default defineConfig({
	testDir: './e2e',
	testMatch: 'm6-onboarding-shell.spec.mjs',
	fullyParallel: false,
	workers: 1,
	reporter: 'line',
	webServer: {
		command: 'npm run preview -- --host 127.0.0.1 --port 4173',
		port: 4173,
		reuseExistingServer: false
	},
	use: {
		baseURL: 'http://127.0.0.1:4173'
	}
});
