export const CATALOG_STATE_PATHS = [
	'files/destination_catalog.txt',
	'files/destination_catalog.txt.bak',
	'files/destination_catalog.txt.new'
];

export function catalogStateOwnedByM4(text) {
	return (text ?? '').split('\n').map((line) => line.trim()).filter(Boolean).every((entry) =>
		entry.startsWith('@latest|')
			? (entry === '@latest|' || entry.includes('TorrentWebUi-M4-'))
			: entry.includes('TorrentWebUi-M4-')
	);
}

export function catalogStatesOwnedByM4(states) {
	return states.length === CATALOG_STATE_PATHS.length && states.every(catalogStateOwnedByM4);
}
