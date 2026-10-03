package com.gameday.tv.dvr

import android.content.Context
import android.os.StatFs
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every recording on this device (all app accounts), saved as JSON next to the video files.
 * Shared by the UI and [RecordingService], which may run without the UI.
 */
object RecordingStore {
    private var file: File? = null
    private var dir: File? = null
    private val state = MutableStateFlow<List<Recording>>(emptyList())
    val all: StateFlow<List<Recording>> get() = state

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val app = context.applicationContext
        file = File(app.filesDir, "recordings.json")
        dir = (app.getExternalFilesDir("recordings") ?: File(app.filesDir, "recordings")).apply { mkdirs() }
        state.value = load()
    }

    val directory: File get() = dir ?: error("RecordingStore.init not called")

    fun get(id: String): Recording? = state.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(r: Recording) {
        val list = state.value.filter { it.id != r.id } + r
        commit(list)
    }

    @Synchronized
    fun update(id: String, change: (Recording) -> Recording): Recording? {
        var updated: Recording? = null
        val list = state.value.map { if (it.id == id) change(it).also { n -> updated = n } else it }
        commit(list)
        return updated
    }

    /** Deletes the entry and its video file. */
    @Synchronized
    fun remove(id: String) {
        state.value.firstOrNull { it.id == id }?.file?.let { File(it).delete() }
        commit(state.value.filter { it.id != id })
    }

    @Synchronized
    fun removeAccount(accountId: String) {
        state.value.filter { it.accountId == accountId }.forEach { r -> r.file?.let { File(it).delete() } }
        commit(state.value.filter { it.accountId != accountId })
    }

    fun nextScheduledStart(): Long? =
        state.value.filter { it.status == RecStatus.SCHEDULED }.minOfOrNull { it.startMillis }

    /** Bytes free where recordings are written. */
    fun freeBytes(): Long = runCatching { StatFs(directory.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun usedBytes(accountId: String? = null): Long =
        state.value.filter { accountId == null || it.accountId == accountId }.sumOf { r -> r.file?.let { File(it).length() } ?: 0L }

    /**
     * Frees space: drops finished recordings older than [keepDays], then the oldest ones until the
     * account is under [capGb]. Recordings in progress are never touched.
     */
    @Synchronized
    fun enforceLimits(accountId: String, capGb: Int, keepDays: Int, now: Long = System.currentTimeMillis()) {
        val finished = state.value.filter { it.accountId == accountId && it.status in FINISHED }.sortedBy { it.startMillis }
        if (keepDays > 0) {
            finished.filter { now - it.endMillis > keepDays * 86_400_000L }.forEach { remove(it.id) }
        }
        if (capGb > 0) {
            val cap = capGb * 1_000_000_000L
            var used = usedBytes(accountId)
            for (r in state.value.filter { it.accountId == accountId && it.status in FINISHED }.sortedBy { it.startMillis }) {
                if (used <= cap) break
                used -= r.file?.let { File(it).length() } ?: 0L
                remove(r.id)
            }
        }
    }

    private val FINISHED = setOf(RecStatus.DONE, RecStatus.FAILED, RecStatus.CANCELLED)

    private fun commit(list: List<Recording>) {
        state.value = list.sortedBy { it.startMillis }
        val arr = JSONArray()
        list.forEach { arr.put(encode(it)) }
        file?.let { f ->
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }

    private fun load(): List<Recording> = runCatching {
        val arr = JSONArray(file!!.readText())
        (0 until arr.length()).mapNotNull { runCatching { decode(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun encode(r: Recording) = JSONObject()
        .put("id", r.id).put("account", r.accountId).put("title", r.title).put("subtitle", r.subtitle)
        .put("channelId", r.channelId).put("channelName", r.channelName).put("channelLogo", r.channelLogo.orEmpty())
        .put("urls", JSONArray(r.urls)).put("start", r.startMillis).put("end", r.endMillis).put("status", r.status.name)
        .put("file", r.file.orEmpty()).put("bytes", r.bytes).put("event", r.eventId.orEmpty()).put("image", r.image.orEmpty())
        .put("error", r.error.orEmpty()).put("auto", r.auto).put("created", r.createdAt)

    private fun decode(o: JSONObject): Recording {
        val urls = o.optJSONArray("urls")
        return Recording(
            id = o.getString("id"),
            accountId = o.optString("account"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            channelId = o.optString("channelId"),
            channelName = o.optString("channelName"),
            channelLogo = o.optString("channelLogo").ifBlank { null },
            urls = if (urls == null) emptyList() else (0 until urls.length()).map { urls.getString(it) },
            startMillis = o.optLong("start"),
            endMillis = o.optLong("end"),
            status = runCatching { RecStatus.valueOf(o.optString("status")) }.getOrDefault(RecStatus.FAILED),
            file = o.optString("file").ifBlank { null },
            bytes = o.optLong("bytes"),
            eventId = o.optString("event").ifBlank { null },
            image = o.optString("image").ifBlank { null },
            error = o.optString("error").ifBlank { null },
            auto = o.optBoolean("auto"),
            createdAt = o.optLong("created"),
        )
    }
}
