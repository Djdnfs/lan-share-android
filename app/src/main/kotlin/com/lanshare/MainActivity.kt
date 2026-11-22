package com.lanshare

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 主界面：不依赖任何 XML 资源与第三方库，界面完全用代码构建。
 * 这种方式不会出现主题/资源缺失导致的启动崩溃。
 */
class MainActivity : Activity() {

    private val port = 8080
    private lateinit var server: LanServer
    private lateinit var shareDir: File
    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvDir: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button

    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareDir = File(getExternalFilesDir(null), "share")
        shareDir.mkdirs()

        server = LanServer(port, shareDir) { msg -> appendLog(msg) }
        buildUi()
        appendLog("共享目录已就绪：${shareDir.absolutePath}")
        startServer()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F5F7FA"))
        val pad = (16 * dp).toInt()
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "LanShare 局域网传输"
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.parseColor("#1F2937"))
        root.addView(title)

        tvStatus = TextView(this)
        tvStatus.textSize = 14f
        tvStatus.setTextColor(Color.parseColor("#6B7280"))
        tvStatus.setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
        root.addView(tvStatus)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundColor(Color.WHITE)
        val cp = (14 * dp).toInt()
        card.setPadding(cp, cp, cp, cp)
        root.addView(card)

        tvIp = TextView(this)
        tvIp.textSize = 16f
        tvIp.typeface = Typeface.MONOSPACE
        tvIp.setTextColor(Color.parseColor("#111827"))
        card.addView(tvIp)

        tvDir = TextView(this)
        tvDir.textSize = 12f
        tvDir.setTextColor(Color.parseColor("#6B7280"))
        tvDir.setPadding(0, (8 * dp).toInt(), 0, 0)
        card.addView(tvDir)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(0, (14 * dp).toInt(), 0, (10 * dp).toInt())
        root.addView(btnRow)

        btnToggle = Button(this)
        btnToggle.text = "停止服务"
        btnToggle.setOnClickListener { toggleServer() }
        btnRow.addView(btnToggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btnAdd = Button(this)
        btnAdd.text = "添加文件"
        btnAdd.setOnClickListener { pickFile() }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginStart = (10 * dp).toInt()
        btnRow.addView(btnAdd, lp)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        val sp = (12 * dp).toInt()
        scroll.setPadding(sp, sp, sp, sp)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        tvLog = TextView(this)
        tvLog.textSize = 12f
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.setTextColor(Color.parseColor("#374151"))
        tvLog.gravity = Gravity.START
        scroll.addView(tvLog)

        setContentView(root)
    }

    private fun toggleServer() {
        if (server.isRunning) {
            server.stop()
            btnToggle.text = "启动服务"
            tvStatus.text = "服务未运行"
            tvIp.text = "—"
        } else {
            startServer()
        }
    }

    private fun startServer() {
        server.start()
        btnToggle.text = "停止服务"
        tvStatus.text = "服务运行中 · 端口 $port"
        tvDir.text = "共享目录：" + shareDir.absolutePath
        tvIp.text = buildIpText()
    }

    private fun buildIpText(): String {
        val ips = localIpv4List()
        return if (ips.isEmpty()) {
            "未获取到局域网地址\n请确认已连接 WiFi"
        } else {
            val sb = StringBuilder()
            for (ip in ips) {
                sb.append("http://").append(ip).append(":").append(port).append("\n")
            }
            sb.append("\n在电脑/其它设备浏览器里打开上面地址")
            sb.toString()
        }
    }

    private fun localIpv4List(): List<String> {
        val result = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue
                        result.add(host)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun pickFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            appendLog("无法打开文件选择器：${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        Thread {
            try {
                val name = queryName(uri) ?: ("file_" + System.currentTimeMillis())
                val target = uniqueTarget(name)
                contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { outs ->
                        ins.copyTo(outs, 8192)
                    }
                }
                appendLog("已添加：${target.name}")
            } catch (e: Exception) {
                appendLog("添加失败：${e.message}")
            }
        }.start()
    }

    private fun queryName(uri: Uri): String? {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment
    }

    private fun uniqueTarget(name: String): File {
        val safe = File(name).name
        var f = File(shareDir, safe)
        if (!f.exists()) return f
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(shareDir, "$base($i)$ext")
            i++
        }
        return f
    }

    private fun appendLog(msg: String) {
        ui.post {
            logLines.add(msg)
            while (logLines.size > 200) logLines.removeAt(0)
            tvLog.text = logLines.joinToString("\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            server.stop()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val REQ_PICK = 1001
    }
}
