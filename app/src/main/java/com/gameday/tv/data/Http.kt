package com.gameday.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    /** The whole body, at most [maxBytes] (subtitle files). */
    suspend fun getBytes(url: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray = withStream(url) { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) throw IOException("The file is too large.")
        }
        out.toByteArray()
    }

    /** Streams the response body to [block] on the IO dispatcher (used for large channel lists). */
    suspend fun <T> withStream(url: String, headers: Map<String, String> = emptyMap(), block: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(describeHttpError(resp.code))
            block(resp.body.byteStream())
        }
    }

    /** A request whose body is read whatever the status (APIs that explain errors in JSON). */
    suspend fun call(url: String, headers: Map<String, String> = emptyMap(), form: Map<String, String>? = null): HttpResult =
        withContext(Dispatchers.IO) {
            val builder = buildRequest(url, headers)
            if (form != null) {
                val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).apply {
                    form.forEach { (k, v) -> addFormDataPart(k, v) }
                }.build()
                builder.post(body)
            }
            client.newCall(builder.build()).execute().use { resp -> HttpResult(resp.code, resp.body.string()) }
        }

    /** POSTs a JSON body; the response body is read whatever the status. */
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResult =
        withContext(Dispatchers.IO) {
            val body = json.toRequestBody("application/json".toMediaType())
            client.newCall(buildRequest(url, headers).post(body).build()).execute().use { resp -> HttpResult(resp.code, resp.body.string()) }
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

class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}
