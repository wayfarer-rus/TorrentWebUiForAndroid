package com.andreiefimov.torrentwebui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentVerificationCoordinatorTest {

    @Test
    fun `timed out verification blocks reuse until its alert is drained`() = runTest {
        val coordinator = TorrentVerificationCoordinator()
        val timedOutWaiter = CompletableDeferred<Boolean>()
        val laterWaiter = CompletableDeferred<Boolean>()

        assertTrue(coordinator.begin(7L, timedOutWaiter))
        coordinator.detachWaiter(7L, timedOutWaiter)
        assertFalse(coordinator.begin(7L, laterWaiter))

        assertTrue(coordinator.complete(7L))
        assertTrue(coordinator.begin(7L, laterWaiter))
        assertTrue(coordinator.complete(7L))
        assertTrue(laterWaiter.await())
    }

    @Test
    fun `abandoned waiter fails without making its stale alert reusable`() = runTest {
        val coordinator = TorrentVerificationCoordinator()
        val abandonedWaiter = CompletableDeferred<Boolean>()

        assertTrue(coordinator.begin(11L, abandonedWaiter))
        coordinator.abandonWaiter(11L)
        assertFalse(abandonedWaiter.await())
        assertFalse(coordinator.begin(11L))
        assertTrue(coordinator.complete(11L))
        assertTrue(coordinator.begin(11L))
    }

    @Test
    fun `shutdown fails waiters and clears pending verification identities`() = runTest {
        val coordinator = TorrentVerificationCoordinator()
        val waiter = CompletableDeferred<Boolean>()

        assertTrue(coordinator.begin(13L, waiter))
        coordinator.shutdown()

        assertFalse(waiter.await())
        assertTrue(coordinator.begin(13L))
    }

    @Test
    fun `native destroy failure still runs waiter cleanup and reports failure`() = runTest {
        val coordinator = TorrentVerificationCoordinator()
        val waiter = CompletableDeferred<Boolean>()
        var recordedFailure: Exception? = null
        assertTrue(coordinator.begin(17L, waiter))

        val succeeded = runNativeDestroyWithCleanup(
            nativeDestroy = { requireNativeDestroySucceeded(false) },
            cleanup = { coordinator.shutdown() },
            onFailure = { recordedFailure = it }
        )

        assertFalse(succeeded)
        assertTrue(recordedFailure is IllegalStateException)
        assertFalse(waiter.await())
        assertTrue(coordinator.begin(17L))
    }
}
