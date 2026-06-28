# Skill: Android Background Service

Operational guide for foreground service and background execution.

## Foreground Service Requirements

- The future torrent engine will need foreground service behavior.
- Foreground services require a persistent notification.
- Android 8.0+ requires `FOREGROUND_SERVICE` permission.
- Android 14+ requires declaring foreground service types.

## Notification

- The foreground service notification must show active torrent state.
- Notification should include: active torrent count, total download rate.
- Notification action buttons for pause/resume are optional (later milestone).

## Lifecycle Implications

- The service must survive activity destruction.
- Bind the service to the app's lifecycle for clean shutdown.
- Handle `onTaskRemoved` for background cleanup.

## Background Runtime

- Do not assume unlimited background runtime.
- Android may still kill foreground services under extreme memory pressure.
- Queue persistence is required for crash recovery.

## Implementation Timing

- Defer actual implementation until Milestone 3 (Persistent Daemon).
- Do not add foreground service code during proof-of-concept stages.

## Future Test Cases

| Scenario | Expected Behavior |
|----------|-------------------|
| Process death | Queue restored on relaunch; torrents resume |
| Device reboot | Boot receiver restarts service; queue restored |
| Low memory | Foreground service retained; if killed, state persisted |
| Doze mode | Torrent I/O may be delayed; resume when awake |
