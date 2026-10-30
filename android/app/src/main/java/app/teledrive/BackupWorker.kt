package app.teledrive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class Progress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val message: String = "",
)

/** In-process status shared between the worker and the UI. */
object BackupStatus {
    val state = MutableStateFlow(Progress())
    @Volatile var stopRequested = false
}

object Notifications {
    private const val CHANNEL = "backup"
    const val ID = 4201

    fun foreground(ctx: Context, text: String, done: Int, total: Int): ForegroundInfo {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup progress", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Backing up to Telegram")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(total, done, total == 0)
            .build()
        return ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

class BackupWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private val lock = Mutex()
        private const val SYNC_EVERY = 25
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = Notifications.foreground(ctx, "Preparing…", 0, 0)

    override suspend fun doWork(): Result {
        // Never run two backups at the same time (periodic + "Back up now").
        if (!lock.tryLock()) return Result.success()
        try {
            BackupStatus.stopRequested = false
            return run()
        } finally {
            BackupStatus.state.value = Progress()
            withContext(NonCancellable) {
                if (isStopped || BackupStatus.stopRequested) runCatching { JsEngine.reset() } else runCatching { JsEngine.disconnect() }
            }
            lock.unlock()
        }
    }

    private suspend fun run(): Result {
        val prefs = Prefs(ctx)
        if (!prefs.configured) return Result.failure()
        JsEngine.attach(ctx)
        JsEngine.configure(prefs.apiId, prefs.apiHash, prefs.session, prefs.storage)
        val store = Store(ctx)

        fun publish(done: Int, total: Int, current: String, msg: String = "") {
            BackupStatus.state.value = Progress(true, done, total, current, msg)
        }
        publish(0, 0, "", "Connecting to Telegram…")
        runCatching { setForeground(Notifications.foreground(ctx, "Connecting…", 0, 0)) }

        // 1) push records from a previous interrupted run (also verifies the login still works)
        try {
            flush(store)
        } catch (e: Exception) {
            prefs.lastRunMs = System.currentTimeMillis()
            if (e.message == "SIGNED_OUT") {
                prefs.session = ""      // forces the sign-in screen next time the app opens
                prefs.lastStatus = "Telegram session ended – open the app and sign in again"
                Scheduler.cancelAll(ctx)
                return Result.failure()
            }
            prefs.lastStatus = "Telegram unreachable: ${e.message}"
            return Result.retry()
        }

        // 2) find new files
        val uploaded = store.loadUploaded()
        val items = try {
            Scanner.collect(ctx, prefs).filter { it.key !in uploaded }
        } catch (e: SecurityException) {
            prefs.lastRunMs = System.currentTimeMillis(); prefs.lastStatus = "Storage permission missing"
            return Result.failure()
        }
        if (items.isEmpty()) {
            prefs.lastRunMs = System.currentTimeMillis(); prefs.lastStatus = "Everything is already backed up"
            return Result.success()
        }

        // 3) upload
        var done = 0
        var consecutiveFailures = 0
        var lastError = ""
        var lastNotify = 0L
        var fresh = 0
        for ((i, item) in items.withIndex()) {
            if (isStopped || BackupStatus.stopRequested) break
            publish(i, items.size, item.name)
            try {
                val folder = ("/Phone/${prefs.deviceName}/${item.folder}").replace(Regex("/+"), "/").trimEnd('/')
                val rec = JsEngine.upload(item, folder) { sent ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1500) {
                        lastNotify = now
                        val pct = if (item.size > 0) " ${(sent * 100 / item.size)}%" else ""
                        BackupStatus.state.value = Progress(true, i, items.size, item.name + pct)
                    }
                }
                store.addPending(rec)          // write the record first, then mark done: a crash can only cause a re-upload, never a lost file
                store.markUploaded(item.key)
                done++; fresh++; consecutiveFailures = 0
                runCatching { setForeground(Notifications.foreground(ctx, "${i + 1}/${items.size} · ${item.name}", i + 1, items.size)) }
                if (fresh >= SYNC_EVERY) { runCatching { flush(store) }; fresh = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isStopped || BackupStatus.stopRequested) break
                lastError = e.message ?: e.javaClass.simpleName
                consecutiveFailures++
                if (consecutiveFailures >= 3) break
            }
        }

        // 4) final index sync (records stay in pending.jsonl if this fails)
        var syncError = ""
        try { flush(store) } catch (e: Exception) { syncError = e.message ?: "index sync failed" }

        prefs.lastRunMs = System.currentTimeMillis()
        prefs.lastStatus = when {
            consecutiveFailures >= 3 -> "Stopped after errors: $lastError (uploaded $done)"
            isStopped || BackupStatus.stopRequested -> "Stopped (uploaded $done of ${items.size})"
            syncError.isNotEmpty() -> "Uploaded $done, will sync index later: $syncError"
            else -> "Uploaded $done file" + if (done == 1) "" else "s"
        }
        return if (consecutiveFailures >= 3 && done == 0) Result.retry() else Result.success()
    }

    private suspend fun flush(store: Store) {
        val recs = store.readPending()
        if (recs.isEmpty()) { JsEngine.ready(); return }
        JsEngine.syncIndex(recs)
        store.clearPending()
    }
}

object Scheduler {
    private const val PERIODIC = "teledrive_periodic"
    private const val NOW = "teledrive_now"

    private fun net(p: Prefs) = if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    /** (Re)applies the schedule from the user's settings. Safe to call whenever a setting changes. */
    fun apply(ctx: Context, p: Prefs) {
        val wm = WorkManager.getInstance(ctx)
        if (p.enabled && p.configured) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(p.intervalMin.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net(p)).setRequiresCharging(p.charging).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(PERIODIC)
        }
    }

    fun runNow(ctx: Context, p: Prefs) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net(p)).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(NOW)
    }
}
