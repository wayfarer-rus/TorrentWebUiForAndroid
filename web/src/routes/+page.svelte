<script lang="ts">
	import { tick, onDestroy, onMount } from 'svelte';
	import {
		consumerDownloadName,
		formatConsumerProgress,
		normalizedProgress,
		presentDownload,
		presentDownloads,
		primaryConsumerAction,
		recoveryForDownload,
		type PresentedDownload
	} from '$lib/webui/consumer-download-queue';
	import { createWebUiCoordinator } from '$lib/webui/web-ui-coordinator.svelte';

	// The route is presentation-only: the coordinator owns authenticated transport and live state.
	const ui = createWebUiCoordinator();

	let presentedDownloads = $derived(presentDownloads(ui.lastSnapshot));
	let needsAttentionDownloads = $derived(presentedDownloads.filter((item) => item.section === 'needs-attention'));
	let activeDownloads = $derived(presentedDownloads.filter((item) => item.section === 'active'));
	let completedDownloads = $derived(presentedDownloads.filter((item) => item.section === 'completed'));
	let pathCopyFeedback = $state<Record<number, string>>({});
	let detailsDialog = $state<HTMLDivElement | null>(null);
	let detailsOpener: HTMLButtonElement | null = null;
	let detailsHistoryEntry = false;
	let settingsDialog = $state<HTMLDivElement | null>(null);
	let settingsOpener: HTMLButtonElement | null = null;
	let settingsHistoryEntry = false;
	let settingsPanel = $state<'menu' | 'folders' | 'folder-browser' | 'password' | 'about'>('menu');
	let moveFormFor = $state<number | null>(null);
	let moveConfirmation = $state<{ id: number; name: string; sourcePath: string; targetPath: string } | null>(null);
	let moveConfirmationDialog = $state<HTMLDivElement | null>(null);
	let moveConfirmationOpener: HTMLButtonElement | null = null;
	let moveConfirmationHistoryEntry = false;
	let removalConfirmation = $state<{ id: number; name: string; deleteFiles: boolean } | null>(null);
	let removalConfirmationDialog = $state<HTMLDivElement | null>(null);
	let removalOpener: HTMLButtonElement | null = null;
	let removalConfirmationHistoryEntry = false;
	let selectedDetailState = $derived(ui.selectedTorrent ? presentDownload(ui.selectedTorrent).state : '');

	function selectCanonicalPath(inputId: string, path: string) {
		const input = document.getElementById(inputId) as HTMLInputElement | null;
		if (!input) return;
		input.focus();
		input.select();
		input.setSelectionRange(0, path.length);
	}

	async function copyCanonicalPath(id: number, inputId: string, path: string) {
		try {
			if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
			await navigator.clipboard.writeText(path);
			pathCopyFeedback = { ...pathCopyFeedback, [id]: 'Path copied.' };
		} catch {
			selectCanonicalPath(inputId, path);
			pathCopyFeedback = { ...pathCopyFeedback, [id]: 'Path selected. Use your system copy command.' };
		}
	}


	async function invokeTorrentAction(item: PresentedDownload) {
		const action = primaryConsumerAction(item);
		if (!action) return;
		const changed = action === 'pause'
			? await ui.pauseTorrent(item.download.id)
			: await ui.resumeTorrent(item.download.id);
		await tick();
		const card = document.getElementById(`download-${item.download.id}-card`);
		if (changed) {
			card?.focus();
		} else {
			card?.querySelector<HTMLButtonElement>('button.primary-button')?.focus();
		}
	}

	function openMoveForm(id: number) {
		moveConfirmation = null;
		moveFormFor = id;
	}

	function cancelMoveForm() {
		moveConfirmation = null;
		moveFormFor = null;
	}

	async function requestMoveConfirmation(download: PresentedDownload['download'], sourcePath: string, opener: HTMLButtonElement) {
		const targetPath = ui.moveTargetFor(download);
		if (!targetPath || targetPath === sourcePath) {
			ui.setMoveTarget(download.id, '');
			return;
		}
		moveConfirmationOpener = opener;
		moveConfirmation = {
			id: download.id,
			name: consumerDownloadName(download),
			sourcePath,
			targetPath
		};
		moveConfirmationHistoryEntry = true;
		window.history.pushState({ moveConfirmationOpen: true }, '', window.location.href);
		await tick();
		moveConfirmationDialog?.querySelector<HTMLButtonElement>('button.primary-button')?.focus();
	}

	async function closeMoveConfirmation(fromHistory = false) {
		if (!moveConfirmation) return;
		moveConfirmation = null;
		const opener = moveConfirmationOpener;
		moveConfirmationOpener = null;
		if (fromHistory) moveConfirmationHistoryEntry = false;
		await tick();
		if (opener?.isConnected) opener.focus();
		if (moveConfirmationHistoryEntry && !fromHistory) {
			moveConfirmationHistoryEntry = false;
			window.history.back();
		}
	}

	async function confirmMove() {
		const confirmation = moveConfirmation;
		if (!confirmation) return;
		await ui.moveTorrent(confirmation.id, confirmation.targetPath);
		if (!ui.moveErrors[confirmation.id]) {
			await closeMoveConfirmation();
			cancelMoveForm();
		}
	}

	function removalActionIsPending(id: number, deleteFiles: boolean): boolean {
		return ui.torrentActionPending[id] === (deleteFiles ? 'remove-and-delete-files' : 'remove-from-list');
	}

	async function requestRemovalConfirmation(download: PresentedDownload['download'], deleteFiles: boolean, opener: HTMLButtonElement) {
		if (ui.torrentActionPending[download.id]) {
			if (removalConfirmation?.id === download.id) {
				await tick();
				removalConfirmationDialog?.querySelector<HTMLButtonElement>('button.primary-button')?.focus();
			}
			return;
		}
		removalOpener = opener;
		removalConfirmation = { id: download.id, name: consumerDownloadName(download), deleteFiles };
		removalConfirmationHistoryEntry = true;
		window.history.pushState({ removalConfirmationOpen: true }, '', window.location.href);
		await tick();
		removalConfirmationDialog?.querySelector<HTMLButtonElement>('button.primary-button')?.focus();
	}

	async function cancelRemovalConfirmation(fromHistory = false, restoreFocus = true) {
		if (!removalConfirmation || removalActionIsPending(removalConfirmation.id, removalConfirmation.deleteFiles)) return;
		removalConfirmation = null;
		const opener = removalOpener;
		removalOpener = null;
		if (fromHistory) removalConfirmationHistoryEntry = false;
		await tick();
		if (restoreFocus && opener?.isConnected) opener.focus();
		if (removalConfirmationHistoryEntry && !fromHistory) {
			removalConfirmationHistoryEntry = false;
			window.history.back();
		}
	}

	async function confirmRemoval() {
		const confirmation = removalConfirmation;
		if (!confirmation) return;
		const removed = await ui.removeTorrent(confirmation.id, confirmation.deleteFiles);
		if (removed) {
			await cancelRemovalConfirmation(false, false);
			return;
		}
		await cancelRemovalConfirmation();
	}

	async function openDetails(torrent: PresentedDownload['download'], opener: HTMLButtonElement) {
		detailsOpener = opener;
		await ui.toggleInfo(torrent);
		detailsHistoryEntry = true;
		window.history.pushState({ detailsOpen: true }, '', window.location.href);
		await tick();
		detailsDialog?.focus();
	}

	async function closeDetails(fromHistory = false) {
		if (!ui.showInfo) return;
		ui.closeInfo();
		const opener = detailsOpener;
		detailsOpener = null;
		if (fromHistory) detailsHistoryEntry = false;
		await tick();
		if (opener?.isConnected) opener.focus();
		if (detailsHistoryEntry && !fromHistory) {
			detailsHistoryEntry = false;
			window.history.back();
		}
	}

	function clearSettingsForm() {
		ui.currentPassword = '';
		ui.newPassword = '';
		ui.settingsMessage = '';
		ui.settingsError = '';
	}

	async function openSettings(opener: HTMLButtonElement, panel: typeof settingsPanel = 'menu') {
		settingsOpener = opener;
		settingsPanel = panel;
		clearSettingsForm();
		ui.showSettings = true;
		settingsHistoryEntry = true;
		window.history.pushState({ settingsOpen: true }, '', window.location.href);
		await tick();
		settingsDialog?.focus();
	}

	async function openStorageRecovery(opener: HTMLButtonElement) {
		await openSettings(opener, 'folders');
	}

	async function closeSettings(fromHistory = false) {
		if (!ui.showSettings) return;
		ui.closeDirectoryBrowser();
		ui.showSettings = false;
		settingsPanel = 'menu';
		clearSettingsForm();
		const opener = settingsOpener;
		settingsOpener = null;
		if (fromHistory) settingsHistoryEntry = false;
		await tick();
		if (opener?.isConnected) opener.focus();
		if (settingsHistoryEntry && !fromHistory) {
			settingsHistoryEntry = false;
			window.history.back();
		}
	}

	async function openFolderBrowser() {
		settingsPanel = 'folder-browser';
		await ui.openDirectoryBrowser();
		await tick();
		settingsDialog?.querySelector<HTMLButtonElement>('button.settings-back')?.focus();
	}

	async function returnToDownloadFolders() {
		ui.closeDirectoryBrowser();
		settingsPanel = 'folders';
		await tick();
		settingsDialog?.querySelector<HTMLButtonElement>('#browse-storage')?.focus();
	}

	async function approveFolder(path: string): Promise<boolean> {
		const approved = await ui.approveDestination(path);
		if (approved) settingsPanel = 'folders';
		return approved;
	}

	function trapFocus(event: KeyboardEvent, dialog: HTMLDivElement | null) {
		if (event.key !== 'Tab' || !dialog) return;
		const focusable = [...dialog.querySelectorAll<HTMLElement>(
			'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
		)].filter((element) => !element.hasAttribute('hidden'));
		const first = focusable[0];
		const last = focusable.at(-1);
		if (!first || !last) return;
		if (event.shiftKey && document.activeElement === first) {
			event.preventDefault();
			last.focus();
		} else if (!event.shiftKey && document.activeElement === last) {
			event.preventDefault();
			first.focus();
		}
	}

	function handleSecondarySurfaceKeydown(event: KeyboardEvent) {
		if (removalConfirmation) {
			if (event.key === 'Escape') {
				event.preventDefault();
				void cancelRemovalConfirmation();
				return;
			}
			trapFocus(event, removalConfirmationDialog);
			return;
		}
		if (moveConfirmation && event.key === 'Escape') {
			event.preventDefault();
			void closeMoveConfirmation();
			return;
		}
		if (ui.showSettings) {
			if (event.key === 'Escape') {
				event.preventDefault();
				void closeSettings();
				return;
			}
			trapFocus(event, settingsDialog);
			return;
		}
		if (!ui.showInfo) return;
		if (event.key === 'Escape') {
			event.preventDefault();
			void closeDetails();
			return;
		}
		trapFocus(event, detailsDialog);
	}

	onMount(() => {
		ui.start();
		const closeFromHistory = () => {
			if (removalConfirmation) void cancelRemovalConfirmation(true);
			else if (moveConfirmation) void closeMoveConfirmation(true);
			else if (ui.showSettings) void closeSettings(true);
			else if (ui.showInfo) void closeDetails(true);
		};
		window.addEventListener('popstate', closeFromHistory);
		return () => window.removeEventListener('popstate', closeFromHistory);
	});
	onDestroy(() => ui.dispose());
