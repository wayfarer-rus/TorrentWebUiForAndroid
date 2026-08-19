/** Authenticated backend snapshot rendered by the Downloads presentation. */
export type TorrentSnapshot = {
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

export type StorageVolume = { path: string; description?: string; isRemovable?: boolean };
export type PathValidation = {
	isValid: boolean;
	canonicalPath?: string | null;
	rejectionReason?: string | null;
};
export type MoveStatus = { phase: string; sourcePath?: string | null; targetPath?: string | null };
export type TorrentCardAction = 'pause' | 'resume' | 'remove-from-list' | 'remove-and-delete-files';
export type OnboardingStatus = {
	completed: boolean;
	passwordDecision: 'pending' | 'deferred' | 'changed';
	hasApprovedDestination: boolean;
	readiness: 'Ready' | 'Action needed on Android' | 'Service unavailable';
};

/** State the backend-free page presentation is allowed to read or edit. */
export interface WebUiPresentationState {
	readonly onboardingStatus: OnboardingStatus | null;
	readonly onboardingLoading: boolean;
	readonly onboardingError: string;
	readonly recommendedPath: string | null;
	readonly recommendedLoading: boolean;
	readonly onboardingActionLoading: boolean;
	readonly onboardingActionError: string;
	showOnboardingPasswordChange: boolean;
	onboardingNewPassword: string;
	onboardingPasswordConfirmation: string;
	magnetUri: string;
	readonly addPending: boolean;
	readonly addStatus: string;
	readonly addError: string;
	readonly wsConnected: boolean;
	readonly wsConnecting: boolean;
	/** Live updates disconnected after a rendered authenticated snapshot; cards remain stale. */
	readonly updatesPaused: boolean;
	readonly lastSnapshot: TorrentSnapshot[];
	readonly loading: boolean;
	readonly storagePermission: string | null;
	readonly volumes: StorageVolume[];
	readonly catalog: string[];
	readonly selectedDestination: string | null;
	readonly storageLoading: boolean;
	readonly storageError: string;
	readonly storageRecoveryMessage: string | null;
	readonly storageMutationPending: boolean;
	showBrowser: boolean;
	readonly browsedPath: string | null;
	readonly children: string[];
	pastedPath: string;
	readonly pathValidation: PathValidation | null;
	readonly validatingPath: boolean;
	readonly storageReady: boolean;
	readonly moveErrors: Record<number, string>;
	readonly moveMessages: Record<number, string>;
	readonly moveLoading: Record<number, boolean>;
	readonly torrentActionPending: Record<number, TorrentCardAction>;
	readonly torrentActionErrors: Record<number, string>;
	showInfo: boolean;
	readonly selectedTorrent: TorrentSnapshot | null;
	showSettings: boolean;
	currentPassword: string;
	newPassword: string;
	readonly settingsSaving: boolean;
	settingsMessage: string;
	settingsError: string;
}

/** User-intent operations the page presentation can invoke. */
export interface WebUiPresentationActions {
	confirmRecommendedDestination(): Promise<void>;
	chooseAnotherOnboardingPassword(): void;
	cancelOnboardingPasswordChange(): void;
	changeOnboardingPassword(): Promise<void>;
	deferOnboardingPassword(): Promise<void>;
	toggleDirectoryBrowser(): Promise<void>;
	openDirectoryBrowser(): Promise<void>;
	closeDirectoryBrowser(): void;
	browseDirectory(path: string): Promise<void>;
	validateBrowsedPath(): void;
	validatePath(path: string): Promise<void>;
	approveDestination(path: string, surface?: 'normal' | 'onboarding'): Promise<boolean>;
	selectApprovedDestination(path: string): Promise<boolean>;
	removeApprovedDestination(path: string): Promise<boolean>;
	addMagnet(): Promise<void>;
	clearAddFeedback(): void;
	pauseTorrent(id: number): Promise<boolean>;
	resumeTorrent(id: number): Promise<boolean>;
	removeTorrent(id: number, deleteFiles: boolean): Promise<boolean>;
	setMoveTarget(id: number, destinationPath: string): void;
	moveTargetFor(torrent: TorrentSnapshot): string;
	recoverableMoveTargetFor(torrent: TorrentSnapshot): string;
	isRecoverableMoveState(moveState?: string | null): boolean;
	moveTorrent(id: number, destinationPath: string): Promise<void>;
	retryMove(id: number, destinationPath: string): Promise<void>;
	cancelMove(id: number): Promise<void>;
	changePassword(): Promise<void>;
	toggleInfo(torrent: TorrentSnapshot): Promise<void>;
	closeInfo(): void;
	formatBytes(bytes: number): string;
}

export interface WebUiCoordinator extends WebUiPresentationState, WebUiPresentationActions {
	start(): void;
	dispose(): void;
}
