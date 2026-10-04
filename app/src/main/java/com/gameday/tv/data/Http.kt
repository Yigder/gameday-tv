package com.gameday.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Shared HTTP client for API calls and video streams, so the User-Agent is consistent everywhere. */
object Http {
    const val DEFAULT_USER_AGENT = "GameDayTV/1.0 (Linux; Android TV)"

    @Volatile
    var userAgent: String = DEFAULT_USER_AGENT

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", userAgent).build() else req
                )
            }
            .build()
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        withStream(url, headers) { it.bufferedReader().readText() }

    /** Streams the response body to [block] on the IO dispatcher (used for large channel lists). */
    suspend fun <T> withStream(url: String, headers: Map<String, String> = emptyMap(), block: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(describeHttpError(resp.code))
            block(resp.body.byteStream())
        }
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw IOException("That address isn't a valid URL.")
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    fun describeHttpError(code: Int): String = when (code) {
        401, 403 -> "Access denied (HTTP $code). Check your login, or your provider may be blocking this device/User-Agent."
        404 -> "Not found (HTTP 404). Check the server address."
        429 -> "Too many requests (HTTP 429). Wait a moment and try again."
        in 500..599 -> "The server had a problem (HTTP $code). Try again later."
        else -> "Request failed (HTTP $code)."
    }
}
