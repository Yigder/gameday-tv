package com.gameday.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.gameday.tv.BuildConfig
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A newer release on GitHub: its version and where to get it (the APK itself when the release has one). */
data class AppUpdate(val version: String, val url: String) {
    val isApk: Boolean get() = url.endsWith(".apk", ignoreCase = true)
}

/** Where updating the app from inside it has got to. */
sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    /** [fraction] is null until the size is known. */
    data class Downloading(val fraction: Float?) : UpdateProgress
    /** Handed to Android's installer; waiting on the viewer's confirmation or the install itself. */
    data object Installing : UpdateProgress
    /** "Install unknown apps" has to be switched on for this app first. */
    data object NeedsPermission : UpdateProgress
    data class Failed(val message: String) : UpdateProgress
}

/** Release parsing and version comparison, kept free of Android types so they can be unit tested. */
object Releases {
    const val REPO = "Yigder/gameday-tv"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** The APK each app looks for on a release (the README and Downloader code rely on these names). */
    val assetName: String get() = if (Device.isMobile) "GameDay.apk" else "GameDayTV.apk"

    /** Reads GitHub's "latest release" answer: the version, and [asset]'s download link (else the release page). */
    fun parse(json: String, asset: String): AppUpdate {
        val o = JSONObject(json)
        val version = o.optString("tag_name").removePrefix("v").trim().ifEmpty { throw IOException("GitHub's answer had no version") }
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").equals(asset, ignoreCase = true) }
            ?.optString("browser_download_url")?.takeIf { it.isNotEmpty() }
        val page = o.optString("html_url").ifEmpty { "https://github.com/$REPO/releases/latest" }
        return AppUpdate(version, apk ?: page)
    }

    /** "2.10.0" is newer than "2.9"; "2.6.0" is the same as "2.6"; anything after a dash ("-beta") is ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * Asks GitHub for the latest release now and then (at most every six hours, or right away from
 * Settings › About), and updates the app in place: the APK is downloaded inside the app and passed
 * to Android's installer, which asks the viewer to confirm. Only the public release info and file
 * are fetched; nothing about the viewer is sent. Pre-releases are never offered, and Android itself
 * refuses an APK that isn't signed like the installed app.
 */
class UpdateChecker(private val context: Context, private val onProgress: (UpdateProgress) -> Unit) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir get() = File(context.cacheDir, "updates")
    private val userAgent = "GameDay/${BuildConfig.VERSION_NAME}"

    init {
        active = this
    }

    /** The update to offer, or null when the app is current (or this version was dismissed). */
    fun saved(): AppUpdate? {
        val v = prefs.getString("latest_version", null) ?: return null
        val url = prefs.getString("latest_url", null) ?: return null
        return AppUpdate(v, url).takeIf { Releases.isNewer(v, BuildConfig.VERSION_NAME) && v != prefs.getString("dismissed", null) }
    }

    /** A finished update leaves its APK behind; it's no use once that version is running. */
    fun cleanUp() {
        if (saved() == null) dir.deleteRecursively()
    }

    val checkedRecently: Boolean get() = System.currentTimeMillis() - prefs.getLong("checked_at", 0) < 6 * 3_600_000L

    /** Blocking: asks GitHub and returns the update to offer. [forget] brings back a dismissed version. */
    fun fetch(forget: Boolean = false): AppUpdate? {
        if (forget) prefs.edit().remove("dismissed").apply()
        val req = Request.Builder().url(Releases.LATEST_URL)
            .header("Accept", "application/vnd.github+json").header("User-Agent", userAgent).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}")
            val u = Releases.parse(r.body.string(), Releases.assetName)
            prefs.edit().putString("latest_version", u.version).putString("latest_url", u.url)
                .putLong("checked_at", System.currentTimeMillis()).apply()
        }
        return saved()
    }

    /** Hides the banner until a version newer than [update] comes out. */
    fun dismiss(update: AppUpdate) {
        prefs.edit().putString("dismissed", update.version).apply()
    }

    /** Whether Android lets this app hand APKs to its installer ("Install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the viewer allows this app to install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Blocking: downloads [update], checks it and starts installing it. */
    fun install(update: AppUpdate) {
        val apk = download(update)
        verify(apk)
        onProgress(UpdateProgress.Installing)
        startSession(apk)
    }

    private fun download(update: AppUpdate): File {
        dir.mkdirs()
        val file = File(dir, "GameDay-${update.version}.apk")
        if (file.exists()) return file
        val part = File(dir, file.name + ".part")
        val req = Request.Builder().url(update.url).header("User-Agent", userAgent).build()
        onProgress(UpdateProgress.Downloading(null))
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}")
            val body = r.body
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = total?.let { (done * 100 / it).toInt() } ?: -1
                        if (pct != shown) {
                            shown = pct
                            onProgress(UpdateProgress.Downloading(total?.let { done.toFloat() / it }))
                        }
                    }
                }
            }
            if (total != null && done != total) throw IOException("The download was cut short")
        }
        if (!part.renameTo(file)) throw IOException("Couldn't save the update")
        return file
    }

    /** Only this app (TV or phone), and only something newer than what's running, is passed on. */
    @Suppress("DEPRECATION")
    private fun verify(apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info == null) { apk.delete(); throw IOException("The downloaded file isn't an app") }
        if (info.packageName != context.packageName) { apk.delete(); throw IOException("The download is a different app") }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) throw IOException("That's not newer than this version")
    }

    private fun startSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Once the app installed itself, later updates can go through without the confirm screen (Android 12+).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("GameDay.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallerResult(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // On success Android replaces the app and closes it; there's nothing left to do here.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> onProgress(UpdateProgress.Idle)
            else -> onProgress(
                UpdateProgress.Failed(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "Android wouldn't install it over this version (it's signed differently)"
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space to install the update"
                        else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update didn't install"
                    },
                ),
            )
        }
    }

    companion object {
        /** The checker the installer reports back to (the view model's). */
        @Volatile
        internal var active: UpdateChecker? = null
    }
}

/** Android's installer reports here: it asks for the viewer's go-ahead, or says how the install went. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateChecker.active?.onInstallerResult(intent)
    }
}
