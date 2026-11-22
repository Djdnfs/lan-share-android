package com.lanshare

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 纯 JDK 实现的极简 HTTP 服务器（零第三方依赖）：
 *  - GET  /             : 文件列表页面
 *  - GET  /download?f=  : 下载文件
 *  - POST /upload       : 上传文件（multipart/form-data）
 */
class LanServer(
    private val port: Int,
    private val rootDir: File,
    private val onLog: (String) -> Unit
) {

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return
        running = true
        rootDir.mkdirs()
        Thread {
            try {
                val ss = ServerSocket(port)
                ss.reuseAddress = true
                serverSocket = ss
                onLog("服务已启动，端口 $port")
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    pool.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                running = false
                onLog("启动失败：${e.message}")
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        onLog("服务已停止")
    }

    // ---------------- 请求处理 ----------------

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> respondIndex(output)
                method == "GET" && path == "/download" -> respondDownload(output, query)
                method == "POST" && path == "/upload" -> respondUpload(input, output, headers)
                else -> sendText(output, 404, "Not Found", "未找到该地址")
            }
            output.flush()
        } catch (e: Exception) {
            onLog("请求异常：${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun respondIndex(output: OutputStream) {
        val files = rootDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        val rows = StringBuilder()
        if (files.isEmpty()) {
            rows.append("<tr><td colspan=\"3\" class=\"empty\">共享目录为空，先上传文件或在手机上添加文件</td></tr>")
        }
        for (f in files) {
            val name = escapeHtml(f.name)
            val encoded = java.net.URLEncoder.encode(f.name, "UTF-8")
            rows.append(
                "<tr><td class=\"name\"><a href=\"/download?f=$encoded\">$name</a></td>" +
                    "<td class=\"size\">${readableSize(f.length())}</td>" +
                    "<td class=\"act\"><a class=\"btn\" href=\"/download?f=$encoded\">下载</a></td></tr>"
            )
        }
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>LanShare 局域网传输</title>
            <style>
            body{font-family:-apple-system,Segoe UI,sans-serif;margin:0;background:#f5f7fa;color:#1f2937}
            .wrap{max-width:760px;margin:0 auto;padding:16px}
            h1{font-size:20px;margin:8px 0 4px}
            .sub{color:#6b7280;font-size:13px;margin-bottom:16px}
            .card{background:#fff;border-radius:12px;padding:14px;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:16px}
            table{width:100%;border-collapse:collapse;font-size:14px}
            td{padding:9px 6px;border-bottom:1px solid #f0f2f5}
            .name a{color:#1f2937;text-decoration:none}
            .size{color:#6b7280;text-align:right;white-space:nowrap}
            .act{text-align:right;width:64px}
            .btn{display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:6px 12px;border-radius:8px;font-size:13px}
            .empty{color:#9ca3af;text-align:center;padding:18px}
            form{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
            input[type=file]{flex:1;min-width:180px}
            button{background:#10b981;color:#fff;border:0;padding:9px 16px;border-radius:8px;font-size:14px}
            .tip{font-size:12px;color:#6b7280;margin-top:8px}
            </style></head><body><div class="wrap">
            <h1>LanShare 局域网传输</h1>
            <div class="sub">同一 WiFi 下的设备可直接在此上传、下载文件</div>
            <div class="card"><form method="post" action="/upload" enctype="multipart/form-data">
            <input type="file" name="file" required>
            <button type="submit">上传到手机</button>
            </form><div class="tip">单个文件建议不超过 200MB</div></div>
            <div class="card"><table>$rows</table></div>
            </div></body></html>
        """.trimIndent()
        sendText(output, 200, "OK", html)
    }

    private fun respondDownload(output: OutputStream, query: String) {
        val name = parseQuery(query)["f"]
        if (name.isNullOrEmpty()) return sendText(output, 400, "Bad Request", "缺少文件名")
        val file = safeFile(name)
        if (file == null || !file.isFile) return sendText(output, 404, "Not Found", "文件不存在")

        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: application/octet-stream\r\n")
        head.append("Content-Length: ${file.length()}\r\n")
        head.append("Content-Disposition: attachment; filename*=UTF-8''")
        head.append(java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20"))
        head.append("\r\nConnection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
            }
        }
        output.flush()
    }

    private fun respondUpload(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val contentType = headers["content-type"] ?: ""
        val key = "boundary="
        val bi = contentType.indexOf(key, ignoreCase = true)
        if (!contentType.contains("multipart/form-data") || bi < 0) {
            return sendText(output, 400, "Bad Request", "只支持 multipart/form-data 表单上传")
        }
        val boundary = contentType.substring(bi + key.length).trim().trim('"')
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        if (length <= 0) return sendText(output, 411, "Length Required", "缺少 Content-Length")
        if (length > MAX_UPLOAD) return sendText(output, 413, "Too Large", "文件超过 200MB 限制")

        val body = readBody(input, length.toInt())
        val parsed = parseMultipart(body, boundary)
        if (parsed == null) return sendText(output, 400, "Bad Request", "没有解析到上传的文件")

        val safeName = sanitizeFileName(parsed.first)
        if (safeName.isEmpty()) return sendText(output, 400, "Bad Request", "非法文件名")

        val target = uniqueFile(rootDir, safeName)
        target.outputStream().use { it.write(parsed.second) }
        onLog("收到文件：${target.name}（${readableSize(target.length())}）")
        sendRedirect(output, "/")
    }

    // ---------------- multipart 解析 ----------------

    private fun parseMultipart(body: ByteArray, boundary: String): Pair<String, ByteArray>? {
        val delim = "--$boundary"
        var pos = indexOf(body, delim.toByteArray(Charsets.US_ASCII), 0)
        if (pos < 0) return null
        while (pos >= 0) {
            var p = pos + delim.length
            if (p + 1 < body.size && body[p] == '-'.code.toByte() && body[p + 1] == '-'.code.toByte()) {
                return null
            }
            if (p + 1 < body.size && body[p] == 13.toByte()) p += 2
            val headerEnd = indexOf(body, CRLF2, p)
            if (headerEnd < 0) return null
            val headerText = String(body, p, headerEnd - p, Charsets.UTF_8)
            val contentStart = headerEnd + 4
            val nextDelim = indexOf(body, ("\r\n" + delim).toByteArray(Charsets.US_ASCII), contentStart)
            val contentEnd = if (nextDelim < 0) body.size else nextDelim

            val filename = extractFilename(headerText)
            if (!filename.isNullOrBlank() && contentEnd >= contentStart) {
                val data = ByteArray(contentEnd - contentStart)
                System.arraycopy(body, contentStart, data, 0, data.size)
                return filename to data
            }
            if (nextDelim < 0) return null
            pos = nextDelim + 2
        }
        return null
    }

    private fun extractFilename(headerText: String): String? {
        val idx = headerText.lowercase().indexOf("filename=")
        if (idx < 0) return null
        var v = headerText.substring(idx + "filename=".length).trim()
        if (v.startsWith("\"")) {
            v = v.substring(1)
            val end = v.indexOf('"')
            if (end >= 0) v = v.substring(0, end)
        } else {
            v = v.substringBefore(';').trim()
        }
        return v.ifBlank { null }
    }

    private fun indexOf(src: ByteArray, target: ByteArray, from: Int): Int {
        if (target.isEmpty() || src.size < target.size) return -1
        var i = maxOf(from, 0)
        val limit = src.size - target.size
        while (i <= limit) {
            var j = 0
            while (j < target.size && src[i + j] == target[j]) j++
            if (j == target.size) return i
            i++
        }
        return -1
    }

    // ---------------- 工具方法 ----------------

    private fun safeFile(name: String): File? {
        val clean = File(name).name
        if (clean.isEmpty() || clean == "." || clean == "..") return null
        return File(rootDir, clean)
    }

    private fun sanitizeFileName(name: String): String {
        return File(name).name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base($i)$ext")
            i++
        }
        return f
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(length, 1 shl 20))
        val buf = ByteArray(8192)
        var total = 0
        while (total < length) {
            val n = input.read(buf, 0, minOf(buf.size, length - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == 10) return sb.toString()
            if (b != 13) sb.append(b.toChar())
            if (sb.length > 4096) return sb.toString()
            b = input.read()
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            map[java.net.URLDecoder.decode(k, "UTF-8")] = java.net.URLDecoder.decode(v, "UTF-8")
        }
        return map
    }

    private fun sendText(
        output: OutputStream,
        code: Int,
        reason: String,
        text: String,
        contentType: String = "text/html; charset=utf-8"
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendRedirect(output: OutputStream, location: String) {
        val head = "HTTP/1.1 303 See Other\r\n" +
            "Location: $location\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun readableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    companion object {
        const val MAX_UPLOAD = 200L * 1024 * 1024
        private val CRLF2 = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
}
