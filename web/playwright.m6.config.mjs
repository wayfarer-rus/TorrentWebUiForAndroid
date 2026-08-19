import { defineConfig } from '@playwright/test';

export default defineConfig({
	testDir: './e2e',
	testMatch: ['m6-onboarding-shell.spec.mjs', 'm7-webui-coordinator.spec.mjs', 'm7-application-shell.spec.mjs', 'm7-add-download.spec.mjs', 'm7-consumer-download-queue.spec.mjs', 'm7-canonical-download-folder-paths.spec.mjs', 'm7-pause-resume-cards.spec.mjs', 'm7-download-details.spec.mjs', 'm7-settings.spec.mjs', 'm7-manage-download-folders.spec.mjs', 'm7-move-files-between-folders.spec.mjs', 'm7-remove-downloads-safely.spec.mjs', 'm7-needs-attention-recovery.spec.mjs', 'm7-accessibility-responsive-acceptance.spec.mjs'],
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
