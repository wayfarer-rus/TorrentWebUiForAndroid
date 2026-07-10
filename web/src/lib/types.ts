/** Response item for GET /api/torrents and WebSocket snapshot */
export interface TorrentListItem {
	id: number;
	name: string;
	state: string;
	progress: number; // 0.0 to 1.0
	downloadRate: number; // bytes/s
	uploadRate: number; // bytes/s
	peers: number;
	savePath: string;
}

/** Alert types from WebSocket */
export interface TorrentAlert {
	type: 'torrent_finished' | 'torrent_error' | 'state_changed';
	name?: string;
	message?: string;
	state?: string;
	info_hash?: string;
}

/** WebSocket message envelope */
export interface WsMessage {
	type: 'torrents' | 'alert';
	data: TorrentListItem[] | TorrentAlert;
}
