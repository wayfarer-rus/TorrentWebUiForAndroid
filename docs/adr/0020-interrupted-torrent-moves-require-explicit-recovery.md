# Interrupted Torrent Moves Require Explicit Recovery

Every cross-volume Torrent Data Move records durable per-torrent progress. After process termination, reboot, or storage loss, startup inspects the source and target, preserves both copies, and leaves the torrent paused in a recoverable Move Interrupted state; only an explicit retry or cancel can continue or resolve the move.
