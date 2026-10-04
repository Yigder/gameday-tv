package com.gameday.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.ui.theme.AppColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A tiny web page on the local network for typing a long link or key on a phone instead of with
 * the remote. It runs only while the screen showing it is open, answers only at a random path,
 * and accepts one value.
 */
class PhoneInputServer(private val fieldLabel: String, private val secret: Boolean, private val onValue: (String) -> Unit) {
    private val token = buildString {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        repeat(8) { append(chars[rnd.nextInt(chars.length)]) }
    }
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Starts listening; returns the address to open on the phone, or null without a network. */
    fun start(): String? {
        val ip = lanAddress() ?: return null
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) } }
            }
        }
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream())
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("content-length:", true)) length = h.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, 16_384) ?: 0
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty().substringBefore('?')
            val out = sock.getOutputStream()
            fun respond(code: String, html: String) {
                val body = html.toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
            }
            when {
                path != "/$token" -> respond("404 Not Found", page("<p>Not found.</p>"))
                method == "GET" -> respond("200 OK", page(form()))
                method == "POST" -> {
                    val raw = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(raw, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val value = String(raw, 0, read, Charsets.UTF_8).split('&')
                        .firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                        ?.let { URLDecoder.decode(it, "UTF-8") }?.trim().orEmpty()
                    if (value.isEmpty()) {
                        respond("200 OK", page("<p>Nothing was entered.</p>" + form()))
                    } else {
                        main.post { onValue(value) }
                        respond("200 OK", page("<h2>Sent to your TV ✓</h2><p>You can close this page.</p>"))
                    }
                }
                else -> respond("405 Method Not Allowed", page(""))
            }
        }
    }

    private fun form(): String {
        val type = if (secret) "password" else "url"
        return """<form method="post"><label>$fieldLabel</label><input name="v" type="$type" autocomplete="off" autocapitalize="off" spellcheck="false" autofocus>
            |<button type="submit">Send to TV</button></form>""".trimMargin()
    }

    private fun page(body: String) = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        |<title>GameDay TV</title><style>body{font-family:system-ui,sans-serif;background:#0f0f0f;color:#f1f1f1;margin:0;padding:24px;max-width:560px}
        |label{display:block;margin:16px 0 8px;color:#aaa}input{width:100%;box-sizing:border-box;font-size:16px;padding:12px;border-radius:8px;border:1px solid #444;background:#1f1f1f;color:#fff}
        |button{margin-top:16px;width:100%;font-size:17px;padding:12px;border:0;border-radius:24px;background:#fff;color:#000;font-weight:600}</style></head>
        |<body><h1 style="font-size:22px">GameDay TV</h1>$body</body></html>""".trimMargin()

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            if (buf.size() > 8_192) return null
            buf.write(b)
        }
    }

    companion object {
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}

/** QR code + address for [PhoneInputServer]; [onValue] gets what the phone sends. */
@Composable
fun PhoneEntryPanel(fieldLabel: String, secret: Boolean, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(onValue)
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val server = PhoneInputServer(fieldLabel, secret) { latest(it) }
        url = runCatching { server.start() }.getOrNull()
        failed = url == null
        onDispose { server.stop() }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Or use your phone", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("Scan with a phone on the same Wi-Fi and paste it there.", fontSize = 12.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(12.dp))
        val u = url
        if (u != null) {
            val matrix = remember(u) { runCatching { qr(u) }.getOrNull() }
            Box(Modifier.size(196.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp)) {
                if (matrix != null) QrCanvas(matrix, Modifier.size(176.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(u, fontSize = 13.sp, color = AppColors.Text)
        } else if (failed) {
            Text("This TV isn't on a local network.", fontSize = 13.sp, color = AppColors.TextDim)
        }
    }
}

private fun qr(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))

@Composable
private fun QrCanvas(m: BitMatrix, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
