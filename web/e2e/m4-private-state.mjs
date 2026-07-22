export function catalogStateOwnedByM4(text) {
	return (text ?? '').split('\n').map((line) => line.trim()).filter(Boolean).every((entry) =>
		entry.startsWith('@latest|')
			? (entry === '@latest|' || entry.includes('TorrentWebUi-M4-'))
			: entry.includes('TorrentWebUi-M4-')
	);
}
