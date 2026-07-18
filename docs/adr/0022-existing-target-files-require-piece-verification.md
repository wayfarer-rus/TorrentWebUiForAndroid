# Existing Target Files Require Piece Verification

Adding or moving a torrent never overwrites data already present at the target path. The torrent may reuse matching data only through normal libtorrent piece verification; an unverified or conflicting target leaves the torrent paused with a recoverable error.
