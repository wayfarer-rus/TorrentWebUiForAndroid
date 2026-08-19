import type { TorrentSnapshot } from './contracts';

export type ConsumerDownloadState =
	| 'Preparing'
	| 'Downloading'
	| 'Pausing'
	| 'Paused'
	| 'Moving files'
	| 'Complete'
	| 'Needs attention';

export type ConsumerDownloadSection = 'needs-attention' | 'active' | 'completed';
export type ConsumerDownloadAction = 'pause' | 'resume';
export type ConsumerRecoveryKind =
	| 'folder-unavailable'
	| 'folder-conflict'
	| 'move-interrupted'
	| 'move-conflict'
	| 'native-error'
	| 'unknown-state';

export type ConsumerRecovery = {
	kind: ConsumerRecoveryKind;
	message: string;
	primaryAction: 'fix-problem' | 'retry-move' | 'cancel-move' | null;
};

export type PresentedDownload = {
	download: TorrentSnapshot;
	state: ConsumerDownloadState;
	section: ConsumerDownloadSection;
};

const PREPARING_STATES = new Set([
	'queued_for_checking',
	'checking_files',
	'downloading_metadata',
	'metadata',
	'allocating',
	'checking_resume_data',
	'restore_check',
	'restore-check'
]);
const COMPLETE_STATES = new Set(['finished', 'seeding', 'completed']);
const ATTENTION_STATES = new Set(['error', 'native_error', 'native-error']);
const ACTIVE_MOVES = new Set([
	'journal-persisted',
	'copying',
	'verifying',
	'queue-updated',
	'source-removed'
]);
const RECOGNIZED_STATES = new Set([
	...PREPARING_STATES,
	...COMPLETE_STATES,
	...ATTENTION_STATES,
	'downloading',
	'pause_requested',
	'paused',
	'move_storage',
	'move-storage'
]);

function normalized(value?: string | null): string {
	return value?.trim().toLowerCase() ?? '';
}

/**
 * Converts backend exceptional signals into the only consumer-facing recovery copy.
 * Backend messages are deliberately not part of this contract.
 */
export function recoveryForDownload(download: TorrentSnapshot): ConsumerRecovery | null {
	const state = normalized(download.state);
	const moveState = normalized(download.moveState);
	const destinationStatus = normalized(download.destinationStatus);

	if (moveState === 'storage-conflict' || moveState === 'storage_conflict') {
		return {
			kind: 'move-conflict',
			message: 'Files already exist at the target. Nothing was overwritten. Resolve the conflict before retrying the move.',
			primaryAction: 'retry-move'
		};
	}
	if (moveState === 'move-interrupted' || moveState === 'move_interrupted' || moveState === 'interrupted') {
		return {
			kind: 'move-interrupted',
			message: 'The move stopped before completion. The Download Folder has not changed and source files were not removed.',
			primaryAction: 'retry-move'
		};
	}
	if (destinationStatus === 'destination_unavailable') {
		return {
			kind: 'folder-unavailable',
			message: 'This Download Folder is unavailable. Reconnect storage or restore storage access in the Android app, then choose the folder in Settings.',
			primaryAction: 'fix-problem'
		};
	}
	if (destinationStatus === 'storage_conflict' || destinationStatus === 'storage-conflict') {
		return {
			kind: 'folder-conflict',
			message: 'This Download Folder needs attention. In Settings, choose a different approved Download Folder or restore storage access in the Android app.',
			primaryAction: 'fix-problem'
		};
	}
	if (ATTENTION_STATES.has(state)) {
		return {
			kind: 'native-error',
			message: 'This Download needs attention. Check Downloads in the Android app, then return here.',
			primaryAction: null
		};
	}
	if (!RECOGNIZED_STATES.has(state) && !ACTIVE_MOVES.has(moveState)) {
		return {
			kind: 'unknown-state',
			message: 'This Download needs attention. Check Downloads in the Android app, then return here.',
			primaryAction: null
		};
	}
	return null;
}

/**
 * Maps the authenticated backend snapshot into the household queue vocabulary.
 * The returned section deliberately never derives order from a runtime ID.
 */
export function presentDownload(download: TorrentSnapshot): PresentedDownload {
	const state = normalized(download.state);
	const moveState = normalized(download.moveState);

	const recovery = recoveryForDownload(download);
	// A missing durable folder needs recovery guidance, but cannot turn a fully
	// downloaded item into an Active Download. Keep it in Completed.
	if (COMPLETE_STATES.has(state) && recovery?.kind === 'folder-unavailable') {
		return { download, state: 'Complete', section: 'completed' };
	}
	if (recovery) {
		return { download, state: 'Needs attention', section: 'needs-attention' };
	}
	if (state === 'move_storage' || state === 'move-storage' || ACTIVE_MOVES.has(moveState)) {
		return { download, state: 'Moving files', section: 'active' };
	}
	if (COMPLETE_STATES.has(state)) {
		return { download, state: 'Complete', section: 'completed' };
	}
	if (PREPARING_STATES.has(state)) {
		return { download, state: 'Preparing', section: 'active' };
	}
	if (state === 'downloading') {
		return { download, state: 'Downloading', section: 'active' };
	}
	if (state === 'pause_requested') {
		return { download, state: 'Pausing', section: 'active' };
	}
	if (state === 'paused') {
		return { download, state: 'Paused', section: 'active' };
	}

	return { download, state: 'Needs attention', section: 'needs-attention' };
}

/** Preserves backend order while partitioning Downloads into their queue sections. */
export function presentDownloads(downloads: TorrentSnapshot[]): PresentedDownload[] {
	return downloads.map(presentDownload);
}

export function consumerDownloadName(download: TorrentSnapshot): string {
	return download.name.trim() || 'Unnamed Download';
}

/** Keeps native progress semantics valid when a malformed backend value is received. */
export function normalizedProgress(progress: number): number {
	return Number.isFinite(progress) ? Math.min(1, Math.max(0, progress)) : 0;
}

export function formatConsumerProgress(progress: number): string {
	return `${(normalizedProgress(progress) * 100).toFixed(1)}% complete`;
}


/** Returns the sole safe primary action for a card, based only on its backend snapshot. */
export function primaryConsumerAction(item: PresentedDownload): ConsumerDownloadAction | null {
	if (item.section !== 'active' || item.state === 'Moving files') return null;
	const state = normalized(item.download.state);
	if (state === 'downloading') return 'pause';
	if (state === 'paused') return 'resume';
	return null;
}
