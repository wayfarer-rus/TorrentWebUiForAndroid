package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OnboardingReadiness {
    @SerialName("Ready")
    Ready,

    @SerialName("Action needed on Android")
    ActionNeededOnAndroid,

    @SerialName("Service unavailable")
    ServiceUnavailable
}

@Serializable
enum class PasswordDecision {
    @SerialName("pending")
    Pending,

    @SerialName("deferred")
    Deferred,

    @SerialName("changed")
    Changed
}

internal data class OnboardingRecord(
    val completed: Boolean,
    val passwordDecision: PasswordDecision
)

@Serializable
data class OnboardingStatus(
    val completed: Boolean,
    val passwordDecision: PasswordDecision,
    val hasApprovedDestination: Boolean,
    val readiness: OnboardingReadiness
)

internal sealed interface PasswordDeferralResult {
    data class Updated(val status: OnboardingStatus) : PasswordDeferralResult
    data object AlreadyCompleted : PasswordDeferralResult
    data object DecisionAlreadyRecorded : PasswordDeferralResult
    data object PersistenceFailed : PasswordDeferralResult
}

internal interface OnboardingStateStore {
    /** Null means this installation has never been initialized for M6. */
    fun read(): OnboardingRecord?

    /** Returns only after the complete record is durable. */
    fun write(record: OnboardingRecord): Boolean
}

internal class SharedPreferencesOnboardingStateStore(context: Context) : OnboardingStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): OnboardingRecord? {
        if (!preferences.contains(KEY_INITIALIZED)) return null
        val decision = when (preferences.getString(KEY_PASSWORD_DECISION, null)) {
            PasswordDecision.Deferred.name -> PasswordDecision.Deferred
            PasswordDecision.Changed.name -> PasswordDecision.Changed
            else -> PasswordDecision.Pending
        }
        return OnboardingRecord(
            completed = preferences.getBoolean(KEY_COMPLETED, false),
            passwordDecision = decision
        )
    }

    override fun write(record: OnboardingRecord): Boolean = preferences.edit()
        .putBoolean(KEY_INITIALIZED, true)
        .putBoolean(KEY_COMPLETED, record.completed)
        .putString(KEY_PASSWORD_DECISION, record.passwordDecision.name)
        .commit()

    companion object {
        internal const val PREFERENCES_NAME = "consumer_onboarding"
        internal const val KEY_INITIALIZED = "initialized"
        internal const val KEY_COMPLETED = "completed"
        internal const val KEY_PASSWORD_DECISION = "password_decision"
    }
}

/** Backend authority for one-time migration and resumable Consumer Onboarding status. */
internal class OnboardingCoordinator(
    private val store: OnboardingStateStore,
    private val hasDurableQueue: suspend () -> Boolean,
    private val hasApprovedDestination: suspend () -> Boolean,
    private val hasNonDefaultPassword: () -> Boolean,
    private val readiness: () -> OnboardingReadiness
) {
    private val lock = Mutex()

    suspend fun status(): OnboardingStatus = lock.withLock {
        val destinationPresent = hasApprovedDestination()
        val currentReadiness = readiness()
        var record = store.read() ?: initialize(destinationPresent)
        if (record.isEligibleForCompletion(destinationPresent, currentReadiness)) {
            val completed = record.copy(completed = true)
            if (store.write(completed)) record = completed
        }
        record.toStatus(destinationPresent, currentReadiness)
    }

    suspend fun deferPassword(): PasswordDeferralResult = lock.withLock {
        val destinationPresent = hasApprovedDestination()
        val currentReadiness = readiness()
        var record = store.read() ?: initialize(destinationPresent)
        if (record.completed) return@withLock PasswordDeferralResult.AlreadyCompleted
        if (record.passwordDecision == PasswordDecision.Changed) {
            return@withLock PasswordDeferralResult.DecisionAlreadyRecorded
        }
        if (record.passwordDecision == PasswordDecision.Pending) {
            val deferred = record.copy(passwordDecision = PasswordDecision.Deferred)
            if (!store.write(deferred)) return@withLock PasswordDeferralResult.PersistenceFailed
            record = deferred
        }
        if (record.isEligibleForCompletion(destinationPresent, currentReadiness)) {
            val completed = record.copy(completed = true)
            if (!store.write(completed)) return@withLock PasswordDeferralResult.PersistenceFailed
            record = completed
        }
        PasswordDeferralResult.Updated(record.toStatus(destinationPresent, currentReadiness))
    }

    private fun OnboardingRecord.isEligibleForCompletion(
        destinationPresent: Boolean,
        currentReadiness: OnboardingReadiness
    ): Boolean = !completed &&
        currentReadiness == OnboardingReadiness.Ready &&
        destinationPresent &&
        passwordDecision != PasswordDecision.Pending

    private fun OnboardingRecord.toStatus(
        destinationPresent: Boolean,
        currentReadiness: OnboardingReadiness
    ) = OnboardingStatus(
        completed = completed,
        passwordDecision = passwordDecision,
        hasApprovedDestination = destinationPresent,
        readiness = currentReadiness
    )

    private suspend fun initialize(destinationPresent: Boolean): OnboardingRecord {
        val established = destinationPresent || hasDurableQueue() || hasNonDefaultPassword()
        val record = if (established) {
            OnboardingRecord(completed = true, passwordDecision = PasswordDecision.Deferred)
        } else {
            OnboardingRecord(completed = false, passwordDecision = PasswordDecision.Pending)
        }
        check(store.write(record)) { "Unable to persist Consumer Onboarding state" }
        return record
    }

    companion object {
        internal fun completedForTest(): OnboardingCoordinator = OnboardingCoordinator(
            store = object : OnboardingStateStore {
                override fun read() = OnboardingRecord(true, PasswordDecision.Deferred)
                override fun write(record: OnboardingRecord) = true
            },
            hasDurableQueue = { false },
            hasApprovedDestination = { true },
            hasNonDefaultPassword = { false },
            readiness = { OnboardingReadiness.Ready }
        )
    }
}

internal fun mapOnboardingReadiness(
    storagePermissionState: StoragePermissionState,
    sessionStarted: Boolean
): OnboardingReadiness = when {
    storagePermissionState == StoragePermissionState.RevokedRuntime ->
        OnboardingReadiness.ActionNeededOnAndroid
    !sessionStarted -> OnboardingReadiness.ServiceUnavailable
    else -> OnboardingReadiness.Ready
}
