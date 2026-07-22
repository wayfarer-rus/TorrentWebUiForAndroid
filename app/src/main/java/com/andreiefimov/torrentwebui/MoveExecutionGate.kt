package com.andreiefimov.torrentwebui

import kotlinx.coroutines.delay
import java.io.File

/** Injectable boundary used only to make debug-fixture interruption timing deterministic. */
fun interface MoveExecutionGate {
    suspend fun awaitBeforeNativeMove(sourcePath: String, targetPath: String)

    companion object {
        val None = MoveExecutionGate { _, _ -> }
    }
}

/**
 * Debug-build fixture gate. Production wiring never uses this implementation.
 *
 * A repository-owned test creates [HOLD_FILE] in the source directory. The gate writes
 * [REACHED_FILE] after durable journaling and pause, then waits for [RELEASE_FILE] before
 * native move_storage starts. The bounded wait is failure-safe and always removes markers.
 */
class DebugFileMoveExecutionGate : MoveExecutionGate {
    override suspend fun awaitBeforeNativeMove(sourcePath: String, targetPath: String) {
        val source = File(sourcePath)
        val hold = source.resolve(HOLD_FILE)
        if (!hold.isFile) return

        val reached = source.resolve(REACHED_FILE)
        val release = source.resolve(RELEASE_FILE)
        check(reached.writeText(targetPath).let { reached.isFile }) { "Unable to signal move fixture gate" }
        try {
            repeat(MAX_WAIT_POLLS) {
                if (release.isFile) return
                delay(POLL_INTERVAL_MS)
            }
            error("Timed out waiting for move fixture release")
        } finally {
            hold.delete()
            reached.delete()
            release.delete()
        }
    }

    companion object {
        const val HOLD_FILE = ".torrentwebui-m4-move-hold"
        const val REACHED_FILE = ".torrentwebui-m4-move-reached"
        const val RELEASE_FILE = ".torrentwebui-m4-move-release"
        private const val POLL_INTERVAL_MS = 50L
        private const val MAX_WAIT_POLLS = 600
    }
}
