import { BackendRequestError, BrowserWebUiBackendClient } from './backend-client';
import type {
	MoveStatus,
	OnboardingStatus,
	PathValidation,
	StorageVolume,
	TorrentCardAction,
	TorrentSnapshot,
	WebUiCoordinator
} from './contracts';

const ONBOARDING_POLL_INTERVAL_MS = 2000;
const ONBOARDING_REQUEST_TIMEOUT_MS = 5000;

function storageRecoveryGuidance(state: string | null): string | null {
	if (state === 'RevokedRuntime') {
		return 'Storage access was removed. In the Android app, restore storage access, then return here and try again.';
	}
	if (state && state !== 'Ready') {
		return 'Storage access needs attention. In the Android app, allow storage access, then return here and try again.';
	}
	return null;
}

function moveFailureGuidance(status: number | null, backendState?: string | null): string {
	const normalized = backendState?.toLowerCase() || '';
	if (normalized.includes('conflict')) {
		return 'Needs attention: files already exist at the target. Nothing was overwritten. Resolve the conflict or choose a different approved Download Folder.';
	}
	if (normalized.includes('interrupt') || normalized.includes('reject')) {
		return 'The move stopped before completion. The Download Folder has not changed and source files were not removed. Retry the move or cancel it.';
	}
	if (status === 503 || normalized.includes('storage') || normalized.includes('permission') || normalized.includes('unavailable') || normalized.includes('invalid destination') || normalized.includes('does not exist')) {
		return 'The target storage is unavailable. Reconnect it or restore storage access in the Android app, then try again.';
	}
	return 'We could not confirm this move. The Download Folder has not changed and files were not removed. Try again.';
}

function rejectedFolderGuidance(reason?: string | null): string {
	const normalized = reason?.toLowerCase() || '';
	if (normalized.includes('uri') || normalized.includes('absolute')) {
		return 'Choose a storage folder, not a document link or alias. Use a full folder path from a reported storage volume.';
	}
	if (normalized.includes('outside') || normalized.includes('volume')) {
		return 'Choose a folder inside one of the reported storage volumes.';
	}
	if (normalized.includes('exist') || normalized.includes('available') || normalized.includes('resolve')) {
		return 'That folder is unavailable. Reconnect the storage if needed, then choose it again.';
	}
	return 'That folder cannot be used. Choose a readable folder from a reported storage volume.';
}

