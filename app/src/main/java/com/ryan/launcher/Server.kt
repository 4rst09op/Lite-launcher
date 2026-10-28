package com.ryan.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Tiny LAN web server. Runs ONLY while the Customize card is open on the TV. */
class Server(
    private val ctx: Context,
    private val cfg: Cfg,
    private val appsJson: () -> String,
    private val onChange: () -> Unit,
    private val port: Int = 8080
) {
    private var ss: ServerSocket? = null
    @Volatile private var up = false

    fun start() {
        if (up) return
        up = true
        Thread {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                ss = s
                while (up) {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    fun stop() {
        up = false
        try { ss?.close() } catch (e: Exception) {}
        ss = null
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 30000
            val inp = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var len = 0L
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0 && l.substring(0, i).equals("content-length", true)) len = l.substring(i + 1).trim().toLongOrNull() ?: 0L
            }
            if (method == "GET" && path == "/") {
                send(out, 200, "text/html; charset=utf-8", ctx.assets.open("customize.html").readBytes())
            } else if (method == "GET" && path == "/api/state") {
                send(out, 200, "application/json", state().toByteArray())
            } else if (method == "POST" && path == "/api/config") {
                val body = readBody(inp, len)
                cfg.replace(JSONObject(String(body)))
                onChange()
                send(out, 200, "text/plain", "ok".toByteArray())
            } else if (method == "POST" && path.startsWith("/upload/")) {
                val name = when (path.removePrefix("/upload/")) {
                    "image" -> "wall.jpg"
                    "video" -> "wall.mp4"
                    "music" -> "music.bin"
                    else -> null
                }
                if (name == null || len > 400_000_000L) {
                    send(out, 400, "text/plain", "bad".toByteArray())
                } else {
                    save(inp, len, File(ctx.filesDir, name))
                    onChange()
                    send(out, 200, "text/plain", "ok".toByteArray())
                }
            } else {
                send(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (e: Exception) {
        } finally {
            try { c.close() } catch (e: Exception) {}
        }
    }

    private fun state(): String {
        val o = JSONObject()
        o.put("config", cfg.o)
        o.put("apps", JSONArray(appsJson()))
        val has = JSONObject()
        has.put("image", File(ctx.filesDir, "wall.jpg").exists())
        has.put("video", File(ctx.filesDir, "wall.mp4").exists())
        has.put("music", File(ctx.filesDir, "music.bin").exists())
        o.put("has", has)
        return o.toString()
    }

    private fun readHead(i: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val b = i.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun readBody(i: InputStream, len: Long): ByteArray {
        val n = len.toInt().coerceIn(0, 1_000_000)
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = i.read(b, o, n - o)
            if (r < 0) break
            o += r
        }
        return b
    }

    private fun save(i: InputStream, len: Long, dest: File) {
        val tmp = File(dest.path + ".tmp")
        FileOutputStream(tmp).use { o ->
            val buf = ByteArray(65536)
            var left = len
            while (left > 0) {
                val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                o.write(buf, 0, r)
                left -= r
            }
        }
        tmp.renameTo(dest)
    }

    private fun send(o: OutputStream, code: Int, type: String, body: ByteArray) {
        val h = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.size +
            "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        o.write(h.toByteArray())
        o.write(body)
        o.flush()
    }
}