</script>

<svelte:head>
	<title>Downloads</title>
</svelte:head>

<svelte:window onkeydown={handleSecondarySurfaceKeydown} />

{#snippet directoryBrowser(onApproved: (path: string) => Promise<boolean>, disabled = false)}
	<button class="text-button" type="button" onclick={ui.toggleDirectoryBrowser} disabled={disabled}>
		{ui.showBrowser ? 'Hide folder browser' : 'Choose another folder'}
	</button>
	{#if ui.showBrowser}{@render directoryBrowserContent(onApproved, disabled)}{/if}
{/snippet}

{#snippet directoryBrowserContent(onApproved: (path: string) => Promise<boolean>, disabled = false, approvalLabel = 'Use this folder')}
	<div class="folder-browser">
		{#if ui.storageLoading}
			<p>Loading available folders…</p>
		{:else if !ui.storageReady}
			<p class="storage-guidance">{ui.storageRecoveryMessage || 'Storage access needs attention in the Android app.'}</p>
		{:else}
			<h4>Storage volumes</h4>
			{#if ui.volumes.length === 0}
				<p class="quiet-empty">No mounted storage volumes are available. Reconnect storage, then try again.</p>
			{:else}
				<div class="path-buttons">
					{#each ui.volumes as volume}
						<button class="path-button" type="button" onclick={() => void ui.browseDirectory(volume.path)} disabled={disabled || ui.storageMutationPending}>
							{#if volume.description}<span>{volume.description}</span>{/if}
							<code>{volume.path}</code>
						</button>
					{/each}
				</div>
			{/if}

			{#if ui.browsedPath}
				<div class="browser-current">
					<p>Current folder</p>
					<code>{ui.browsedPath}</code>
					<button class="secondary-button" type="button" onclick={ui.validateBrowsedPath} disabled={disabled || ui.validatingPath || ui.storageMutationPending}>Check this folder</button>
				</div>
				<h4>Child folders</h4>
				{#if ui.children.length === 0}
					<p class="quiet-empty">No readable child folders were returned by the device.</p>
				{:else}
					<div class="path-buttons">
						{#each ui.children as childPath}
							<button class="path-button" type="button" onclick={() => void ui.browseDirectory(childPath)} disabled={disabled || ui.storageMutationPending}><code>{childPath}</code></button>
						{/each}
					</div>
				{/if}
			{/if}

			<div class="paste-path">
				<label for="destination-path">Or paste an absolute path</label>
				<div class="input-row compact-row">
					<input id="destination-path" type="text" bind:value={ui.pastedPath} placeholder="/storage/…" disabled={disabled || ui.storageMutationPending} />
					<button class="secondary-button" type="button" onclick={() => void ui.validatePath(ui.pastedPath)} disabled={disabled || ui.validatingPath || ui.storageMutationPending}>Check folder</button>
				</div>
			</div>
		{/if}

		{#if ui.pathValidation}
			<div class:validation-success={ui.pathValidation.isValid} class:validation-error={!ui.pathValidation.isValid} class="validation-result">
				{#if ui.pathValidation.isValid && ui.pathValidation.canonicalPath}
					<p>Verified canonical path</p>
					<code>{ui.pathValidation.canonicalPath}</code>
					<button class="primary-button" type="button" onclick={() => void onApproved(ui.pathValidation?.canonicalPath || '')} disabled={disabled || ui.storageMutationPending}>{approvalLabel}</button>
				{:else}
					<p>{ui.pathValidation.rejectionReason || 'This folder cannot be used.'}</p>
				{/if}
			</div>
		{/if}
		{#if ui.storageError}<p class="error" role="alert">{ui.storageError}</p>{/if}
	</div>
{/snippet}

{#snippet downloadCard(item: PresentedDownload)}
	{@const download = item.download}
	{@const name = consumerDownloadName(download)}
	{@const progress = normalizedProgress(download.progress)}
	{@const progressText = formatConsumerProgress(download.progress)}
	{@const folderPath = download.destinationPath?.trim() || null}
	{@const pathInputId = `download-${download.id}-folder-path`}
	{@const pathFeedbackId = `download-${download.id}-folder-feedback`}
	{@const action = primaryConsumerAction(item)}
	{@const pendingAction = ui.torrentActionPending[download.id]}
	{@const actionError = ui.torrentActionErrors[download.id]}
	{@const actionFeedbackId = `download-${download.id}-action-feedback`}
	{@const approvedMoveTargets = ui.catalog.filter((path) => path !== folderPath)}
	{@const selectedMoveTarget = ui.moveTargetFor(download)}
	{@const recovery = recoveryForDownload(download)}
	{@const recoverableMove = ui.isRecoverableMoveState(download.moveState)}
	{@const recoverableMoveTarget = ui.recoverableMoveTargetFor(download)}
	{@const movePending = Boolean(ui.moveLoading[download.id])}
	{@const moving = item.state === 'Moving files'}
	{@const moveError = ui.moveErrors[download.id]}
	{@const moveMessage = ui.moveMessages[download.id]}
	<article id={`download-${download.id}-card`} class="torrent-card download-card" aria-labelledby={`download-${download.id}-name`} tabindex="-1">
		<div class="card-main">
			<div class="card-title-row">
				<div>
					<p class="state">{item.state}</p>
					<h3 id={`download-${download.id}-name`} class="torrent-name">{name}</h3>
				</div>
				<span class="progress-text">{progressText}</span>
			</div>
			<progress aria-label={`${name}: ${item.state}, ${progressText}`} value={progress * 100} max="100">{progressText}</progress>
			<div class="card-secondary-action">
				<button class="text-button details-button" type="button" onclick={(event) => void openDetails(download, event.currentTarget as HTMLButtonElement)}>View details</button>
				{#if folderPath && (approvedMoveTargets.length > 0 || recoverableMove || moving)}
					<button class="text-button details-button" type="button" onclick={() => openMoveForm(download.id)} disabled={movePending || moving}>Move files</button>
				{/if}
			</div>
			{#if action}
				<div class="card-action">
					<button
						class="primary-button"
						type="button"
						onclick={() => void invokeTorrentAction(item)}
						disabled={Boolean(pendingAction) || movePending || moving}
						aria-describedby={actionError ? actionFeedbackId : undefined}
					>{pendingAction === 'pause' ? 'Pausing…' : pendingAction === 'resume' ? 'Resuming…' : action === 'pause' ? 'Pause' : 'Resume'}</button>
				</div>
			{/if}
			<div class="card-remove-actions" aria-label={`Remove ${name}`}>
				<button class="text-button remove-from-list-button" type="button" onclick={(event) => void requestRemovalConfirmation(download, false, event.currentTarget as HTMLButtonElement)} disabled={removalActionIsPending(download.id, false) || pendingAction === 'pause' || pendingAction === 'resume' || movePending || moving}>{removalActionIsPending(download.id, false) ? 'Removing from list…' : 'Remove from list'}</button>
				<button class="destructive-button" type="button" onclick={(event) => void requestRemovalConfirmation(download, true, event.currentTarget as HTMLButtonElement)} disabled={removalActionIsPending(download.id, true) || pendingAction === 'pause' || pendingAction === 'resume' || movePending || moving}>{removalActionIsPending(download.id, true) ? 'Removing and deleting files…' : 'Remove and delete files'}</button>
			</div>
			{#if actionError}<p id={actionFeedbackId} class="card-action-feedback error" role="alert">{actionError}</p>{/if}
			{#if folderPath && recoverableMove && recovery}
				<div class="move-recovery" role="alert">
					<p><strong>Needs attention.</strong> {recovery.message}</p>
					{#if recoverableMoveTarget}
						<button class="primary-button" type="button" onclick={() => void ui.retryMove(download.id, recoverableMoveTarget)} disabled={movePending}>{movePending ? 'Retrying move…' : 'Retry move'}</button>
					{:else}
						<p class="move-feedback">The interrupted target cannot be retried safely. Cancel this move, then choose another approved Download Folder if needed.</p>
					{/if}
					<button class:primary-button={!recoverableMoveTarget} class:secondary-button={Boolean(recoverableMoveTarget)} type="button" onclick={() => void ui.cancelMove(download.id)} disabled={movePending}>{movePending ? 'Cancelling move…' : 'Cancel move'}</button>
				</div>
			{:else if recovery}
				<div class="download-recovery" role="alert">
					<p><strong>Needs attention.</strong> {recovery.message}</p>
					{#if recovery.primaryAction === 'fix-problem'}
						<button class="primary-button" type="button" onclick={(event) => void openStorageRecovery(event.currentTarget as HTMLButtonElement)}>Fix problem</button>
					{/if}
				</div>
			{:else if folderPath && moveFormFor === download.id}
				<div class="move-panel" aria-label={`Move ${name} files`}>
					<h4>Move files</h4>
					{#if approvedMoveTargets.length === 0}
						<p class="quiet-empty">Approve another Download Folder in Settings before moving files.</p>
					{:else}
						<fieldset class="catalog-list"><legend>Move to an approved Download Folder</legend>
							{#each approvedMoveTargets as path, index}
								<div class="path-option"><label><input type="radio" name={`move-target-${download.id}`} checked={selectedMoveTarget === path} onchange={() => ui.setMoveTarget(download.id, path)} disabled={movePending} /><code>{path}</code></label></div>
							{/each}
						</fieldset>
						<button class="primary-button" type="button" onclick={(event) => void requestMoveConfirmation(download, folderPath, event.currentTarget as HTMLButtonElement)} disabled={movePending || !selectedMoveTarget}>Continue</button>
					{/if}
					<button class="text-button" type="button" onclick={cancelMoveForm} disabled={movePending}>Cancel</button>
				</div>
			{/if}
			{#if moveConfirmation?.id === download.id}
				<div bind:this={moveConfirmationDialog} class="move-confirmation" role="dialog" aria-modal="false" aria-labelledby={`move-${download.id}-confirmation-title`} tabindex="-1">
					<h4 id={`move-${download.id}-confirmation-title`}>Move {moveConfirmation.name}?</h4>
					<p>Files will move only after Downloads records the request and the download engine accepts it.</p>
					<dl class="move-paths"><div><dt>From</dt><dd><code>{moveConfirmation.sourcePath}</code></dd></div><div><dt>To</dt><dd><code>{moveConfirmation.targetPath}</code></dd></div></dl>
					<button class="primary-button" type="button" onclick={() => void confirmMove()} disabled={movePending}>{movePending ? 'Starting move…' : 'Move files'}</button>
					<button class="text-button" type="button" onclick={() => void closeMoveConfirmation()} disabled={movePending}>Back</button>
				</div>
			{/if}
			{#if moveError}<p class="card-action-feedback error" role="alert">{moveError}</p>{:else if moveMessage}<p class="move-feedback" role="status">{moveMessage}</p>{/if}
			{#if folderPath}
				<div class="download-folder">
					<label for={pathInputId}>Download Folder</label>
					<div class="folder-path-action">
						<input id={pathInputId} class="canonical-path" type="text" value={folderPath} readonly aria-describedby={pathFeedbackId} />
						<button class="secondary-button copy-path-button" type="button" onclick={() => void copyCanonicalPath(download.id, pathInputId, folderPath)}>Copy path</button>
					</div>
					<p id={pathFeedbackId} class="path-copy-feedback" role="status">{pathCopyFeedback[download.id] || ''}</p>
				</div>
			{:else}
				<p class="folder-unavailable" role="status">Download Folder unavailable. Check the Android app to restore its verified folder.</p>
			{/if}
		</div>
	</article>
{/snippet}

{#if ui.onboardingLoading && !ui.onboardingStatus}
	<main class="onboarding-shell" aria-busy="true"><h1>Getting Downloads ready</h1><p>Checking whether Downloads are ready…</p></main>
{:else if ui.onboardingStatus && !ui.onboardingStatus.completed}
	<main class="onboarding-shell">
		<h1>Getting Downloads ready</h1>
		{#if ui.onboardingStatus.readiness === 'Action needed on Android'}
			<h2>Action needed on Android</h2><p>Open the Android app and complete the requested device action.</p>
		{:else if ui.onboardingStatus.readiness === 'Service unavailable'}
			<h2>Downloads are unavailable</h2><p>Use the Android app to recover or restart Downloads.</p>
		{:else if !ui.onboardingStatus.hasApprovedDestination}
			<h2>Choose a download folder</h2><p>Use the recommended folder for completed and in-progress downloads.</p>
			{#if ui.recommendedLoading}<p>Preparing the recommended path…</p>{:else if ui.recommendedPath}
				{#if !ui.showBrowser}<code class="path-readout">{ui.recommendedPath}</code><button class="primary-button" onclick={ui.confirmRecommendedDestination} disabled={ui.onboardingActionLoading}>{ui.onboardingActionLoading ? 'Creating folder…' : 'Use this folder'}</button>{/if}
				{@render directoryBrowser((path) => ui.approveDestination(path, 'onboarding'))}
			{/if}
		{:else if ui.onboardingStatus.passwordDecision === 'pending'}
			<h2>Password choice</h2>
			{#if !ui.showOnboardingPasswordChange}
				<p>Choose another Password now or keep the current one for later.</p>
				<button class="primary-button" onclick={ui.chooseAnotherOnboardingPassword} disabled={ui.onboardingActionLoading}>Choose another password</button>
				<button class="text-button" onclick={ui.deferOnboardingPassword} disabled={ui.onboardingActionLoading}>{ui.onboardingActionLoading ? 'Finishing setup…' : 'Set it later'}</button>
			{:else}
				<label for="onboarding-new-password">New Password</label><input id="onboarding-new-password" type="password" autocomplete="new-password" bind:value={ui.onboardingNewPassword} />
				<label for="onboarding-confirm-password">Confirm Password</label><input id="onboarding-confirm-password" type="password" autocomplete="new-password" bind:value={ui.onboardingPasswordConfirmation} />
				<button class="primary-button" onclick={ui.changeOnboardingPassword} disabled={ui.onboardingActionLoading}>{ui.onboardingActionLoading ? 'Saving Password…' : 'Change Password'}</button>
				<button class="text-button" onclick={ui.cancelOnboardingPasswordChange} disabled={ui.onboardingActionLoading}>Back</button>
			{/if}
		{:else}
			<h2>Finishing setup</h2><p>Your saved onboarding progress is being completed.</p>
		{/if}
		{#if ui.onboardingActionError}<p class="error">{ui.onboardingActionError}</p>{/if}
	</main>
{:else if ui.onboardingError}
	<main class="onboarding-shell"><h1>Getting Downloads ready</h1><p class="error">{ui.onboardingError}</p><a href="/">Try again</a></main>
{:else}
	<div class="app-shell">
		<header class="site-header">
			<div><p class="eyebrow">Home downloader</p><h1>Downloads</h1></div>
			<div class="header-actions">
				<span class="system-readout" aria-label="Service status"><i class:connected={ui.wsConnected && !ui.updatesPaused} class="status-dot"></i>{ui.updatesPaused ? 'Updates paused — reconnecting…' : ui.wsConnecting ? 'Connecting…' : ui.wsConnected ? 'Running' : 'Checking…'}</span>
				<button class="settings-button" type="button" onclick={(event) => void openSettings(event.currentTarget as HTMLButtonElement)} aria-label="Open settings">•••</button>
			</div>
		</header>

		<main>
			<section class="add-torrent add-panel" aria-labelledby="add-heading">
				<div class="add-copy"><p class="section-kicker">Start something new</p><h2 id="add-heading">Add Download</h2></div>
				<form class="add-form" aria-busy={ui.addPending} onsubmit={(event) => { event.preventDefault(); void ui.addMagnet(); }}>
					<label for="download-link">Download link</label>
					<p id="download-link-hint" class="field-hint">Paste a magnet link that starts with <code>magnet:?</code>.</p>
					<div class="input-row link-row"><input id="download-link" type="text" bind:value={ui.magnetUri} placeholder="Paste your Download link" aria-describedby="download-link-hint add-form-feedback" oninput={ui.clearAddFeedback} disabled={ui.addPending} /><button class="primary-button" type="submit" disabled={!ui.storageReady || ui.addPending}>{ui.addPending ? 'Adding Download…' : 'Add Download'}</button></div>
					<div class="destination-picker" aria-label="Download folder">
						<h3>Download Folder</h3>
						{#if ui.storageLoading}<p class="quiet-empty">Loading available folders…</p>
						{:else if !ui.storageReady}<p class="storage-guidance">Storage permission is required. Grant All Files Access in the Android app before choosing a Download Folder.</p>
						{:else}
							{#if ui.catalog.length > 0}<fieldset class="catalog-list" aria-describedby="add-form-feedback"><legend>Approved Destinations</legend>{#each ui.catalog as path}<div class="path-option"><label><input type="radio" name="destination" checked={ui.selectedDestination === path} onchange={() => { ui.clearAddFeedback(); void ui.selectApprovedDestination(path); }} disabled={ui.addPending || ui.storageMutationPending} /><code>{path}</code></label></div>{/each}</fieldset>
							{:else}<p class="quiet-empty">Choose and approve a Download Folder in Settings before adding a Download.</p>{/if}
						{/if}
						{#if ui.selectedDestination}<p class="selected-path">Downloads will be saved to <code>{ui.selectedDestination}</code></p>{/if}
						{#if ui.storageError}<p class="error">{ui.storageError}</p>{/if}
					</div>
					{#if !ui.selectedDestination}<p class="folder-required">Choose a Download Folder to continue.</p>{/if}
					{#if ui.addError}<p id="add-form-feedback" class="error" role="alert">{ui.addError}</p>{:else if ui.addStatus}<p id="add-form-feedback" class="add-status" role="status">{ui.addStatus}</p>{:else}<p id="add-form-feedback" class="visually-hidden"></p>{/if}
				</form>
			</section>

			{#if needsAttentionDownloads.length > 0}
				<section class="queue-section attention-section" aria-labelledby="needs-attention-heading">
					<div class="section-heading"><div><p class="section-kicker">Action needed</p><h2 id="needs-attention-heading">Needs Attention</h2></div><span class="count">{needsAttentionDownloads.length}</span></div>
					<div class="card-list">{#each needsAttentionDownloads as item (item.download.id)}{@render downloadCard(item)}{/each}</div>
				</section>
			{/if}

			<section class="queue-section" aria-labelledby="active-heading">
				<div class="section-heading"><div><p class="section-kicker">In progress</p><h2 id="active-heading">Active</h2></div><span class="count">{activeDownloads.length}</span></div>
				{#if ui.loading}<p class="quiet-empty">Loading Downloads…</p>
				{:else if ui.lastSnapshot.length === 0}<div class="empty-state"><h3>Ready when you are</h3><p>Add your first Download to see its progress here.</p><a href="#download-link" class="primary-link">Add Download</a></div>
				{:else if activeDownloads.length === 0}<p class="quiet-empty">Nothing is active right now.</p>
				{:else}<div class="card-list">{#each activeDownloads as item (item.download.id)}{@render downloadCard(item)}{/each}</div>{/if}
			</section>

			<section class="queue-section completed-section" aria-labelledby="completed-heading">
				<div class="section-heading"><div><p class="section-kicker">Ready when you are</p><h2 id="completed-heading">Completed</h2></div><span class="count">{completedDownloads.length}</span></div>
				{#if !ui.loading && completedDownloads.length === 0}<p class="quiet-empty">Completed Downloads will appear here.</p>
				{:else if completedDownloads.length > 0}<div class="card-list">{#each completedDownloads as item (item.download.id)}{@render downloadCard(item)}{/each}</div>{/if}
			</section>
		</main>
	</div>

	{#if removalConfirmation}
		<div class="modal-overlay removal-overlay">
			<button class="modal-scrim" type="button" aria-label="Cancel removal" onclick={() => void cancelRemovalConfirmation()} disabled={removalActionIsPending(removalConfirmation.id, removalConfirmation.deleteFiles)}></button>
			<div bind:this={removalConfirmationDialog} class="modal-content removal-confirmation" role="alertdialog" aria-modal="true" aria-labelledby="removal-confirmation-title" tabindex="-1">
				<div class="modal-header"><div><p class="section-kicker">{removalConfirmation.deleteFiles ? 'Delete files' : 'Remove Download'}</p><h2 id="removal-confirmation-title">{removalConfirmation.deleteFiles ? `Remove ${removalConfirmation.name} and delete files?` : `Remove ${removalConfirmation.name} from list?`}</h2></div></div>
				<div class="modal-body">
					{#if removalConfirmation.deleteFiles}
						<p>Downloaded and partial files for this Download will be deleted from its Download Folder.</p>
					{:else}
						<p>This Download will be removed from the list. Downloaded and partial files will remain in its Download Folder.</p>
					{/if}
					<div class="removal-confirmation-actions">
						<button class:destructive-button={removalConfirmation.deleteFiles} class="primary-button" type="button" onclick={() => void confirmRemoval()} disabled={removalActionIsPending(removalConfirmation.id, removalConfirmation.deleteFiles)}>{removalActionIsPending(removalConfirmation.id, removalConfirmation.deleteFiles) ? removalConfirmation.deleteFiles ? 'Removing and deleting files…' : 'Removing from list…' : removalConfirmation.deleteFiles ? 'Remove and delete files' : 'Remove from list'}</button>
						<button class="text-button" type="button" onclick={() => void cancelRemovalConfirmation()} disabled={removalActionIsPending(removalConfirmation.id, removalConfirmation.deleteFiles)}>Cancel</button>
					</div>
				</div>
			</div>
		</div>
	{/if}

	{#if ui.showSettings}
		<div class="modal-overlay settings-overlay">
			<button class="modal-scrim" type="button" aria-label="Close settings" onclick={() => void closeSettings()}></button>
			<div bind:this={settingsDialog} class="modal-content settings-surface" role="dialog" aria-modal="true" aria-labelledby="settings-title" tabindex="-1">
				<div class="modal-header">
					<div><p class="section-kicker">Downloads</p><h2 id="settings-title">Settings</h2></div>
					<button class="close-button" type="button" onclick={() => void closeSettings()} aria-label="Close settings">×</button>
				</div>
				<div class="modal-body settings-body">
					{#if settingsPanel === 'menu'}
						<nav class="settings-menu" aria-label="Settings sections">
							<button class="settings-entry" type="button" onclick={() => settingsPanel = 'folders'}><span><strong>Download folders</strong><small>Choose where Downloads are saved.</small></span><span aria-hidden="true">›</span></button>
							<button class="settings-entry" type="button" onclick={() => settingsPanel = 'password'}><span><strong>Password</strong><small>Change the Password used to access Downloads.</small></span><span aria-hidden="true">›</span></button>
							<button class="settings-entry" type="button" onclick={() => settingsPanel = 'about'}><span><strong>About</strong><small>Learn about Downloads on this device.</small></span><span aria-hidden="true">›</span></button>
						</nav>
					{:else if settingsPanel === 'folder-browser'}
						<button class="text-button settings-back" type="button" onclick={() => void returnToDownloadFolders()}>Back to Download folders</button>
						<h3>Browse storage</h3>
						<p class="settings-description">Choose only a folder shown by this device, or paste its full path for device verification.</p>
						{@render directoryBrowserContent(approveFolder, false, 'Approve this folder')}
					{:else}
						<button class="text-button settings-back" type="button" onclick={() => settingsPanel = 'menu'}>Back to Settings</button>
						{#if settingsPanel === 'folders'}
							<h3>Download folders</h3>
							<p class="settings-description">Manage the folders used for Downloads.</p>
							{#if ui.storageLoading}
								<p class="quiet-empty">Loading Download Folders…</p>
							{:else if !ui.storageReady}
								<p class="storage-guidance">{ui.storageRecoveryMessage || 'Storage access needs attention in the Android app.'}</p>
							{:else}
								{#if ui.catalog.length > 0}
									<fieldset class="catalog-list settings-catalog-list"><legend>Approved Destinations</legend>
										{#each ui.catalog as path, index}
											<div class="path-option settings-path-option">
												<input id={`approved-destination-${index}`} class="canonical-path" aria-label={`Approved Download Folder: ${path}`} type="text" value={path} readonly onclick={() => selectCanonicalPath(`approved-destination-${index}`, path)} />
												<button class="text-button" type="button" aria-label={`Forget ${path}`} onclick={() => void ui.removeApprovedDestination(path)} disabled={ui.storageMutationPending}>Forget</button>
											</div>
										{/each}
									</fieldset>
								{:else}
									<p class="quiet-empty">No Download Folders are approved yet.</p>
								{/if}
								<button id="browse-storage" class="primary-button" type="button" onclick={() => void openFolderBrowser()} disabled={ui.storageMutationPending}>Browse storage</button>
							{/if}
							{#if ui.storageError}<p class="settings-error" role="alert">{ui.storageError}</p>{/if}
						{:else if settingsPanel === 'password'}
							<h3>Password</h3>
							<p class="settings-description">Change the Password used to access Downloads. You will be asked to sign in again after saving it.</p>
							<form onsubmit={(event) => { event.preventDefault(); void ui.changePassword(); }} aria-busy={ui.settingsSaving}>
								{#if ui.settingsMessage}<p class="settings-success" role="status">{ui.settingsMessage}</p>{/if}
								{#if ui.settingsError}<p class="settings-error" role="alert">{ui.settingsError}</p>{/if}
								<label for="current-password">Current Password</label>
								<input id="current-password" type="password" bind:value={ui.currentPassword} autocomplete="current-password" />
								<label for="new-password">New Password</label>
								<input id="new-password" type="password" bind:value={ui.newPassword} autocomplete="new-password" />
								<button class="primary-button settings-submit" type="submit" disabled={ui.settingsSaving}>{ui.settingsSaving ? 'Saving Password…' : 'Change Password'}</button>
							</form>
						{:else}
							<h3>About Downloads</h3>
							<p class="settings-description">Downloads helps your household manage downloads from browsers you authorize on your local network.</p>
							<p class="settings-description">It runs on this device and keeps its service details in the background so the everyday controls stay simple.</p>
						{/if}
					{/if}
				</div>
			</div>
		</div>
	{/if}

	{#if ui.showInfo && ui.selectedTorrent}
		{@const detailPath = ui.selectedTorrent.destinationPath?.trim() || null}
		{@const isSharing = ui.selectedTorrent.state.trim().toLowerCase() === 'seeding' || ui.selectedTorrent.uploadRate > 0}
		<div class="modal-overlay details-overlay">
			<button class="modal-scrim" aria-label="Close details" onclick={() => void closeDetails()}></button>
			<div bind:this={detailsDialog} class="modal-content details-surface" role="dialog" aria-modal="true" aria-label={`${consumerDownloadName(ui.selectedTorrent)} details`} tabindex="-1">
				<div class="modal-header"><div><p class="section-kicker">Download details</p><h2 id="details-title">{consumerDownloadName(ui.selectedTorrent)}</h2></div><button class="close-button" type="button" onclick={() => void closeDetails()} aria-label="Close details">×</button></div>
				<div class="modal-body details-body">
					<dl class="details-list">
						<div class="info-row"><dt>Progress and state</dt><dd>{formatConsumerProgress(ui.selectedTorrent.progress)} · {selectedDetailState}</dd></div>
						<div class="info-row"><dt>Download speed</dt><dd>{ui.formatBytes(ui.selectedTorrent.downloadRate)}/s</dd></div>
						<div class="info-row"><dt>Upload speed</dt><dd>{ui.formatBytes(ui.selectedTorrent.uploadRate)}/s</dd></div>
						<div class="info-row"><dt>Peer information</dt><dd>{ui.selectedTorrent.peers} connected</dd></div>
						{#if isSharing}<div class="info-row"><dt>Sharing</dt><dd>Sharing with {ui.selectedTorrent.peers} connected peers</dd></div>{/if}
						{#if detailPath}<div class="info-row path"><dt>Download folder</dt><dd><code>{detailPath}</code></dd></div>{:else}<div class="info-row"><dt>Download folder</dt><dd>Unavailable. Check the Android app to restore its verified folder.</dd></div>{/if}
					</dl>
				</div>
			</div>
		</div>
	{/if}
{/if}

<style>
	:global(*) { box-sizing: border-box; }
	:global(html) { min-width: 320px; background: #0d1114; }
	:global(body) { margin: 0; min-width: 320px; background: #0d1114; color: #e9f0f2; font-family: Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
	:global(button), :global(input), :global(select) { font: inherit; }
	:global(button) { cursor: pointer; }
	:global(button:focus-visible), :global(input:focus-visible), :global(select:focus-visible), :global(a:focus-visible) { outline: 3px solid rgba(101, 230, 204, .65); outline-offset: 3px; }

	.app-shell, .onboarding-shell { width: min(100% - 28px, 880px); margin: 0 auto; }
	.app-shell { min-height: 100vh; padding: 28px 0 72px; }
	.site-header { display: flex; align-items: center; min-height: 76px; margin-bottom: 24px; border-bottom: 1px solid rgba(233, 240, 242, .13); }
	.eyebrow, .section-kicker, .state, .count, .system-readout { margin: 0 0 4px; color: #8c9aa0; font: 650 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .1em; text-transform: uppercase; }
	h1, h2, h3, h4, p { margin-top: 0; }
	h1 { margin-bottom: 0; font: 690 24px/1 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: -.04em; }
	h2 { margin-bottom: 0; font-size: 24px; letter-spacing: -.035em; }
	h3 { margin-bottom: 0; font-size: 16px; letter-spacing: -.02em; }
	h4 { margin-bottom: 10px; color: #8c9aa0; font-size: 13px; }
	.header-actions { display: flex; align-items: center; gap: 14px; margin-left: auto; }
	.system-readout { display: flex; align-items: center; margin: 0; white-space: nowrap; }
	.status-dot { width: 7px; height: 7px; margin-right: 7px; border-radius: 50%; background: #5d6b71; }
	.status-dot.connected { background: #65e6cc; box-shadow: 0 0 0 4px rgba(101, 230, 204, .13); }
	.settings-button, .close-button { display: grid; width: 44px; height: 44px; place-items: center; border: 1px solid rgba(233, 240, 242, .13); border-radius: 5px; color: #e9f0f2; background: #13191d; font-weight: 700; }

	.add-panel { display: grid; grid-template-columns: 170px minmax(0, 1fr); gap: 30px; padding: 25px; border: 1px solid rgba(233, 240, 242, .13); border-left: 4px solid #65e6cc; border-radius: 5px; background: #13191d; }
	.add-form { min-width: 0; }
	.add-form > label, .paste-path label, .modal-body form > label { display: block; margin-bottom: 8px; color: #e9f0f2; font-size: 13px; font-weight: 690; }
	.field-hint { margin: -2px 0 8px; color: #8c9aa0; font-size: 12px; line-height: 1.45; }
	.input-row { display: flex; gap: 8px; }
	.input-row input, .modal-body input { width: 100%; min-width: 0; height: 48px; padding: 0 13px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 4px; color: #e9f0f2; background: #0d1114; }
	.input-row input::placeholder { color: #5d6b71; }
	.primary-button, .secondary-button, .text-button, .destructive-button { min-height: 44px; padding: 0 14px; border: 0; border-radius: 3px; font-weight: 720; }
	.primary-button { color: #06231d; background: #65e6cc; }
	.destructive-button { color: #fff4f2; background: #9d3d37; }
	.secondary-button { color: #e9f0f2; background: #263138; }
	.text-button { color: #8c9aa0; background: transparent; }
	.text-button:hover { color: #e9f0f2; background: #192126; }
	button:disabled { cursor: not-allowed; opacity: .5; }
	.destination-picker { margin-top: 12px; padding-top: 13px; border-top: 1px solid rgba(233, 240, 242, .13); }
	.destination-picker h3 { margin-bottom: 9px; }
	.catalog-list { margin: 0 0 8px; padding: 0; border: 0; }
	.catalog-list legend { margin-bottom: 7px; color: #8c9aa0; font-size: 11px; }
	.path-option { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px solid rgba(233, 240, 242, .09); }
	.path-option label { display: flex; min-width: 0; align-items: center; gap: 8px; }
	code { overflow-wrap: anywhere; color: #e9f0f2; font: 500 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; }
	.selected-path, .folder-required, .quiet-empty { margin: 8px 0 0; color: #8c9aa0; font-size: 13px; line-height: 1.5; }
	.storage-guidance, .error { color: #ffb86f; font-size: 13px; line-height: 1.5; }
	.error, .add-status { margin: 8px 0 0; }
	.error { color: #ff9c91; }
	.add-status { color: #65e6cc; font-size: 13px; line-height: 1.5; }
	.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
	.folder-browser { margin-top: 11px; padding: 14px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 4px; background: #0d1114; }
	.path-buttons { display: grid; gap: 7px; margin-bottom: 12px; }
	.path-button { display: grid; gap: 3px; width: 100%; padding: 9px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 3px; color: #e9f0f2; background: #192126; text-align: left; }
	.path-button span { color: #8c9aa0; font-size: 12px; }
	.browser-current, .validation-result { margin: 10px 0; padding: 12px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 4px; }
	.browser-current code, .validation-result code { display: block; margin: 5px 0 10px; }
	.validation-success { border-color: rgba(101, 230, 204, .55); }
	.validation-error { border-color: rgba(255, 156, 145, .55); }
	.paste-path { margin-top: 12px; }
	.compact-row { margin-top: 5px; }

	.queue-section { margin-top: 46px; }
	.section-heading { display: flex; align-items: end; justify-content: space-between; margin: 0 4px 14px; }
	.count { display: grid; width: 30px; height: 30px; place-items: center; margin: 0; border: 1px solid rgba(233, 240, 242, .13); border-radius: 50%; }
	.card-list { display: grid; gap: 10px; }
	.download-card { overflow: hidden; border: 1px solid rgba(233, 240, 242, .13); border-left: 3px solid #65e6cc; border-radius: 5px; background: #13191d; }
	.card-main { min-width: 0; padding: 18px 20px 16px; }
	.card-title-row { display: flex; align-items: start; justify-content: space-between; gap: 16px; }
	.state { margin-bottom: 5px; color: #65e6cc; }
	.torrent-name { overflow-wrap: anywhere; }
	.progress-text { flex: 0 0 auto; color: #8c9aa0; font: 650 13px/1 ui-monospace, SFMono-Regular, Menlo, monospace; }
	progress { display: block; width: 100%; height: 5px; margin: 15px 0 12px; appearance: none; border: 0; border-radius: 10px; background: #263138; }
	.card-secondary-action { display: flex; justify-content: flex-end; gap: 12px; margin-top: 10px; }
	.details-button { min-height: 44px; padding-inline: 0; color: #b9c7cb; text-decoration: underline; text-underline-offset: 3px; }
	.card-action { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
	.card-remove-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; }
	.remove-from-list-button { text-decoration: underline; text-underline-offset: 3px; }
	.card-action-feedback { min-height: 1.5em; margin: 0; color: #8c9aa0; font-size: 13px; line-height: 1.5; }
	.card-action-feedback.error { color: #ff9c91; }
	.move-panel, .move-confirmation, .move-recovery, .download-recovery { margin-top: 14px; padding: 14px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 4px; background: #0d1114; }
	.move-panel h4, .move-confirmation h4 { margin-bottom: 10px; color: #e9f0f2; }
	.move-panel .primary-button, .move-confirmation .primary-button, .move-recovery .primary-button { margin-right: 8px; }
	.move-confirmation p, .move-recovery p, .download-recovery p { color: #b9c7cb; font-size: 13px; line-height: 1.5; }
	.move-feedback { margin: 12px 0 0; color: #8c9aa0; font-size: 13px; line-height: 1.5; }
	.move-paths { display: grid; gap: 10px; margin: 14px 0; }
	.move-paths div { display: grid; gap: 4px; }
	.move-paths dt { color: #8c9aa0; font-size: 11px; font-weight: 650; letter-spacing: .08em; text-transform: uppercase; }
	.move-paths dd { margin: 0; overflow-wrap: anywhere; }
	.download-folder { margin-top: 14px; }
	.download-folder label { display: block; margin-bottom: 6px; color: #8c9aa0; font-size: 11px; font-weight: 650; letter-spacing: .08em; text-transform: uppercase; }
	.folder-path-action { display: flex; min-width: 0; gap: 8px; }
	.canonical-path { width: 100%; min-width: 0; height: 44px; padding: 0 11px; overflow: hidden; border: 1px solid rgba(233, 240, 242, .13); border-radius: 3px; color: #e9f0f2; background: #0d1114; font: 500 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; text-overflow: ellipsis; white-space: nowrap; }
	.copy-path-button { flex: 0 0 auto; min-height: 44px; }
	.path-copy-feedback { min-height: 1.5em; margin: 6px 0 0; color: #8c9aa0; font-size: 12px; line-height: 1.5; }
	progress::-webkit-progress-bar { border-radius: inherit; background: #263138; }
	progress::-webkit-progress-value { border-radius: inherit; background: #65e6cc; }
	progress::-moz-progress-bar { border-radius: inherit; background: #65e6cc; }
	.empty-state { padding: 30px 20px; border: 1px dashed rgba(233, 240, 242, .18); border-radius: 5px; color: #8c9aa0; text-align: center; }
	.empty-state h3 { color: #e9f0f2; }
	.empty-state p { margin: 8px 0 18px; }
	.primary-link { display: inline-grid; min-height: 44px; place-items: center; padding: 0 14px; border-radius: 3px; color: #06231d; background: #65e6cc; font-weight: 720; text-decoration: none; }
	.completed-section .download-card { border-left-color: #4a9385; }

	.onboarding-shell { margin-top: 64px; padding: 28px; border: 1px solid rgba(233, 240, 242, .13); border-left: 4px solid #65e6cc; border-radius: 5px; background: #13191d; }
	.onboarding-shell h1 { margin-bottom: 24px; }
	.onboarding-shell h2 { margin-bottom: 10px; font-size: 20px; }
	.onboarding-shell p { line-height: 1.5; }
	.onboarding-shell input { display: block; width: 100%; height: 44px; margin: 0 0 12px; padding: 0 12px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 4px; color: #e9f0f2; background: #0d1114; }
	.onboarding-shell .primary-button, .onboarding-shell .text-button { margin: 8px 6px 0 0; }
	.path-readout { display: block; margin: 12px 0; padding: 10px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 4px; background: #0d1114; }
	.onboarding-shell a { color: #65e6cc; }

	.modal-overlay { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 14px; }
	.modal-scrim { position: absolute; inset: 0; border: 0; background: rgba(5, 8, 10, .76); }
	.modal-content { position: relative; z-index: 1; width: min(100%, 550px); max-height: min(80vh, 760px); overflow-y: auto; border: 1px solid rgba(233, 240, 242, .13); border-radius: 5px; background: #13191d; box-shadow: 0 24px 70px rgba(0, 0, 0, .45); }
	.removal-confirmation { width: min(100%, 500px); }
	.removal-confirmation .modal-body > p { color: #b9c7cb; line-height: 1.5; }
	.removal-confirmation-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 20px; }
	.modal-header { display: flex; justify-content: space-between; gap: 16px; padding: 20px; border-bottom: 1px solid rgba(233, 240, 242, .13); }
	.modal-body { padding: 20px; }
	.modal-body form > label:not(:first-child) { margin-top: 14px; }
	.modal-body input { margin-bottom: 0; }
	.settings-menu { display: grid; gap: 8px; }
	.settings-entry { display: flex; width: 100%; min-height: 72px; align-items: center; justify-content: space-between; gap: 16px; padding: 14px; border: 1px solid rgba(233, 240, 242, .13); border-radius: 4px; color: #e9f0f2; background: #192126; text-align: left; }
	.settings-entry:hover { background: #263138; }
	.settings-entry span:first-child { display: grid; gap: 4px; }
	.settings-entry strong { font-size: 14px; }
	.settings-entry small { color: #8c9aa0; font-size: 12px; line-height: 1.4; }
	.settings-entry > span:last-child { color: #65e6cc; font-size: 26px; line-height: 1; }
	.settings-back { min-height: 44px; margin: -8px 0 16px; padding-inline: 0; text-decoration: underline; text-underline-offset: 3px; }
	.settings-body h3 { margin-bottom: 10px; }
	.settings-description { color: #8c9aa0; line-height: 1.5; }
	.settings-success, .settings-error { padding: 10px; border-radius: 4px; font-size: 13px; }
	.settings-success { color: #65e6cc; background: rgba(101, 230, 204, .1); }
	.settings-error { color: #ff9c91; background: rgba(255, 156, 145, .1); }
	.settings-submit { width: 100%; margin-top: 18px; }
	.details-list { margin: 0; }
	.info-row { display: flex; justify-content: space-between; gap: 20px; padding: 14px 0; border-bottom: 1px solid rgba(233, 240, 242, .1); color: #8c9aa0; }
	.info-row dt { flex: 0 1 42%; font-size: 13px; }
	.info-row dd { min-width: 0; margin: 0; color: #e9f0f2; text-align: right; font-size: 13px; font-weight: 650; overflow-wrap: anywhere; }
	.info-row.path { display: grid; gap: 7px; }
	.info-row.path dd { text-align: left; }

	@media (min-width: 721px) { .details-overlay, .settings-overlay { justify-items: end; padding: 0; } .details-surface, .settings-surface { height: 100%; max-height: none; border-radius: 0; border-top: 0; border-right: 0; border-bottom: 0; } .details-surface { width: min(100%, 540px); } .settings-surface { width: min(100%, 440px); } }
	@media (max-width: 720px) { .app-shell { padding-top: 18px; } .add-panel { grid-template-columns: 1fr; gap: 20px; padding: 20px 16px; } .link-row { display: grid; } .link-row .primary-button { width: 100%; } .system-readout { display: none; } .details-overlay, .settings-overlay { padding: 0; } .details-surface, .settings-surface { width: 100%; height: 100%; max-height: none; border-radius: 0; border: 0; } }
	@media (max-width: 420px) { .app-shell, .onboarding-shell { width: min(100% - 20px, 880px); } .app-shell { padding-bottom: 48px; } .eyebrow { display: none; } .site-header { min-height: 64px; } .card-main { padding: 16px 14px; } .card-title-row { display: block; } .progress-text { display: block; margin-top: 8px; } .compact-row, .folder-path-action, .card-action, .card-remove-actions { display: grid; } .copy-path-button { width: 100%; } .info-row { display: grid; gap: 5px; } .info-row dd { text-align: left; } }
	@media (prefers-reduced-motion: reduce) { :global(*), :global(*::before), :global(*::after) { scroll-behavior: auto !important; transition-duration: .01ms !important; animation-duration: .01ms !important; } }
</style>
