<script lang="ts">
	import { onMount, onDestroy } from 'svelte';

	type TorrentSnapshot = {
		id: number;
		name: string;
		state: string;
		progress: number;
		downloadRate: number;
		uploadRate: number;
		peers: number;
		savePath: string;
		destinationPath?: string | null;
		queueId?: string | null;
		destinationStatus?: string | null;
		moveState?: string | null;
	};
	type StorageVolume = { path: string; description?: string; isRemovable?: boolean };
	type PathValidation = {
		isValid: boolean;
		canonicalPath?: string | null;
		rejectionReason?: string | null;
	};
	type ProgressAlert = { type: 'alert'; alertType: string; message?: string };
	type MoveStatus = { phase: string; sourcePath?: string | null; targetPath?: string | null };
	type InterruptedMove = { torrentId: number; phase: string; targetPath?: string | null };

	// --- State (Svelte 5 runes) ---
	let magnetUri = $state('');
	let addError = $state('');
	let wsConnected = $state(false);
	let wsConnecting = $state(false);
	let lastSnapshot = $state<TorrentSnapshot[]>([]);
	let loading = $state(true);

	// Canonical destination selection (all paths come from authenticated backend responses).
	let storagePermission = $state<string | null>(null);
	let volumes = $state<StorageVolume[]>([]);
	let catalog = $state<string[]>([]);
	let latestSelected = $state<string | null>(null);
	let selectedDestination = $state<string | null>(null);
	let storageLoading = $state(true);
	let storageError = $state('');
	let showBrowser = $state(false);
	let browsedPath = $state<string | null>(null);
	let children = $state<string[]>([]);
	let pastedPath = $state('');
	let pathValidation = $state<PathValidation | null>(null);
	let validatingPath = $state(false);
	let moveTargets = $state<Record<number, string>>({});
	let moveErrors = $state<Record<number, string>>({});
	let moveLoading = $state<Record<number, boolean>>({});
	let storageReady = $derived(storagePermission === 'Ready');

	// WebSocket reference (used only for cleanup)
	let ws = $state<WebSocket | null>(null);

	// --- API helpers ---

	async function fetchJson<T = any>(url: string, options?: RequestInit): Promise<T> {
		const response = await fetch(url, options);
		if (!response.ok) {
			const body = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
			throw new Error(body.error || `HTTP ${response.status}`);
		}
		return response.json();
	}

	async function loadTorrents() {
		try {
			const torrents = await fetchJson<TorrentSnapshot[]>('/api/torrents');
			lastSnapshot = Array.isArray(torrents) ? torrents : [];
		} catch (e) {
			addError = e instanceof Error ? e.message : 'Unable to load torrents.';
		} finally {
			loading = false;
		}
	}

	async function loadStorageState(preferredPath: string | null = null) {
		storageLoading = true;
		storageError = '';
		try {
			const [permission, volumeList, destinationList, latest] = await Promise.all([
				fetchJson('/api/storage/permission'),
				fetchJson('/api/storage/volumes'),
				fetchJson('/api/storage/catalog'),
				fetchJson('/api/storage/latest-selected')
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
		storageError = '';
		pathValidation = null;
		try {
			children = await fetchJson(`/api/storage/children/${encodeURIComponent(path)}`);
			browsedPath = path;
		} catch (e) {
			storageError = e instanceof Error ? e.message : 'Unable to browse this folder';
			children = [];
		}
	}

	async function validatePath(path: string) {
		if (!path || !path.startsWith('/')) {
			pathValidation = { isValid: false, rejectionReason: 'Enter an absolute filesystem path.' };
			return;
		}
		validatingPath = true;
		storageError = '';
		try {
			pathValidation = await fetchJson('/api/storage/validate', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ path })
			});
		} catch (e) {
			pathValidation = null;
			storageError = e instanceof Error ? e.message : 'Unable to validate this folder';
		} finally {
			validatingPath = false;
		}
	}

	function validateBrowsedPath() {
		if (browsedPath) validatePath(browsedPath);
	}

	function approveValidatedPath() {
		const canonicalPath = pathValidation?.canonicalPath;
		if (canonicalPath) approveDestination(canonicalPath);
	}

	async function approveDestination(canonicalPath: string) {
		storageError = '';
		try {
			const approved = await fetchJson(
				`/api/storage/destinations/${encodeURIComponent(canonicalPath)}`,
				{ method: 'POST' }
			);
			pathValidation = null;
			pastedPath = '';
			await loadStorageState(approved.path);
		} catch (e) {
			storageError = e instanceof Error ? e.message : 'Unable to approve this folder';
		}
	}

	async function selectApprovedDestination(path: string) {
		await approveDestination(path);
	}

	async function removeApprovedDestination(path: string) {
		storageError = '';
		try {
			await fetchJson(`/api/storage/destinations/${encodeURIComponent(path)}`, { method: 'DELETE' });
			await loadStorageState();
		} catch (e) {
			storageError = e instanceof Error ? e.message : 'Unable to remove this folder.';
		}
	}

	async function addMagnet() {
		const uri = magnetUri.trim();
		if (!uri) {
			addError = 'Please enter a magnet URI';
			return;
		}
		if (!selectedDestination) {
			addError = 'Choose a download folder before adding this torrent.';
			return;
		}

		try {
			const res = await fetch('/api/torrents/magnet', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ magnet: uri, destinationPath: selectedDestination }),
			});

			if (!res.ok) {
				const err = await res.json().catch(() => ({ error: 'Unknown error' }));
				addError = err.error || `HTTP ${res.status}`;
				return;
			}

			const data = await res.json();
			magnetUri = '';
			addError = '';

			// Add torrent to local list immediately for responsive UI
			const newTorrent = {
				id: data.id,
				name: uri.substring(uri.indexOf('&dn=') + 4) || 'Unknown',
				state: 'downloading_metadata',
				progress: 0,
				downloadRate: 0,
				uploadRate: 0,
				peers: 0,
				savePath: '',
				destinationPath: selectedDestination,
				destinationStatus: data.status === 'storage_conflict' ? 'storage_conflict' : null
			};
			lastSnapshot = [...lastSnapshot, newTorrent];

			// Show success message
			console.log(`Added torrent: ${data.id}`);
		} catch (e) {
			addError = e instanceof Error ? e.message : 'Network error';
		}
	}

	function updateTorrentState(id: number, state: string) {
		lastSnapshot = lastSnapshot.map((torrent) => torrent.id === id ? { ...torrent, state } : torrent);
	}

	async function pauseTorrent(id: number) {
		try {
			const res = await fetch(`/api/torrents/${id}/pause`, { method: 'PUT' });
			if (!res.ok) {
				const err = await res.json().catch(() => ({ error: 'Failed to pause' }));
				alert(err.error || 'Failed to pause torrent');
			} else {
				updateTorrentState(id, 'paused');
			}
		} catch (e) {
			alert(e instanceof Error ? e.message : 'Network error');
		}
	}

	async function resumeTorrent(id: number) {
		try {
			const res = await fetch(`/api/torrents/${id}/resume`, { method: 'PUT' });
			if (!res.ok) {
				const err = await res.json().catch(() => ({ error: 'Failed to resume' }));
				alert(err.error || 'Failed to resume torrent');
			} else {
				updateTorrentState(id, 'downloading');
			}
		} catch (e) {
			alert(e instanceof Error ? e.message : 'Network error');
		}
	}

	async function removeTorrent(id: number, deleteFiles: boolean) {
		if (!deleteFiles && !confirm('Remove this torrent without deleting files?')) return;
		try {
			const res = await fetch(`/api/torrents/${id}?deleteFiles=${deleteFiles}`, { method: 'DELETE' });
			if (!res.ok) {
				const err = await res.json().catch(() => ({ error: 'Failed to remove' }));
				alert(err.error || 'Failed to remove torrent');
			} else {
				lastSnapshot = lastSnapshot.filter((torrent) => torrent.id !== id);
			}
		} catch (e) {
			alert(e instanceof Error ? e.message : 'Network error');
		}
	}

	function setMoveTarget(id: number, destinationPath: string) {
		moveTargets = { ...moveTargets, [id]: destinationPath };
	}

	function moveTargetFor(torrent: TorrentSnapshot): string {
		return moveTargets[torrent.id] ?? catalog.find((path) => path !== torrent.destinationPath) ?? '';
	}

	function updateMoveState(id: number, moveState: string | null) {
		lastSnapshot = lastSnapshot.map((torrent) =>
			torrent.id === id ? { ...torrent, moveState } : torrent
		);
	}

	function isRecoverableMoveState(moveState?: string | null): boolean {
		return moveState === 'move-interrupted' || moveState === 'interrupted' || moveState === 'storage-conflict';
	}

	async function refreshMoveStatus(id: number) {
		const status = await fetchJson<MoveStatus>(`/api/torrents/${id}/move/status`);
		if (status.targetPath) setMoveTarget(id, status.targetPath);
		updateMoveState(id, status.phase === 'none' ? null : status.phase);
	}

	async function submitMove(id: number, destinationPath: string, endpoint: 'move' | 'move/retry') {
		if (!destinationPath) {
			moveErrors = { ...moveErrors, [id]: 'Choose an approved destination.' };
			return;
		}
		moveLoading = { ...moveLoading, [id]: true };
		moveErrors = { ...moveErrors, [id]: '' };
		try {
			const response = await fetch(`/api/torrents/${id}/${endpoint}`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ destinationPath })
			});
			const result = await response.json().catch(() => ({})) as MoveStatus & { status?: string; error?: string };
			if (!response.ok && result.status !== 'interrupted' && result.status !== 'storage_conflict') {
				throw new Error(result.error || `HTTP ${response.status}`);
			}
			updateMoveState(id, result.phase);
			await refreshMoveStatus(id);
		} catch (e) {
			moveErrors = { ...moveErrors, [id]: e instanceof Error ? e.message : 'Unable to update the move.' };
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
		moveLoading = { ...moveLoading, [id]: true };
		moveErrors = { ...moveErrors, [id]: '' };
		try {
			await fetchJson(`/api/torrents/${id}/move/cancel`, { method: 'POST' });
			updateMoveState(id, null);
		} catch (e) {
			moveErrors = { ...moveErrors, [id]: e instanceof Error ? e.message : 'Unable to cancel move.' };
		} finally {
			moveLoading = { ...moveLoading, [id]: false };
		}
	}

	async function loadInterruptedMoves() {
		try {
			const moves = await fetchJson<InterruptedMove[]>('/api/storage/moves');
			moves.forEach((move) => {
				if (move.targetPath) setMoveTarget(move.torrentId, move.targetPath);
				updateMoveState(move.torrentId, move.phase.toLowerCase().replace('_', '-'));
			});
		} catch (e) {
			storageError = e instanceof Error ? e.message : 'Unable to load interrupted moves.';
		}
	}

	let showInfo = $state(false);
	let selectedTorrent = $state<TorrentSnapshot | null>(null);

	// --- Settings / password change ---
	let showSettings = $state(false);
	let currentPassword = $state('');
	let newPassword = $state('');
	let settingsMessage = $state('');
	let settingsError = $state('');

	async function changePassword() {
		settingsMessage = '';
		settingsError = '';

		try {
			const res = await fetch('/api/settings/password', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					Authorization: 'Basic ' + btoa(':' + currentPassword),
				},
				body: JSON.stringify({ currentPassword, newPassword }),
			});

			if (res.ok) {
				settingsMessage = 'Password changed successfully. Your browser will re-prompt for the new password.';
				currentPassword = '';
				newPassword = '';
			} else {
				const err = await res.json().catch(() => ({ error: 'Unknown error' }));
				settingsError = err.error || `HTTP ${res.status}`;
			}
		} catch (e) {
			settingsError = e instanceof Error ? e.message : 'Network error';
		}
	}

	async function toggleInfo(torrent: TorrentSnapshot) {
		if (showInfo && selectedTorrent?.id === torrent.id) {
			closeInfo();
			return;
		}
		selectedTorrent = torrent;
		showInfo = true;
		try {
			const destination = await fetchJson<{ canonicalPath?: string | null; path?: string }>(
				`/api/torrents/${torrent.id}/destination`
			);
			if (selectedTorrent?.id === torrent.id) {
				selectedTorrent = {
					...torrent,
					destinationPath: destination.canonicalPath ?? destination.path ?? torrent.destinationPath
				};
			}
		} catch (e) {
			moveErrors = { ...moveErrors, [torrent.id]: e instanceof Error ? e.message : 'Unable to load destination.' };
		}
	}

	function closeInfo() {
		console.log('closeInfo called');
		showInfo = false;
		selectedTorrent = null;
	}

	// --- WebSocket connection ---

	function connectWebSocket() {
		wsConnecting = true;

		const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
		const socket = new WebSocket(`${protocol}://${location.host}/ws/progress`);
		ws = socket;

		socket.onopen = () => {
			wsConnecting = false;
			wsConnected = true;
		};

		socket.onclose = () => {
			wsConnected = false;
			wsConnecting = false;

			// Reconnect after a delay (exponential backoff up to 15s)
			setTimeout(() => {
				if (!ws || ws.readyState === WebSocket.CLOSED) {
					connectWebSocket();
				}
			}, 1500);
		};

		socket.onerror = () => {
			wsConnecting = false;
		};

		socket.onmessage = (event: MessageEvent) => {
			try {
				const data = JSON.parse(event.data);

				if (data.type === 'torrents' && Array.isArray(data.data)) {
					lastSnapshot = data.data;
				} else if (data.type === 'alert') {
					handleAlert(data);
				}
			} catch (e) {
				console.warn('WebSocket parse error:', e);
			}

			if (loading) loading = false;
		};
	}

	function handleAlert(alert: ProgressAlert) {
		switch (alert.alertType) {
			case 'torrent_finished':
				console.log('Torrent finished');
				break;
			case 'error':
				console.error('Torrent operation failed');
				break;
			case 'state_changed':
				console.log('Torrent state changed');
				break;
		}
	}

	function sendPing() {
		if (ws && ws.readyState === WebSocket.OPEN) {
			ws.send('ping');
		}
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

	function formatProgress(progress: number) {
		return `${(progress * 100).toFixed(1)}%`;
	}

	function stateColor(state: string) {
		switch (state) {
			case 'downloading': return '#2196F3';
			case 'seeding': case 'finished': return '#4CAF50';
			case 'paused': case 'pause_requested': return '#FF9800';
			case 'error': case 'checking_files': return '#f44336';
			default: return '#9E9E9E';
		}
	}

	function stateIcon(state: string) {
		switch (state) {
			case 'downloading': return '⬇️';
			case 'seeding': case 'finished': return '⬆️';
			case 'paused': case 'pause_requested': return '⏸️';
			case 'checking_files': case 'checking_resume_data': return '🔍';
			default: return '❓';
		}
	}

	function stateDisplayName(state: string) {
		if (state === 'pause_requested') return 'Pausing';
		return state;
	}

	onMount(() => {
		connectWebSocket();
		void loadTorrents();
		void loadStorageState().then(loadInterruptedMoves);
	});

	onDestroy(() => {
		if (ws) ws.close();
	});
</script>

<svelte:head>
	<title>Torrent WebUI</title>
</svelte:head>

<div class="container">
	<header>
		<h1>Torrent WebUI</h1>
		<div class="header-actions">
			<button class="settings-btn" on:click={() => { showSettings = !showSettings; settingsMessage = ''; settingsError = ''; }} title="Settings">⚙️</button>
			<div class="status">
				<span class="ws-indicator" class:connected={wsConnected} class:connecting={wsConnecting}>
					{wsConnecting ? '⏳ Connecting...' : wsConnected ? '🟢 Connected' : '🔴 Disconnected'}
				</span>
			</div>
		</div>
	</header>

	<section class="destination-picker">
		<h2>Download folder</h2>
		{#if storageLoading}
			<p class="loading compact">Loading available folders…</p>
		{:else if !storageReady}
			<p class="storage-guidance">
				Storage permission is required. Grant All Files Access in the Android app before choosing a download folder.
			</p>
		{:else}
			{#if catalog.length > 0}
				<fieldset class="catalog-list">
					<legend>Approved folders</legend>
					{#each catalog as path}
						<div class="path-option">
							<label>
								<input
									type="radio"
									name="destination"
									checked={selectedDestination === path}
									on:change={() => selectApprovedDestination(path)}
								/>
								<code>{path}</code>
							</label>
							<button class="forget-destination" on:click={() => removeApprovedDestination(path)}>Forget</button>
						</div>
					{/each}
				</fieldset>
			{:else}
				<p class="empty compact">Choose and approve a folder before adding a torrent.</p>
			{/if}

			<button class="secondary-button" on:click={() => { showBrowser = !showBrowser; pathValidation = null; }}>
				{showBrowser ? 'Hide folder browser' : 'Choose another folder'}
			</button>

			{#if showBrowser}
				<div class="folder-browser">
					<h3>Storage roots</h3>
					<div class="path-buttons">
						{#each volumes as volume}
							<button class="path-button" on:click={() => browseDirectory(volume.path)}>
								{#if volume.description}<span>{volume.description}</span>{/if}
								<code>{volume.path}</code>
							</button>
						{/each}
					</div>

					{#if browsedPath}
						<div class="browser-current">
							<p>Current folder</p>
							<code>{browsedPath}</code>
							<button on:click={validateBrowsedPath} disabled={validatingPath}>
								Check this folder
							</button>
						</div>
						<h3>Child folders</h3>
						{#if children.length === 0}
							<p class="empty compact">No selectable child folders returned by the device.</p>
						{:else}
							<div class="path-buttons">
								{#each children as childPath}
									<button class="path-button" on:click={() => browseDirectory(childPath)}>
										<code>{childPath}</code>
									</button>
								{/each}
							</div>
						{/if}
					{/if}

					<div class="paste-path">
						<label for="destination-path">Or paste an absolute path</label>
						<div class="input-row">
							<input id="destination-path" type="text" bind:value={pastedPath} placeholder="/storage/…" />
							<button on:click={() => validatePath(pastedPath.trim())} disabled={validatingPath}>Check folder</button>
						</div>
					</div>
				</div>
			{/if}

			{#if pathValidation}
				<div class:validation-success={pathValidation.isValid} class:validation-error={!pathValidation.isValid} class="validation-result">
					{#if pathValidation.isValid && pathValidation.canonicalPath}
						<p>Verified canonical path</p>
						<code>{pathValidation.canonicalPath}</code>
						<button on:click={approveValidatedPath}>Use this folder</button>
					{:else}
						<p>{pathValidation.rejectionReason || 'This folder cannot be used.'}</p>
					{/if}
				</div>
			{/if}
		{/if}
		{#if selectedDestination}
			<p class="selected-path">Downloads will be saved to <code>{selectedDestination}</code></p>
		{/if}
		{#if storageError}<p class="error">{storageError}</p>{/if}
	</section>

	<section class="add-torrent">
		<h2>Add Torrent</h2>
		<div class="input-row">
			<input
				type="text"
				bind:value={magnetUri}
				placeholder="Paste magnet URI or torrent link..."
				on:keydown={(e) => e.key === 'Enter' && addMagnet()}
			/>
			<button on:click={addMagnet} disabled={!storageReady || !selectedDestination}>Add</button>
		</div>
		{#if !selectedDestination}
			<p class="folder-required">Choose a download folder to continue.</p>
		{/if}
		{#if addError}
			<p class="error">{addError}</p>
		{/if}
	</section>

	<section class="torrent-list">
		<h2>Torrents ({lastSnapshot.length})</h2>

		{#if loading}
			<p class="loading">Loading torrents...</p>
		{:else if lastSnapshot.length === 0}
			<p class="empty">No torrents. Add one above to get started.</p>
		{:else}
			{#each lastSnapshot as torrent (torrent.id)}
				<article class="torrent-card">
					<div class="card-header">
						<span class="state-badge" style="--color: {stateColor(torrent.state)}">
							{stateIcon(torrent.state)} {stateDisplayName(torrent.state)}
						</span>
						<h3 class="torrent-name">{torrent.name}</h3>
					</div>

					<div class="progress-section">
						<progress value={torrent.progress * 100} max="100"></progress>
						<span class="progress-text">{formatProgress(torrent.progress)}</span>
					</div>

					<div class="torrent-meta">
						<span>{formatBytes(torrent.downloadRate)}↓</span>
						<span>{formatBytes(torrent.uploadRate)}↑</span>
						<span>👥 {torrent.peers} peers</span>
					</div>

					{#if torrent.destinationPath || torrent.savePath}
						<p class="save-path">📁 {torrent.destinationPath || torrent.savePath}</p>
					{/if}

					{#if torrent.destinationStatus === 'destination_unavailable'}
						<p class="move-warning">⏸️ Destination unavailable — torrent paused until storage returns.</p>
					{:else if torrent.destinationStatus === 'storage_conflict'}
						<p class="move-warning">⚠️ Storage conflict — existing torrent data did not verify. Remove or repair it, then Resume to verify again.</p>
					{/if}

					{#if torrent.moveState === 'storage-conflict'}
						<p class="move-warning">⚠️ Storage conflict — target directory has data. Retry or cancel the move.</p>
					{:else if torrent.moveState && torrent.moveState !== 'none' && !isRecoverableMoveState(torrent.moveState)}
						<p class="move-status">🔄 Move status: {torrent.moveState}</p>
					{/if}

					{#if torrent.moveState === 'move-interrupted' || torrent.moveState === 'interrupted'}
						<p class="move-warning">⏸️ Move interrupted — retry or cancel to recover.</p>
					{/if}

					{#if isRecoverableMoveState(torrent.moveState) || catalog.some((path) => path !== torrent.destinationPath)}
						<div class="move-controls">
							<label for={`move-destination-${torrent.id}`}>Move to</label>
							<select
								id={`move-destination-${torrent.id}`}
								value={moveTargetFor(torrent)}
								disabled={isRecoverableMoveState(torrent.moveState)}
								on:change={(event) => setMoveTarget(torrent.id, (event.currentTarget as HTMLSelectElement).value)}
							>
								<option value="">Choose approved folder</option>
								{#each catalog.filter((path) => path !== torrent.destinationPath) as path}
									<option value={path}>{path}</option>
								{/each}
							</select>
							{#if isRecoverableMoveState(torrent.moveState)}
								<button class="btn btn-resume" on:click={() => retryMove(torrent.id, moveTargetFor(torrent))} disabled={moveLoading[torrent.id]}>Retry move</button>
								<button class="btn btn-remove" on:click={() => cancelMove(torrent.id)} disabled={moveLoading[torrent.id]}>Cancel move</button>
							{:else}
								<button class="btn btn-move" on:click={() => moveTorrent(torrent.id, moveTargetFor(torrent))} disabled={moveLoading[torrent.id] || torrent.destinationStatus === 'destination_unavailable'}>Move</button>
							{/if}
						</div>
					{/if}
					{#if moveErrors[torrent.id]}<p class="error">{moveErrors[torrent.id]}</p>{/if}

					<div class="card-actions">
						{#if torrent.state === 'paused' || torrent.state === 'pause_requested'}
							<button class="btn btn-resume" on:click={() => resumeTorrent(torrent.id)} disabled={torrent.destinationStatus === 'destination_unavailable'}>▶ Resume</button>
						{:else}
							<button class="btn btn-pause" on:click={() => pauseTorrent(torrent.id)}>⏸ Pause</button>
						{/if}

						<button class="btn btn-info" on:click={() => toggleInfo(torrent)}>ℹ Info</button>

						<button class="btn btn-remove" on:click={() => removeTorrent(torrent.id, false)}>🗑 Remove</button>

						<button
							class="btn btn-delete"
							on:click={() => removeTorrent(torrent.id, true)}
						>🗑 Remove &amp; Delete Files</button>
					</div>
				</article>
			{/each}
		{/if}
	</section>

	<footer>
		<p>Torrent WebUI — powered by libtorrent via JNI</p>
	</footer>

	{#if showSettings}
		<div class="modal-overlay" on:click={() => { showSettings = false; settingsMessage = ''; settingsError = ''; }}>
			<div class="modal-content settings-modal" on:click={(e) => e.stopPropagation()}>
				<div class="modal-header">
					<h2>Settings</h2>
					<button class="btn-close" on:click={() => { showSettings = false; settingsMessage = ''; settingsError = ''; }}>✕</button>
				</div>
				<div class="modal-body">
					<p class="settings-description">Change the password used to access this WebUI.</p>

					{#if settingsMessage}
						<p class="settings-success">{settingsMessage}</p>
					{/if}

					{#if settingsError}
						<p class="settings-error">{settingsError}</p>
					{/if}

					<div class="form-group">
						<label for="current-password">Current Password</label>
						<input
							id="current-password"
							type="password"
							bind:value={currentPassword}
							placeholder="Enter current password"
						/>
					</div>

					<div class="form-group">
						<label for="new-password">New Password</label>
						<input
							id="new-password"
							type="password"
							bind:value={newPassword}
							placeholder="Min. 4 characters"
						/>
					</div>

					<button class="btn btn-settings-submit" on:click={changePassword}>Change Password</button>
				</div>
			</div>
		</div>
	{/if}
</div>

{#if showInfo && selectedTorrent}
	<div class="modal-overlay" on:click={closeInfo}>
		<div class="modal-content" on:click={(e) => e.stopPropagation()}>
			<div class="modal-header">
				<h2>Torrent Info</h2>
				<button class="btn-close" on:click={closeInfo}>✕</button>
			</div>
			<div class="modal-body">
				<div class="info-row">
					<span class="label">Name:</span>
					<span class="value">{selectedTorrent.name}</span>
				</div>
				<div class="info-row">
					<span class="label">ID:</span>
					<span class="value">{selectedTorrent.id}</span>
				</div>
				<div class="info-row">
					<span class="label">State:</span>
					<span class="value state-badge" style="--color: {stateColor(selectedTorrent.state)}">
						{stateIcon(selectedTorrent.state)} {stateDisplayName(selectedTorrent.state)}
					</span>
				</div>
				<div class="info-row">
					<span class="label">Progress:</span>
					<span class="value">{formatProgress(selectedTorrent.progress)}</span>
				</div>
				<div class="info-row">
					<span class="label">Download Speed:</span>
					<span class="value">{formatBytes(selectedTorrent.downloadRate)}/s</span>
				</div>
				<div class="info-row">
					<span class="label">Upload Speed:</span>
					<span class="value">{formatBytes(selectedTorrent.uploadRate)}/s</span>
				</div>
				<div class="info-row">
					<span class="label">Peers:</span>
					<span class="value">{selectedTorrent.peers}</span>
				</div>
				{#if selectedTorrent.destinationPath || selectedTorrent.savePath}
					<div class="info-row">
						<span class="label">Destination:</span>
						<span class="value path">{selectedTorrent.destinationPath || selectedTorrent.savePath}</span>
					</div>
				{/if}
			</div>
			<div class="modal-footer">
				<button class="btn btn-close-modal" on:click={closeInfo}>Close</button>
			</div>
		</div>
	</div>
{/if}

<style>
.modal-overlay {
	position: fixed;
	top: 0;
	left: 0;
	right: 0;
	bottom: 0;
	background: rgba(0, 0, 0, 0.7);
	display: flex;
	align-items: center;
	justify-content: center;
	z-index: 1000;
}

.modal-content {
	background: var(--surface);
	border: 1px solid var(--border);
	border-radius: 8px;
	width: 90%;
	max-width: 500px;
	max-height: 80vh;
	overflow-y: auto;
}

.modal-header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	padding: 1rem;
	border-bottom: 1px solid var(--border);
}

.modal-header h2 {
	font-size: 1.1rem;
	color: var(--text);
}

.btn-close {
	background: none;
	border: none;
	color: var(--muted);
	font-size: 1.2rem;
	cursor: pointer;
	padding: 0;
	width: 32px;
	height: 32px;
	display: flex;
	align-items: center;
	justify-content: center;
	border-radius: 4px;
}

.btn-close:hover {
	background: var(--border);
	color: var(--text);
}

.modal-body {
	padding: 1rem;
}

.info-row {
	display: flex;
	justify-content: space-between;
	align-items: center;
	padding: 0.5rem 0;
	border-bottom: 1px solid var(--border);
}

.info-row:last-child {
	border-bottom: none;
}

.label {
	font-size: 0.85rem;
	color: var(--muted);
	font-weight: 500;
}

.value {
	font-size: 0.85rem;
	color: var(--text);
	text-align: right;
	word-break: break-word;
}

.value.path {
	font-size: 0.75rem;
	color: var(--muted);
}

.modal-footer {
	padding: 1rem;
	border-top: 1px solid var(--border);
	display: flex;
	justify-content: flex-end;
}

.btn-close-modal {
	background: var(--border);
	color: var(--text);
}

.btn-close-modal:hover {
	background: color-mix(in srgb, var(--border) 80%, white);
}

:root {
	--bg: #1a1a2e;
	--surface: #16213e;
	--border: #0f3460;
	--text: #e8e8e8;
	--muted: #a0a0b0;
	--accent: #e94560;
	--input-bg: #1a1a2e;
}

	* {
		box-sizing: border-box;
		margin: 0;
		padding: 0;
	}

	body {
		font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
		background: var(--bg);
		color: var(--text);
		line-height: 1.5;
	}

	.container {
		max-width: 800px;
		margin: 0 auto;
		padding: 1rem;
	}

	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding-bottom: 1rem;
		border-bottom: 1px solid var(--border);
		margin-bottom: 1.5rem;
	}

	h1 {
		font-size: 1.4rem;
		color: var(--accent);
	}

	h2 {
		font-size: 1.1rem;
		color: var(--muted);
		margin-bottom: 0.75rem;
	}

	.status {
		font-size: 0.85rem;
	}

	.ws-indicator {
		padding: 2px 8px;
		border-radius: 12px;
		background: var(--surface);
	}

	.ws-indicator.connected {
		color: #4CAF50;
	}

	.ws-indicator.connecting {
		color: #FF9800;
	}

	/* Canonical destination selector */

	.destination-picker {
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1rem;
		margin-bottom: 1.5rem;
	}

	.catalog-list {
		border: 0;
		margin-bottom: 0.75rem;
	}

	.catalog-list legend, .folder-browser h3, .paste-path label {
		color: var(--muted);
		font-size: 0.8rem;
		font-weight: 600;
		margin-bottom: 0.5rem;
	}

	.path-option {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 0.6rem;
		padding: 0.65rem;
		border: 1px solid var(--border);
		border-radius: 6px;
		margin-bottom: 0.5rem;
	}

	.path-option label {
		display: flex;
		align-items: flex-start;
		gap: 0.6rem;
		cursor: pointer;
	}

	.forget-destination {
		background: transparent;
		border: 1px solid var(--border);
		color: var(--muted);
		padding: 0.25rem 0.5rem;
		font-size: 0.75rem;
	}

	.path-option code, .path-button code, .browser-current code,
	.validation-result code, .selected-path code {
		word-break: break-all;
		color: var(--text);
	}

	.secondary-button, .path-button {
		background: var(--border);
		color: var(--text);
	}

	.folder-browser {
		border-top: 1px solid var(--border);
		margin-top: 1rem;
		padding-top: 1rem;
	}

	.path-buttons {
		display: grid;
		gap: 0.5rem;
		margin-bottom: 1rem;
	}

	.path-button {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		text-align: left;
	}

	.path-button span {
		font-size: 0.8rem;
		color: var(--muted);
	}

	.browser-current, .validation-result {
		background: var(--input-bg);
		border: 1px solid var(--border);
		border-radius: 6px;
		padding: 0.75rem;
		margin-bottom: 1rem;
	}

	.browser-current code, .validation-result code {
		display: block;
		margin: 0.35rem 0 0.75rem;
	}

	.browser-current button, .validation-success button, .paste-path button {
		background: var(--accent);
		color: white;
	}

	.paste-path {
		margin-top: 1rem;
	}

	.paste-path label {
		display: block;
	}

	.validation-success { border-color: #4CAF50; }
	.validation-error, .storage-guidance { color: var(--accent); }
	.selected-path, .folder-required {
		font-size: 0.85rem;
		color: var(--muted);
		margin-top: 0.75rem;
	}
	.compact { padding: 0.75rem; }

	button:disabled {
		opacity: 0.45;
		cursor: not-allowed;
	}

	/* Add torrent section */

	.add-torrent {
		margin-bottom: 1.5rem;
	}

	.input-row {
		display: flex;
		gap: 0.5rem;
	}

	.input-row input {
		flex: 1;
		padding: 0.6rem 0.8rem;
		border-radius: 6px;
		border: 1px solid var(--border);
		background: var(--input-bg);
		color: var(--text);
		font-size: 0.95rem;
	}

	.input-row input:focus {
		outline: none;
		border-color: var(--accent);
	}

	button {
		padding: 0.6rem 1rem;
		border-radius: 6px;
		border: none;
		cursor: pointer;
		font-size: 0.9rem;
		font-weight: 500;
		transition: opacity 0.15s;
	}

	button:hover {
		opacity: 0.85;
	}

	.add-torrent .input-row button {
		background: var(--accent);
		color: white;
	}

	.error {
		color: var(--accent);
		margin-top: 0.5rem;
		font-size: 0.85rem;
	}

	/* Torrent list */

	.torrent-list {
		margin-bottom: 1.5rem;
	}

	.loading, .empty {
		text-align: center;
		color: var(--muted);
		padding: 2rem;
	}

	.torrent-card {
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1rem;
		margin-bottom: 0.75rem;
	}

	.card-header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin-bottom: 0.75rem;
	}

	.state-badge {
		padding: 2px 8px;
		border-radius: 4px;
		font-size: 0.75rem;
		font-weight: 600;
		background: color-mix(in srgb, var(--color) 20%, transparent);
		color: var(--color);
		text-transform: uppercase;
		flex-shrink: 0;
	}

	.torrent-name {
		font-size: 1rem;
		word-break: break-word;
	}

	.progress-section {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		margin-bottom: 0.5rem;
	}

	progress {
		flex: 1;
		height: 8px;
		border-radius: 4px;
		appearance: none;
		background: var(--border);
	}

	progress::-webkit-progress-bar {
		background: var(--border);
		border-radius: 4px;
	}

	progress::-webkit-progress-value {
		background: var(--accent);
		border-radius: 4px;
	}

	progress::-moz-progress-bar {
		background: var(--accent);
		border-radius: 4px;
	}

	.progress-text {
		font-size: 0.85rem;
		color: var(--muted);
		min-width: 48px;
		text-align: right;
	}

	.torrent-meta {
		display: flex;
		gap: 1rem;
		font-size: 0.85rem;
		color: var(--muted);
		margin-bottom: 0.5rem;
	}

	.save-path {
		font-size: 0.75rem;
		color: var(--muted);
		margin-bottom: 0.75rem;
		word-break: break-all;
	}

	.move-status {
		font-size: 0.8rem;
		color: #2196F3;
		margin-bottom: 0.5rem;
	}

	.move-warning {
		font-size: 0.8rem;
		color: #f44336;
		margin-bottom: 0.5rem;
	}

	.move-controls {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		flex-wrap: wrap;
		margin-bottom: 0.75rem;
		font-size: 0.8rem;
		color: var(--muted);
	}

	.move-controls select {
		flex: 1 1 180px;
		min-width: 0;
		padding: 0.4rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		background: var(--input-bg);
		color: var(--text);
	}

	.card-actions {
		display: flex;
		gap: 0.5rem;
		flex-wrap: wrap;
	}

	.btn {
		padding: 0.4rem 0.75rem;
		font-size: 0.8rem;
		border-radius: 4px;
	}

	.btn-resume { background: #2196F3; color: white; }
	.btn-pause { background: #FF9800; color: white; }
	.btn-info { background: var(--border); color: var(--text); }
	.btn-remove { background: #555; color: white; }
	.btn-delete { background: var(--accent); color: white; }
	.btn-move { background: #6b5bd2; color: white; }

	/* Header actions */

	.header-actions {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.settings-btn {
		background: none;
		border: none;
		font-size: 1.2rem;
		cursor: pointer;
		padding: 4px;
		line-height: 1;
	}

	.settings-btn:hover {
		opacity: 0.7;
	}

	/* Settings modal */

	.settings-modal {
		max-width: 420px;
	}

	.settings-description {
		font-size: 0.85rem;
		color: var(--muted);
		margin-bottom: 1rem;
	}

	.settings-success {
		background: color-mix(in srgb, #4CAF50 15%, transparent);
		color: #4CAF50;
		padding: 0.6rem 0.75rem;
		border-radius: 4px;
		font-size: 0.85rem;
		margin-bottom: 1rem;
	}

	.settings-error {
		background: color-mix(in srgb, var(--accent) 15%, transparent);
		color: var(--accent);
		padding: 0.6rem 0.75rem;
		border-radius: 4px;
		font-size: 0.85rem;
		margin-bottom: 1rem;
	}

	.form-group {
		margin-bottom: 0.75rem;
	}

	.form-group label {
		display: block;
		font-size: 0.85rem;
		color: var(--muted);
		margin-bottom: 0.3rem;
		font-weight: 500;
	}

	.form-group input {
		width: 100%;
		padding: 0.6rem 0.8rem;
		border-radius: 6px;
		border: 1px solid var(--border);
		background: var(--input-bg);
		color: var(--text);
		font-size: 0.95rem;
	}

	.form-group input:focus {
		outline: none;
		border-color: var(--accent);
	}

	.btn-settings-submit {
		width: 100%;
		padding: 0.6rem;
		background: var(--accent);
		color: white;
		font-weight: 600;
		margin-top: 0.5rem;
	}

	footer {
		text-align: center;
		padding-top: 1rem;
		border-top: 1px solid var(--border);
		color: var(--muted);
		font-size: 0.8rem;
	}

	/* Mobile */

	@media (max-width: 480px) {
		.container { padding: 0.5rem; }
		header h1 { font-size: 1.1rem; }
		.input-row { flex-direction: column; }
		.torrent-meta { flex-wrap: wrap; gap: 0.5rem; }
		header { flex-wrap: wrap; gap: 0.5rem; }
	}
</style>
