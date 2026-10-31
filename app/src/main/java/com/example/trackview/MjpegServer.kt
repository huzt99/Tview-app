package com.example.trackview

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * سيرفر بسيط داخل التطبيق يبث الصور (MJPEG) على الشبكة المحلية.
 * أي جهاز على نفس الواي فاي يفتح العنوان في المتصفح ويشوف البث،
 * بشرط يكتب الرقم السري (pin) الصحيح في الرابط.
 */
class MjpegServer(private val port: Int, private val pin: String) {

    @Volatile private var running = false
    @Volatile private var frame: ByteArray? = null
    @Volatile private var frameId = 0L

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** عدد الأجهزة المتصلة بالبث الآن */
    val clients = AtomicInteger(0)

    fun start() {
        running = true
        pool.execute {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                while (running) {
                    val socket = server.accept()
                    pool.execute { handle(socket) }
                }
            } catch (e: Exception) {
                // السيرفر انقفل أو المنفذ مشغول
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        pool.shutdownNow()
    }

    /** استقبال صورة جديدة (JPEG) من الكاميرا */
    fun update(jpeg: ByteArray) {
        frame = jpeg
        frameId++
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 5000
                val reader = s.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }
                s.soTimeout = 0

                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val route = path.substringBefore('?')
                val query = path.substringAfter('?', "")
                val pinOk = query.split("&").contains("pin=$pin")
                val out = s.getOutputStream()

                if (!pinOk) {
                    sendText(out, "403 Forbidden", "text/plain", "Wrong or missing pin")
                    return
                }

                when (route) {
                    "/" -> sendText(
                        out, "200 OK", "text/html",
                        "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                            "<body style=\"margin:0;background:#000\">" +
                            "<img src=\"/stream?pin=$pin\" style=\"width:100%\"></body></html>"
                    )
                    "/stream" -> streamFrames(s, out)
                    else -> sendText(out, "404 Not Found", "text/plain", "Not found")
                }
            } catch (e: Exception) {
                // الجهاز قطع الاتصال
            }
        }
    }

    private fun streamFrames(s: Socket, out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.flush()

        clients.incrementAndGet()
        try {
            var lastSent = -1L
            while (running && !s.isClosed) {
                val current = frame
                val id = frameId
                if (current != null && id != lastSent) {
                    out.write(
                        ("--frame\r\nContent-Type: image/jpeg\r\n" +
                            "Content-Length: ${current.size}\r\n\r\n").toByteArray()
                    )
                    out.write(current)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    lastSent = id
                } else {
                    Thread.sleep(30)
                }
            }
        } finally {
            clients.decrementAndGet()
        }
    }

    private fun sendText(out: OutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 $status\r\n" +
                "Content-Type: $type; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }
}
