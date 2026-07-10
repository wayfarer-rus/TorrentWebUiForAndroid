<script>
	import { onMount, onDestroy } from 'svelte';

	// --- State (Svelte 5 runes) ---
	let magnetUri = $state('');
	let addError = $state('');
	let wsConnected = $state(false);
	let wsConnecting = $state(false);
	let lastSnapshot = $state([]);
	let loading = $state(true);

	// WebSocket reference (used only for cleanup)
	let ws = $state(null);

	// --- API helpers ---

	async function addMagnet() {
		const uri = magnetUri.trim();
		if (!uri) {
			addError = 'Please enter a magnet URI';
			return;
		}

		try {
			const res = await fetch('/api/torrents/magnet', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ magnet: uri }),
			});

			if (!res.ok) {
				const err = await res.json().catch(() => ({ error: 'Unknown error' }));
				addError = err.error || `HTTP ${res.status}`;
				return;
			}

			magnetUri = '';
			addError = '';
		} catch (e) {
			addError = e instanceof Error ? e.message : 'Network error';
		}
	}

	async function pauseTorrent(id) {
		await fetch(`/api/torrents/${id}/pause`, { method: 'PUT' });
	}

	async function resumeTorrent(id) {
		await fetch(`/api/torrents/${id}/resume`, { method: 'PUT' });
	}

	async function removeTorrent(id, deleteFiles) {
		if (!deleteFiles && !confirm('Remove this torrent without deleting files?')) return;
		await fetch(`/api/torrents/${id}?deleteFiles=${deleteFiles}`, { method: 'DELETE' });
	}

	function openInManager(id) {
		window.open(`/api/torrents/${id}/info`, '_blank');
	}

	// --- WebSocket connection ---

	function connectWebSocket() {
		wsConnecting = true;

		const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
		ws = new WebSocket(`${protocol}://${location.host}/ws/progress`);

		ws.onopen = () => {
			wsConnecting = false;
			wsConnected = true;
		};

		ws.onclose = () => {
			wsConnected = false;
			wsConnecting = false;

			// Reconnect after a delay (exponential backoff up to 15s)
			setTimeout(() => {
				if (!ws || ws.readyState === WebSocket.CLOSED) {
					connectWebSocket();
				}
			}, 1500);
		};

		ws.onerror = () => {
			wsConnecting = false;
		};

		ws.onmessage = (event) => {
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

	function handleAlert(alert) {
		switch (alert.type) {
			case 'torrent_finished':
				console.log(`Torrent finished: ${alert.name}`);
				break;
			case 'torrent_error':
				console.error(`Torrent error: ${alert.message}`);
				break;
			case 'state_changed':
				console.log(`State changed: ${alert.name} → ${alert.state}`);
				break;
		}
	}

	function sendPing() {
		if (ws && ws.readyState === WebSocket.OPEN) {
			ws.send('ping');
		}
	}

	function formatBytes(bytes) {
		if (bytes === 0) return '—';
		const units = ['B', 'KB', 'MB', 'GB'];
		let i = 0;
		while (bytes >= 1024 && i < units.length - 1) {
			bytes /= 1024;
			i++;
		}
		return `${bytes.toFixed(1)} ${units[i]}`;
	}

	function formatProgress(progress) {
		return `${(progress * 100).toFixed(1)}%`;
	}

	function stateColor(state) {
		switch (state) {
			case 'downloading': return '#2196F3';
			case 'seeding': case 'finished': return '#4CAF50';
			case 'paused': return '#FF9800';
			case 'error': case 'checking_files': return '#f44336';
			default: return '#9E9E9E';
		}
	}

	function stateIcon(state) {
		switch (state) {
			case 'downloading': return '⬇️';
			case 'seeding': case 'finished': return '⬆️';
			case 'paused': return '⏸️';
			case 'checking_files': case 'checking_resume_data': return '🔍';
			default: return '❓';
		}
	}

	onMount(() => {
		connectWebSocket();
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
		<div class="status">
			<span class="ws-indicator" class:connected={wsConnected} class:connecting={wsConnecting}>
				{wsConnecting ? '⏳ Connecting...' : wsConnected ? '🟢 Connected' : '🔴 Disconnected'}
			</span>
		</div>
	</header>

	<section class="add-torrent">
		<h2>Add Torrent</h2>
		<div class="input-row">
			<input
				type="text"
				bind:value={magnetUri}
				placeholder="Paste magnet URI or torrent link..."
				on:keydown={(e) => e.key === 'Enter' && addMagnet()}
			/>
			<button on:click={addMagnet}>Add</button>
		</div>
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
							{stateIcon(torrent.state)} {torrent.state}
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

					{#if torrent.savePath}
						<p class="save-path">📁 {torrent.savePath}</p>
					{/if}

					<div class="card-actions">
						{#if torrent.state === 'paused'}
							<button class="btn btn-resume" on:click={() => resumeTorrent(torrent.id)}>▶ Resume</button>
						{:else}
							<button class="btn btn-pause" on:click={() => pauseTorrent(torrent.id)}>⏸ Pause</button>
						{/if}

						<button class="btn btn-info" on:click={() => openInManager(torrent.id)}>ℹ Info</button>

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
</div>

<style>
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
	}
</style>
