package com.ledgernoir.tracker.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgernoir.tracker.LedgerNoirApp
import java.util.concurrent.TimeUnit

/**
 * Periodically fetches the server's notification feed and posts any item the
 * device hasn't shown yet (deduped by stable id via [com.ledgernoir.tracker.data.TokenStore]).
 * Stateless server + client-side dedupe = each event surfaces exactly once.
 */
class NotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LedgerNoirApp ?: return Result.success()
        if (!NotificationHelper.hasPermission(applicationContext)) return Result.success()

        val feed = app.useCases.getNotifications().getOrNull()?.notifications ?: return Result.retry()
        val alreadyShown = app.tokenStore.shownNotificationIds()
        val fresh = feed.filter { it.id !in alreadyShown }
        if (fresh.isEmpty()) return Result.success()

        fresh.forEach { NotificationHelper.post(applicationContext, it) }
        app.tokenStore.addShownNotificationIds(fresh.map { it.id }.toSet())
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "ledger_notifications_periodic"

        /** Schedule a periodic check (~every 6 hours). Idempotent — KEEP avoids
         *  rescheduling on every app launch. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        /** Run a one-off check immediately (e.g. on app open / after sign-in) so
         *  the user doesn't wait for the periodic cycle. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotificationWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${UNIQUE_WORK}_once", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