export function createWebUiCoordinator(): WebUiCoordinator {
	const client = new BrowserWebUiBackendClient();
	// --- State (Svelte 5 runes) ---
	let onboardingStatus = $state<OnboardingStatus | null>(null);
	let onboardingLoading = $state(true);
	let onboardingError = $state('');
	let recommendedPath = $state<string | null>(null);
	let recommendedLoading = $state(false);
	let onboardingActionLoading = $state(false);
	let onboardingActionError = $state('');
	let showOnboardingPasswordChange = $state(false);
	let onboardingNewPassword = $state('');
	let onboardingPasswordConfirmation = $state('');
	let onboardingPoll: ReturnType<typeof setTimeout> | null = null;
	let onboardingRequest: AbortController | null = null;
	let normalUiStarted = false;
	let magnetUri = $state('');
	let addPending = $state(false);
	let addStatus = $state('');
	let addError = $state('');
	let wsConnected = $state(false);
	let wsConnecting = $state(false);
	let updatesPaused = $state(false);
	let lastSnapshot = $state<TorrentSnapshot[]>([]);
	let loading = $state(true);
	let showInfo = $state(false);
	let selectedTorrent = $state<TorrentSnapshot | null>(null);

	// Canonical destination selection (all paths come from authenticated backend responses).
	let storagePermission = $state<string | null>(null);
	let volumes = $state<StorageVolume[]>([]);
	let catalog = $state<string[]>([]);
	let latestSelected = $state<string | null>(null);
	let selectedDestination = $state<string | null>(null);
	let storageLoading = $state(true);
	let storageError = $state('');
	let storageMutationPending = $state(false);
	let showBrowser = $state(false);
	let browsedPath = $state<string | null>(null);
	let children = $state<string[]>([]);
	let pastedPath = $state('');
	let pathValidation = $state<PathValidation | null>(null);
	let validatingPath = $state(false);
	let moveTargets = $state<Record<number, string>>({});
	let recoverableMoveTargets = $state<Record<number, string>>({});
	let moveStatusRequests = new Set<number>();
	let moveErrors = $state<Record<number, string>>({});
	let moveMessages = $state<Record<number, string>>({});
	let moveLoading = $state<Record<number, boolean>>({});
	let torrentActionPending = $state<Record<number, TorrentCardAction>>({});
	let torrentActionErrors = $state<Record<number, string>>({});
	let storageReady = $derived(storagePermission === 'Ready');
	let storageRecoveryMessage = $derived(storageRecoveryGuidance(storagePermission));

	// WebSocket reference (used only for cleanup)
	let ws = $state<WebSocket | null>(null);

	// --- API helpers ---

	function startNormalUi() {
		if (normalUiStarted) return;
		normalUiStarted = true;
		connectWebSocket();
		void loadTorrents();
		void loadStorageState();
	}

	function scheduleOnboardingPoll() {
		if (onboardingPoll) clearTimeout(onboardingPoll);
		onboardingPoll = setTimeout(() => void loadOnboardingStatus(), ONBOARDING_POLL_INTERVAL_MS);
	}

	async function loadOnboardingStatus() {
		if (onboardingRequest) onboardingRequest.abort();
		onboardingRequest = new AbortController();
		const request = onboardingRequest;
		const timeout = setTimeout(() => request.abort(), ONBOARDING_REQUEST_TIMEOUT_MS);
		onboardingError = '';
		try {
			const status = await client.fetchJson<OnboardingStatus>('/api/onboarding/status', {
				signal: request.signal
			});
			onboardingStatus = status;
			if (status.completed) {
				startNormalUi();
			} else if (status.readiness !== 'Ready') {
				scheduleOnboardingPoll();
			} else if (!status.hasApprovedDestination) {
				void loadRecommendedDestination();
			}
		} catch (error) {
			onboardingError = request.signal.aborted
				? 'The onboarding status request timed out. Try again.'
				: error instanceof Error ? error.message : 'Unable to load onboarding status.';
		} finally {
			clearTimeout(timeout);
			if (onboardingRequest === request) onboardingRequest = null;
			onboardingLoading = false;
		}
	}

	async function loadRecommendedDestination() {
		if (recommendedLoading || recommendedPath) return;
		recommendedLoading = true;
		onboardingActionError = '';
		try {
			const response = await client.fetchJson<{ path: string }>('/api/onboarding/recommended-destination');
			recommendedPath = response.path;
		} catch (error) {
			onboardingActionError = error instanceof Error
				? error.message
				: 'Unable to prepare the recommended download folder.';
		} finally {
			recommendedLoading = false;
		}
	}

	async function confirmRecommendedDestination() {
		onboardingActionLoading = true;
		onboardingActionError = '';
		try {
			await client.fetchJson('/api/onboarding/recommended-destination', { method: 'POST' });
			recommendedPath = null;
			await loadOnboardingStatus();
		} catch (error) {
			onboardingActionError = error instanceof Error
				? error.message
				: 'Unable to use the recommended download folder.';
		} finally {
			onboardingActionLoading = false;
		}
	}

	function chooseAnotherOnboardingPassword() {
		showOnboardingPasswordChange = true;
		onboardingNewPassword = '';
		onboardingPasswordConfirmation = '';
		onboardingActionError = '';
	}

	function cancelOnboardingPasswordChange() {
		showOnboardingPasswordChange = false;
		onboardingNewPassword = '';
		onboardingPasswordConfirmation = '';
		onboardingActionError = '';
	}

	async function changeOnboardingPassword() {
		onboardingActionError = '';
		if (onboardingNewPassword !== onboardingPasswordConfirmation) {
			onboardingActionError = 'The Password confirmation does not match.';
			return;
		}
		onboardingActionLoading = true;
		try {
			await client.fetchJson('/api/onboarding/password', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ newPassword: onboardingNewPassword })
			});
			window.location.reload();
		} catch (error) {
			onboardingActionError = error instanceof Error
				? error.message
				: 'Unable to save the new Password.';
		} finally {
			onboardingActionLoading = false;
		}
	}

	async function deferOnboardingPassword() {
		onboardingActionLoading = true;
		onboardingActionError = '';
		try {
			const status = await client.fetchJson<OnboardingStatus>('/api/onboarding/password/defer', {
				method: 'POST'
			});
			onboardingStatus = status;
			if (status.completed) startNormalUi();
		} catch (error) {
			onboardingActionError = error instanceof Error
				? error.message
				: 'Unable to save the Password choice.';
		} finally {
			onboardingActionLoading = false;
		}
	}

	let snapshotRevision = 0;
	let latestTorrentRequest = 0;

	function replaceSnapshot(torrents: TorrentSnapshot[]) {
		// A snapshot replaces (never appends to) rendered state. Keep first backend occurrence
		// so a malformed repeated ID cannot duplicate a Download card.
		const nextSnapshot = Array.isArray(torrents)
			? torrents.filter((torrent, index, all) =>
				typeof torrent?.id === 'number' && all.findIndex((candidate) => candidate?.id === torrent.id) === index
			)
			: [];
		lastSnapshot = nextSnapshot;
		if (selectedTorrent) {
			const updatedSelection = nextSnapshot.find((torrent) => torrent.id === selectedTorrent?.id);
			if (updatedSelection) selectedTorrent = updatedSelection;
		}
		snapshotRevision += 1;
		for (const torrent of nextSnapshot) {
			if (isRecoverableMoveState(torrent.moveState) && !moveStatusRequests.has(torrent.id)) {
				moveStatusRequests.add(torrent.id);
				void refreshMoveStatus(torrent.id)
					// The recovery card remains safely cancellable when target verification is unavailable.
					.catch(() => undefined)
					.finally(() => moveStatusRequests.delete(torrent.id));
			}
		}
	}

	async function loadTorrents() {
		const requestId = ++latestTorrentRequest;
		const revisionAtRequestStart = snapshotRevision;
		try {
			const torrents = await client.fetchJson<TorrentSnapshot[]>('/api/torrents');
			if (requestId === latestTorrentRequest && revisionAtRequestStart === snapshotRevision) {
				replaceSnapshot(torrents);
			}
		} catch {
			// This background refresh has no dedicated error surface. Never place a raw backend
			// diagnostic beside the Add Download form; the stale authenticated snapshot remains.
			addError = 'Could not refresh Downloads. Check your connection and try again.';
		} finally {
			if (requestId === latestTorrentRequest) loading = false;
		}
	}

	async function loadStorageState(preferredPath: string | null = null) {
		storageLoading = true;
		storageError = '';
		try {
			const [permission, volumeList, destinationList, latest] = await Promise.all([
				client.fetchJson<{ state: string }>('/api/storage/permission'),
				client.fetchJson<StorageVolume[]>('/api/storage/volumes'),
				client.fetchJson<string[]>('/api/storage/catalog'),
				client.fetchJson<{ path?: string | null }>('/api/storage/latest-selected')
			]);
			storagePermission = permission.state;
			volumes = Array.isArray(volumeList) ? volumeList : [];
			catalog = Array.isArray(destinationList) ? destinationList : [];
			latestSelected = latest?.path || null;

			const candidate = preferredPath || latestSelected;
			selectedDestination = candidate && catalog.includes(candidate) ? candidate : null;
		} catch (e) {
			storageError = e instanceof Error ? e.message : 'Unable to load download folders';
			selectedDestination = null;
		} finally {
			storageLoading = false;
		}
	}

	async function browseDirectory(path: string) {
		if (!storageReady || storageMutationPending) return;
		storageError = '';
		pathValidation = null;
		try {
			children = await client.fetchJson<string[]>('/api/storage/children', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ path })
			});
			browsedPath = path;
		} catch {
			storageError = 'This storage volume is no longer available. Reconnect the storage if needed, then try again.';
			children = [];
		}
	}

	async function validatePath(path: string) {
		const candidate = path.trim();
		if (candidate.includes('://')) {
			pathValidation = {
				isValid: false,
				rejectionReason: 'Enter an absolute filesystem path. Choose a storage folder, not a document link or alias.'
			};
			return;
		}
		if (!candidate || !candidate.startsWith('/')) {
			pathValidation = {
				isValid: false,
				rejectionReason: 'Enter a full folder path from a reported storage volume.'
			};
			return;
		}
		validatingPath = true;
		storageError = '';
		try {
			const result = await client.fetchJson<PathValidation>('/api/storage/validate', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ path: candidate })
			});
			pathValidation = result.isValid && result.canonicalPath
				? result
				: { isValid: false, rejectionReason: rejectedFolderGuidance(result.rejectionReason) };
		} catch {
			pathValidation = null;
			storageError = 'We could not check that folder. Check storage access in the Android app, then try again.';
		} finally {
			validatingPath = false;
		}
	}

	function validateBrowsedPath() {
		if (browsedPath) void validatePath(browsedPath);
	}

	async function openDirectoryBrowser() {
		showBrowser = true;
		browsedPath = null;
		children = [];
		pathValidation = null;
		pastedPath = '';
		onboardingActionError = '';
		await loadStorageState();
	}

	function closeDirectoryBrowser() {
		showBrowser = false;
		browsedPath = null;
		children = [];
		pathValidation = null;
		pastedPath = '';
	}

	async function toggleDirectoryBrowser() {
		if (showBrowser) closeDirectoryBrowser();
		else await openDirectoryBrowser();
	}

	async function approveDestination(
		canonicalPath: string,
		surface: 'normal' | 'onboarding' = 'normal'
	): Promise<boolean> {
		if (storageMutationPending) return false;
		storageMutationPending = true;
		if (surface === 'onboarding') {
			onboardingActionLoading = true;
			onboardingActionError = '';
		} else {
			storageError = '';
		}
		try {
			const approved = await client.fetchJson<{ status?: string; path?: string }>('/api/storage/destinations', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ path: canonicalPath })
			});
			if (approved.status !== 'ok' || !approved.path) throw new Error('Missing approval acknowledgement');
			pathValidation = null;
			pastedPath = '';
			showBrowser = false;
			if (surface === 'onboarding') await loadOnboardingStatus();
			else await loadStorageState(approved.path);
			return true;
		} catch {
			const message = 'This folder could not be approved. Check that it is still available, then try again.';
			if (surface === 'onboarding') onboardingActionError = message;
			else storageError = message;
			return false;
		} finally {
			storageMutationPending = false;
			if (surface === 'onboarding') onboardingActionLoading = false;
		}
	}

	function selectApprovedDestination(path: string): Promise<boolean> {
		return approveDestination(path);
	}

	async function removeApprovedDestination(path: string): Promise<boolean> {
		if (storageMutationPending) return false;
		storageMutationPending = true;
		storageError = '';
		try {
			const acknowledgement = await client.fetchJson<{ status?: string }>('/api/storage/destinations', {
				method: 'DELETE',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ path })
			});
			if (acknowledgement.status !== 'ok') throw new Error('Missing removal acknowledgement');
			await loadStorageState();
			return true;
		} catch (error) {
			storageError = error instanceof BackendRequestError && error.status === 409
				? 'This Download Folder is still used by a Download. Move or remove that Download before forgetting this folder.'
				: 'This folder could not be forgotten. Check storage access, then try again.';
			return false;
		} finally {
			storageMutationPending = false;
		}
	}

	function clearAddFeedback() {
		addStatus = '';
		addError = '';
	}

	function isSupportedMagnetLink(value: string): boolean {
		try {
			const magnet = new URL(value);
			return magnet.protocol.toLowerCase() === 'magnet:' && magnet.search.length > 1;
		} catch {
			return false;
		}
	}

	function addFailureMessage(status: number): string {
		if (status === 401 || status === 403) return 'Your session needs to be signed in again. Refresh the page and try again.';
		if (status === 409) return 'Downloads setup needs attention in the Android app before you can add a Download.';
		if (status === 503) return 'Downloads is unavailable. Check the Android app and try again.';
		return 'We could not add that Download. Check the Download link and Download Folder, then try again.';
	}

	async function addMagnet() {
		if (addPending) return;
		const uri = magnetUri.trim();
		clearAddFeedback();
		if (!uri) {
			addError = 'Enter a Download link to continue.';
			return;
		}
		if (!isSupportedMagnetLink(uri)) {
			addError = 'Enter a valid magnet link that starts with magnet:?.';
			return;
		}
		if (!selectedDestination) {
			addError = 'Choose a Download Folder before adding this Download.';
			return;
		}

		addPending = true;
		addStatus = 'Adding Download…';
		try {
			const response = await client.request('/api/torrents/magnet', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ magnet: uri, destinationPath: selectedDestination })
			});
			if (!response.ok) {
				addStatus = '';
				addError = addFailureMessage(response.status);
				return;
			}

			const acknowledgement = await response.json().catch(() => null) as { id?: unknown } | null;
			if (typeof acknowledgement?.id !== 'number' || acknowledgement.id <= 0) {
				addStatus = '';
				addError = 'We could not confirm that Download was added. Please try again.';
				return;
			}

			magnetUri = '';
			addStatus = 'Download added.';
			await loadTorrents();
		} catch {
			addStatus = '';
			addError = 'Could not reach Downloads. Check your connection and try again.';
		} finally {
			addPending = false;
		}
	}


	type ControlAcknowledgement = { status?: unknown; queueId?: unknown };

	function torrentActionFailureMessage(action: TorrentCardAction, status?: number): string {
		if (status === 401 || status === 403) return 'Your session needs to be signed in again. Refresh the page and try again.';
		if (status === 404) return 'This Download is no longer available. Refresh the page and try again.';
		if (status === 503) return 'Downloads is unavailable. Check the Android app and try again.';
		if (action === 'remove-from-list') {
			return status === 409
				? 'This Download could not be removed from the list. Try again.'
				: 'We could not remove this Download from the list. Try again.';
		}
		if (action === 'remove-and-delete-files') {
			return status === 409
				? 'This Download and its files could not be removed. Try again.'
				: 'We could not remove this Download and delete its files. Try again.';
		}
		const verb = action === 'pause' ? 'pause' : 'resume';
		if (status === 409) return `This Download could not be ${verb}d. Try again.`;
		return `We could not ${verb} this Download. Try again.`;
	}

	async function changeTorrentRunState(id: number, action: 'pause' | 'resume'): Promise<boolean> {
		if (torrentActionPending[id]) return false;
		torrentActionPending = { ...torrentActionPending, [id]: action };
		torrentActionErrors = { ...torrentActionErrors, [id]: '' };
		try {
			const response = await client.request(`/api/torrents/${id}/${action}`, { method: 'PUT' });
			const acknowledgement = await response.json().catch(() => null) as ControlAcknowledgement | null;
			if (!response.ok) {
				torrentActionErrors = {
					...torrentActionErrors,
					[id]: torrentActionFailureMessage(action, response.status)
				};
				return false;
			}
			if (acknowledgement?.status !== 'ok' || typeof acknowledgement.queueId !== 'string' || !acknowledgement.queueId) {
				torrentActionErrors = {
					...torrentActionErrors,
					[id]: `We could not confirm that this Download was ${action === 'pause' ? 'paused' : 'resumed'}. Try again.`
				};
				return false;
			}
			await loadTorrents();
			return true;
		} catch {
			torrentActionErrors = {
				...torrentActionErrors,
				[id]: 'Could not reach Downloads. Check your connection and try again.'
			};
			return false;
		} finally {
			const { [id]: _, ...remainingPending } = torrentActionPending;
			torrentActionPending = remainingPending;
		}
	}

	function pauseTorrent(id: number): Promise<boolean> {
		return changeTorrentRunState(id, 'pause');
	}

	function resumeTorrent(id: number): Promise<boolean> {
		return changeTorrentRunState(id, 'resume');
	}

	async function removeTorrent(id: number, deleteFiles: boolean): Promise<boolean> {
		const action: TorrentCardAction = deleteFiles ? 'remove-and-delete-files' : 'remove-from-list';
		if (torrentActionPending[id]) return false;
		torrentActionPending = { ...torrentActionPending, [id]: action };
		torrentActionErrors = { ...torrentActionErrors, [id]: '' };
		try {
			const response = await client.request(`/api/torrents/${id}?deleteFiles=${deleteFiles}`, { method: 'DELETE' });
			const acknowledgement = await response.json().catch(() => null) as ControlAcknowledgement | null;
			if (!response.ok || acknowledgement?.status !== 'ok' || typeof acknowledgement.queueId !== 'string' || !acknowledgement.queueId) {
				torrentActionErrors = {
					...torrentActionErrors,
					[id]: torrentActionFailureMessage(action, response.status)
				};
				return false;
			}
			// Do not optimistically remove the card: the next authenticated snapshot is authoritative.
			await loadTorrents();
			return true;
		} catch {
			torrentActionErrors = {
				...torrentActionErrors,
				[id]: 'We could not confirm this removal. Refresh Downloads and try again.'
			};
			return false;
		} finally {
			const { [id]: _, ...remainingPending } = torrentActionPending;
			torrentActionPending = remainingPending;
		}
	}

	function setMoveTarget(id: number, destinationPath: string) {
		moveTargets = { ...moveTargets, [id]: destinationPath };
	}

	function moveTargetFor(torrent: TorrentSnapshot): string {
		const sourcePath = torrent.destinationPath || torrent.savePath;
		const requested = moveTargets[torrent.id];
		if (requested && requested !== sourcePath && catalog.includes(requested)) return requested;
		return catalog.find((path) => path !== sourcePath) ?? '';
	}

	function recoverableMoveTargetFor(torrent: TorrentSnapshot): string {
		const sourcePath = torrent.destinationPath || torrent.savePath;
		const target = recoverableMoveTargets[torrent.id] || '';
		return target !== sourcePath && catalog.includes(target) ? target : '';
	}

	function isRecoverableMoveState(moveState?: string | null): boolean {
		return moveState === 'move-interrupted' || moveState === 'interrupted' || moveState === 'storage-conflict';
	}

	async function refreshMoveStatus(id: number) {
		const status = await client.fetchJson<MoveStatus>(`/api/torrents/${id}/move/status`);
		if (status.targetPath) {
			setMoveTarget(id, status.targetPath);
			recoverableMoveTargets = { ...recoverableMoveTargets, [id]: status.targetPath };
		}
	}

	async function submitMove(id: number, destinationPath: string, endpoint: 'move' | 'move/retry') {
		const torrent = lastSnapshot.find((entry) => entry.id === id);
		const sourcePath = torrent?.destinationPath || torrent?.savePath;
		if (!destinationPath || !catalog.includes(destinationPath) || destinationPath === sourcePath) {
			moveErrors = { ...moveErrors, [id]: 'Choose another approved Download Folder.' };
			return;
		}
		if (moveLoading[id]) return;
		moveLoading = { ...moveLoading, [id]: true };
		moveErrors = { ...moveErrors, [id]: '' };
		moveMessages = { ...moveMessages, [id]: '' };
		try {
			const response = await client.request(`/api/torrents/${id}/${endpoint}`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ destinationPath })
			});
			const result = await response.json().catch(() => ({})) as MoveStatus & { status?: string; error?: string };
			if (!response.ok || result.status !== 'ok') {
				moveErrors = { ...moveErrors, [id]: moveFailureGuidance(response.status, result.status || result.error) };
				return;
			}
			await refreshMoveStatus(id);
			await loadTorrents();
			moveMessages = { ...moveMessages, [id]: 'Move request accepted. Moving files.' };
		} catch (error) {
			const requestError = error instanceof BackendRequestError ? error : null;
			moveErrors = {
				...moveErrors,
				[id]: moveFailureGuidance(requestError?.status ?? null, requestError?.message)
			};
		} finally {
			moveLoading = { ...moveLoading, [id]: false };
		}
	}

	function moveTorrent(id: number, destinationPath: string) {
		return submitMove(id, destinationPath, 'move');
	}

	function retryMove(id: number, destinationPath: string) {
		return submitMove(id, destinationPath, 'move/retry');
	}

	async function cancelMove(id: number) {
		if (moveLoading[id]) return;
		moveLoading = { ...moveLoading, [id]: true };
		moveErrors = { ...moveErrors, [id]: '' };
		moveMessages = { ...moveMessages, [id]: '' };
		try {
			const acknowledgement = await client.fetchJson<{ status?: string }>(`/api/torrents/${id}/move/cancel`, { method: 'POST' });
			if (acknowledgement.status !== 'ok') throw new Error('Missing cancellation acknowledgement');
			await loadTorrents();
			moveMessages = { ...moveMessages, [id]: 'Move cancelled. The Download Folder has not changed and files were not removed.' };
		} catch (error) {
			const requestError = error instanceof BackendRequestError ? error : null;
			moveErrors = {
				...moveErrors,
				[id]: moveFailureGuidance(requestError?.status ?? null, requestError?.message)
			};
		} finally {
			moveLoading = { ...moveLoading, [id]: false };
		}
	}


	// --- Settings / password change ---
	let showSettings = $state(false);
	let currentPassword = $state('');
	let newPassword = $state('');
	let settingsSaving = $state(false);
	let settingsMessage = $state('');
	let settingsError = $state('');

	async function changePassword() {
		if (settingsSaving) return;
		settingsSaving = true;
		settingsMessage = '';
		settingsError = '';

		try {
			const res = await client.request('/api/settings/password', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					Authorization: 'Basic ' + btoa(':' + currentPassword)
				},
				body: JSON.stringify({ currentPassword, newPassword })
			});

			if (res.ok) {
				currentPassword = '';
				newPassword = '';
				// HTTP Basic credentials are browser-owned. A full reload makes the browser
				// authenticate with the newly saved Password before returning to Downloads.
				window.location.reload();
				return;
			}

			const err = await res.json().catch(() => ({ error: 'Unknown error' }));
			settingsError = err.error || `HTTP ${res.status}`;
		} catch (e) {
			settingsError = e instanceof Error ? e.message : 'Network error';
		} finally {
			settingsSaving = false;
		}
	}

	/** Opens a secondary surface from the authoritative authenticated snapshot. */
	async function toggleInfo(torrent: TorrentSnapshot) {
		selectedTorrent = torrent;
		showInfo = true;
	}

	function closeInfo() {
		showInfo = false;
		selectedTorrent = null;
	}

	// --- WebSocket connection ---

	let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
	let disposed = false;

	function connectWebSocket() {
		if (disposed || ws) return;
		wsConnecting = true;
		const socket = client.openProgressSocket();
		ws = socket;

		socket.onopen = () => {
			if (ws !== socket || disposed) return;
			wsConnecting = false;
			wsConnected = true;
			// Keep stale cards marked until this authenticated socket sends its replacement snapshot.
		};

		socket.onclose = () => {
			if (ws !== socket) return;
			ws = null;
			wsConnected = false;
			wsConnecting = false;
			if (!disposed && normalUiStarted) {
				updatesPaused = true;
				reconnectTimer = setTimeout(connectWebSocket, 1500);
			}
		};

		socket.onerror = () => {
			if (ws === socket) wsConnecting = false;
		};

		socket.onmessage = (event: MessageEvent) => {
			if (ws !== socket || disposed) return;
			try {
				const data = JSON.parse(event.data);
				if (data.type === 'torrents' && Array.isArray(data.data)) {
					replaceSnapshot(data.data);
					updatesPaused = false;
				}
			} catch {
				// Ignore malformed live messages; the next authenticated snapshot remains usable.
			}
			if (loading) loading = false;
		};
	}


	function formatBytes(bytes: number) {
		if (bytes === 0) return '—';
		const units = ['B', 'KB', 'MB', 'GB'];
		let i = 0;
		while (bytes >= 1024 && i < units.length - 1) {
			bytes /= 1024;
			i++;
		}
		return `${bytes.toFixed(1)} ${units[i]}`;
	}


	function start() {
		disposed = false;
		void loadOnboardingStatus();
	}

	function dispose() {
		disposed = true;
		normalUiStarted = false;
		if (onboardingPoll) clearTimeout(onboardingPoll);
		onboardingPoll = null;
		if (onboardingRequest) onboardingRequest.abort();
		onboardingRequest = null;
		if (reconnectTimer) clearTimeout(reconnectTimer);
		reconnectTimer = null;
		const socket = ws;
		ws = null;
		wsConnected = false;
		wsConnecting = false;
		updatesPaused = false;
		socket?.close();
	}

	return {
		get onboardingStatus() { return onboardingStatus; },
		get onboardingLoading() { return onboardingLoading; },
		get onboardingError() { return onboardingError; },
		get recommendedPath() { return recommendedPath; },
		get recommendedLoading() { return recommendedLoading; },
		get onboardingActionLoading() { return onboardingActionLoading; },
		get onboardingActionError() { return onboardingActionError; },
		get showOnboardingPasswordChange() { return showOnboardingPasswordChange; },
		get onboardingNewPassword() { return onboardingNewPassword; },
		get onboardingPasswordConfirmation() { return onboardingPasswordConfirmation; },
		get magnetUri() { return magnetUri; },
		get addPending() { return addPending; },
		get addStatus() { return addStatus; },
		get addError() { return addError; },
		get wsConnected() { return wsConnected; },
		get wsConnecting() { return wsConnecting; },
		get updatesPaused() { return updatesPaused; },
		get lastSnapshot() { return lastSnapshot; },
		get loading() { return loading; },
		get storagePermission() { return storagePermission; },
		get volumes() { return volumes; },
		get catalog() { return catalog; },
		get selectedDestination() { return selectedDestination; },
		get storageLoading() { return storageLoading; },
		get storageError() { return storageError; },
		get storageRecoveryMessage() { return storageRecoveryMessage; },
		get storageMutationPending() { return storageMutationPending; },
		get showBrowser() { return showBrowser; },
		get browsedPath() { return browsedPath; },
		get children() { return children; },
		get pastedPath() { return pastedPath; },
		get pathValidation() { return pathValidation; },
		get validatingPath() { return validatingPath; },
		get storageReady() { return storageReady; },
		get moveErrors() { return moveErrors; },
		get moveMessages() { return moveMessages; },
		get moveLoading() { return moveLoading; },
		get torrentActionPending() { return torrentActionPending; },
		get torrentActionErrors() { return torrentActionErrors; },
		get showInfo() { return showInfo; },
		get selectedTorrent() { return selectedTorrent; },
		get showSettings() { return showSettings; },
		get currentPassword() { return currentPassword; },
		get newPassword() { return newPassword; },
		get settingsSaving() { return settingsSaving; },
		get settingsMessage() { return settingsMessage; },
		get settingsError() { return settingsError; },
		set showOnboardingPasswordChange(value) { showOnboardingPasswordChange = value; },
		set onboardingNewPassword(value) { onboardingNewPassword = value; },
		set onboardingPasswordConfirmation(value) { onboardingPasswordConfirmation = value; },
		set magnetUri(value) { magnetUri = value; },
		set showBrowser(value) { showBrowser = value; },
		set pastedPath(value) { pastedPath = value; },
		set showInfo(value) { showInfo = value; },
		set showSettings(value) { showSettings = value; },
		set currentPassword(value) { currentPassword = value; },
		set newPassword(value) { newPassword = value; },
		set settingsMessage(value) { settingsMessage = value; },
		set settingsError(value) { settingsError = value; },
		confirmRecommendedDestination,
		chooseAnotherOnboardingPassword,
		cancelOnboardingPasswordChange,
		changeOnboardingPassword,
		deferOnboardingPassword,
		toggleDirectoryBrowser,
		openDirectoryBrowser,
		closeDirectoryBrowser,
		browseDirectory,
		validateBrowsedPath,
		validatePath,
		approveDestination,
		selectApprovedDestination,
		removeApprovedDestination,
		addMagnet,
		clearAddFeedback,
		pauseTorrent,
		resumeTorrent,
		removeTorrent,
		setMoveTarget,
		moveTargetFor,
		recoverableMoveTargetFor,
		isRecoverableMoveState,
		moveTorrent,
		retryMove,
		cancelMove,
		changePassword,
		toggleInfo,
		closeInfo,
		formatBytes,
		start,
		dispose
	};
}
