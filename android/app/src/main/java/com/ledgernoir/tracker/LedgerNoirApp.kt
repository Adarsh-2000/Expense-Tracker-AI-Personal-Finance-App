package com.ledgernoir.tracker

import android.app.Application
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.domain.UseCases
import com.ledgernoir.tracker.notifications.NotificationHelper
import com.ledgernoir.tracker.notifications.NotificationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Observable phases of the automatic SMS sync. */
sealed class SyncPhase {
    data object Idle : SyncPhase()
    data object ReadingInbox : SyncPhase()
    data class Importing(val done: Int, val total: Int) : SyncPhase()
    data object SyncingEmail : SyncPhase()
    data class Done(val scanned: Int, val bank: Int, val sent: Int, val emailIngested: Int = 0) : SyncPhase()
    data class Failed(val message: String) : SyncPhase()
}

class LedgerNoirApp : Application() {

    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val repository: ExpenseRepository by lazy { ExpenseRepository(tokenStore, applicationContext) }
    val useCases: UseCases by lazy { UseCases(repository) }

    val syncPhase = MutableStateFlow<SyncPhase>(SyncPhase.Idle)
    @Volatile var syncStarted = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        NotificationWorker.schedule(this)
    }

    /** Kick a one-off notification check (called on app open once signed in). */
    fun checkNotificationsNow() {
        NotificationWorker.runNow(this)
        registerPushTokenNow()
    }

    /**
     * Fetch this device's current FCM token and register it with the backend so it
     * can target this device with push notifications. onNewToken covers rotations;
     * this covers normal launches where the token already exists. Best-effort — a
     * failure (offline / not yet authed) just retries on the next app open.
     */
    fun registerPushTokenNow() {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                appScope.launch { repository.registerPushToken(token) }
            }
    }

    /** Runs the SMS import exactly once per process, publishing progress to [syncPhase]. */
    fun startSmsSyncOnce() {
        if (syncStarted) return
        syncStarted = true
        runSync(email = true, sms = true)
    }

    @Volatile var manualSyncRunning = false

    /** True while any sync (auto or manual) is in progress. */
    fun isSyncing(): Boolean = syncPhase.value.let {
        it is SyncPhase.ReadingInbox || it is SyncPhase.Importing || it is SyncPhase.SyncingEmail
    }

    /** Manually re-pull new SMS (incremental from last import). */
    fun syncSmsNow() = runSync(email = false, sms = true)

    /** Manually re-pull new emails from Gmail. */
    fun syncEmailNow() = runSync(email = false, sms = false, emailOnly = true)

    /** Manually re-pull both sources. */
    fun syncAllNow() = runSync(email = true, sms = true)

    private fun runSync(email: Boolean, sms: Boolean, emailOnly: Boolean = false) {
        if (manualSyncRunning || isSyncing()) return
        manualSyncRunning = true
        appScope.launch {
            try {
                var scanned = 0; var bank = 0; var sent = 0; var emailIngested = 0
                if (sms) {
                    syncPhase.value = SyncPhase.ReadingInbox
                    val result = repository.importSmsHistory { done, total ->
                        syncPhase.value = SyncPhase.Importing(done, total)
                    }
                    scanned = result.scanned; bank = result.bank; sent = result.sent
                }
                if (email || emailOnly) {
                    // Auto-sync email (silently/gracefully; unauthorized just yields 0).
                    syncPhase.value = SyncPhase.SyncingEmail
                    emailIngested = repository.syncGmail().getOrNull()?.ingested ?: 0
                }
                syncPhase.value = SyncPhase.Done(scanned, bank, sent, emailIngested)
            } catch (e: Exception) {
                syncPhase.value = SyncPhase.Failed(e.message ?: "Sync failed")
            } finally {
                manualSyncRunning = false
            }
        }
    }
}
