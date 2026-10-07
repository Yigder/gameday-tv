package com.gameday.tv.dvr

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gameday.tv.R
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.Recording
import com.gameday.tv.data.ScoresRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Records live channels to the TV's storage while the app is in the background. Started by
 * [RecordScheduler] when a recording is due, and stops itself when nothing is left to record.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val scores = ScoresRepository()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RecordingStore.init(this)
        createChannel(this)
        goForeground("Getting ready to record…")
    }

    /** Every startForegroundService() needs a matching startForeground(), even when already running. */
    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(currentText())
        when (intent?.action) {
            ACTION_STOP -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                jobs.remove(id)?.cancel()
                RecordingStore.update(id) { it.copy(status = RecStatus.DONE, endMillis = minOf(it.endMillis, System.currentTimeMillis())) }
            }
        }
        startDue()
        stopIfIdle()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startDue() {
        val now = System.currentTimeMillis()
        for (r in RecordingStore.all.value) {
            val due = r.startMillis - LEAD_MS <= now && r.endMillis > now
            val resumable = r.status == RecStatus.RECORDING && !jobs.containsKey(r.id) // process was restarted
            if ((r.status == RecStatus.SCHEDULED && due) || (resumable && r.endMillis > now)) start(r)
            else if (r.status == RecStatus.SCHEDULED && r.endMillis <= now) {
                RecordingStore.update(r.id) { it.copy(status = RecStatus.FAILED, error = "The TV was off or asleep when this aired.") }
            } else if (resumable) {
                RecordingStore.update(r.id) { it.copy(status = RecStatus.DONE) }
            }
        }
        RecordScheduler.reschedule(this)
    }

    private fun start(rec: Recording) {
        val file = File(RecordingStore.directory, "${rec.id}.ts")
        RecordingStore.update(rec.id) { it.copy(status = RecStatus.RECORDING, file = file.path, error = null) }
        updateNotification()
        jobs[rec.id] = scope.launch {
            var end = rec.endMillis
            var extensions = 0
            var failures = 0
            var lastError: String? = null
            var stopForSpace = false
            FileOutputStream(file, true).use { out ->
                while (isActive && !stopForSpace) {
                    val now = System.currentTimeMillis()
                    if (now >= end) {
                        // Live sports run long: keep recording while the game is still on.
                        if (rec.eventId != null && extensions < MAX_EXTENSIONS && gameStillLive(rec.eventId)) {
                            extensions++
                            end += EXTENSION_MS
                            RecordingStore.update(rec.id) { it.copy(endMillis = end) }
                            continue
                        }
                        break
                    }
                    val url = rec.urls.getOrNull(failures % rec.urls.size.coerceAtLeast(1)) ?: break
                    try {
                        var lastSave = 0L
                        val began = System.currentTimeMillis()
                        val finished = StreamRecorder.record(url, out, { end }) { _ ->
                            val t = System.currentTimeMillis()
                            if (t - lastSave < 10_000L) return@record true
                            lastSave = t
                            failures = 0
                            RecordingStore.update(rec.id) { it.copy(bytes = file.length()) }
                            val enough = RecordingStore.freeBytes() > MIN_FREE_BYTES
                            if (!enough) stopForSpace = true
                            enough
                        }
                        if (finished) break
                        // The stream dropped early: don't spin; reconnect shortly.
                        if (!stopForSpace && System.currentTimeMillis() < end && System.currentTimeMillis() - began < 30_000L) delay(5_000)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures++
                        lastError = e.message
                        delay((2_000L * failures).coerceAtMost(15_000L))
                    }
                }
            }
            val bytes = file.length()
            RecordingStore.update(rec.id) {
                when {
                    stopForSpace -> it.copy(status = RecStatus.DONE, bytes = bytes, endMillis = System.currentTimeMillis(), error = "Stopped early: the ${com.gameday.tv.data.Device.noun}'s storage is full.")
                    bytes < 200_000 -> it.copy(status = RecStatus.FAILED, bytes = bytes, error = lastError ?: "No video was received.")
                    else -> it.copy(status = RecStatus.DONE, bytes = bytes)
                }
            }
            jobs.remove(rec.id)
            updateNotification()
            stopIfIdle()
        }
        active = jobs.keys.toSet()
    }

    private suspend fun gameStillLive(eventId: String): Boolean {
        val league = Leagues.byKey(eventId.substringBefore(':')) ?: return false
        return runCatching {
            if (league.sport == "golf") scores.fetchGolf(league).any { it.id == eventId && it.roundInProgress }
            else scores.fetch(league).any { it.id == eventId && it.state == GameState.LIVE }
        }.getOrDefault(false)
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            active = emptySet()
            RecordScheduler.reschedule(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun currentText(): String {
        val titles = jobs.keys.mapNotNull { RecordingStore.get(it)?.title }
        return if (titles.isEmpty()) "Getting ready to record…" else "Recording " + titles.joinToString(", ")
    }

    private fun updateNotification() {
        active = jobs.keys.toSet()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(currentText()))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            // The app's own launcher activity (the TV and phone apps each have one).
            this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        /** Recordings this process is writing right now. */
        @Volatile
        var active: Set<String> = emptySet()
            private set

        const val ACTION_CHECK = "com.gameday.tv.dvr.CHECK"
        const val ACTION_STOP = "com.gameday.tv.dvr.STOP"
        const val EXTRA_ID = "id"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 41
        /** Start this early so the first seconds aren't lost to connecting. */
        const val LEAD_MS = 30_000L
        private const val EXTENSION_MS = 15 * 60_000L
        private const val MAX_EXTENSIONS = 8
        private const val MIN_FREE_BYTES = 500_000_000L

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}

/** Wakes the app when the next recording is due. */
object RecordScheduler {
    fun reschedule(context: Context) {
        RecordingStore.init(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, RecordAlarmReceiver::class.java).setAction(RecordingService.ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = RecordingStore.nextScheduledStart()
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = maxOf(next - RecordingService.LEAD_MS, System.currentTimeMillis() + 1_000)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** True when Android lets the app wake exactly on time (needed for reliable recordings on Android 12+). */
    fun canWakeExactly(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Starts the recorder now (from the app, while it's open). */
    fun kick(context: Context) {
        RecordingStore.init(context)
        val now = System.currentTimeMillis()
        // Only wake the recorder when there's something new for it: a due recording, or one that
        // was interrupted (the app was killed) and isn't being written.
        val needed = RecordingStore.all.value.any {
            (it.status == RecStatus.SCHEDULED && it.startMillis - RecordingService.LEAD_MS <= now) ||
                (it.status == RecStatus.RECORDING && it.id !in RecordingService.active)
        }
        if (needed) startService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_CHECK))
        reschedule(context)
    }

    fun stop(context: Context, id: String) {
        startService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP).putExtra(RecordingService.EXTRA_ID, id))
    }

    private fun startService(context: Context, intent: Intent) {
        try {
            context.startForegroundService(intent)
        } catch (_: Exception) {
            // Android blocked a background start; the next app launch or alarm retries.
        }
    }
}

class RecordAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> RecordScheduler.reschedule(context)
            else -> RecordScheduler.kick(context)
        }
    }
}
