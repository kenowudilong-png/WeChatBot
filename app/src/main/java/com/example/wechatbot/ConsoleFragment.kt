package com.example.wechatbot

import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.ven.assists.AssistsCore

class ConsoleFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val logLines = mutableListOf<String>()
    private val dateFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvHttpStatus: TextView
    private lateinit var tvApiAddress: TextView
    private lateinit var tvMessageCount: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnToggleHttp: Button
    private lateinit var btnCheckUpdate: Button

    private val statusChecker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_console, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvAccessibilityStatus = view.findViewById(R.id.tvAccessibilityStatus)
        tvHttpStatus = view.findViewById(R.id.tvHttpStatus)
        tvApiAddress = view.findViewById(R.id.tvApiAddress)
        tvMessageCount = view.findViewById(R.id.tvMessageCount)
        tvUpdateStatus = view.findViewById(R.id.tvUpdateStatus)
        tvLog = view.findViewById(R.id.tvLog)
        scrollLog = view.findViewById(R.id.scrollLog)
        btnToggleHttp = view.findViewById(R.id.btnToggleHttp)
        btnCheckUpdate = view.findViewById(R.id.btnCheckUpdate)

        view.findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            AssistsCore.openAccessibilitySetting()
        }

        btnToggleHttp.setOnClickListener {
            (activity as? MainActivity)?.toggleHttpServer()
        }

        btnCheckUpdate.setOnClickListener {
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.text = "检查中..."
            val versionCode = try {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionCode
            } catch (e: Exception) { 1 }
            UpdateChecker.checkUpdate(requireContext(), versionCode)
        }

        UpdateChecker.onLog = { msg -> appendLog("[更新] $msg") }
        UpdateChecker.onStatus = { status ->
            when (status) {
                UpdateChecker.UpdateStatus.CHECKING -> tvUpdateStatus.text = "正在检查更新..."
                UpdateChecker.UpdateStatus.UPDATE_AVAILABLE -> tvUpdateStatus.text = "发现新版本，准备下载..."
                UpdateChecker.UpdateStatus.DOWNLOADING -> tvUpdateStatus.text = "正在下载..."
                UpdateChecker.UpdateStatus.DOWNLOADED -> {
                    tvUpdateStatus.text = "下载完成，正在安装..."
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "检查更新"
                }
                UpdateChecker.UpdateStatus.NO_UPDATE -> {
                    tvUpdateStatus.text = "已是最新版本"
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "检查更新"
                }
                UpdateChecker.UpdateStatus.ERROR -> {
                    tvUpdateStatus.text = "更新检查失败"
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "检查更新"
                }
                UpdateChecker.UpdateStatus.IDLE -> {
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "检查更新"
                }
            }
        }

        MessageMonitor.onLog = { msg -> appendLog(msg) }
        MessageSender.onLog = { msg -> appendLog(msg) }

        handler.post(statusChecker)
        appendLog("好软件已启动")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(statusChecker)
    }

    fun updateStatus() {
        if (!isAdded) return

        val isAccessibilityEnabled = AssistsCore.isAccessibilityServiceEnabled()
        tvAccessibilityStatus.text = if (isAccessibilityEnabled) "已开启" else "未开启"
        tvAccessibilityStatus.setTextColor(
            if (isAccessibilityEnabled) resources.getColor(R.color.status_green, null)
            else resources.getColor(R.color.status_red, null)
        )

        val isHttpRunning = (activity as? MainActivity)?.isHttpRunning() == true
        tvHttpStatus.text = if (isHttpRunning) "运行中" else "未启动"
        tvHttpStatus.setTextColor(
            if (isHttpRunning) resources.getColor(R.color.status_green, null)
            else resources.getColor(R.color.status_red, null)
        )
        btnToggleHttp.text = if (isHttpRunning) "停止 HTTP 服务" else "启动 HTTP 服务"

        if (isHttpRunning) {
            val ip = getLocalIpAddress()
            tvApiAddress.text = "API 地址: http://$ip:8080"
        } else {
            tvApiAddress.text = "API 地址: --"
        }

        tvMessageCount.text = "已缓存消息: ${MessageMonitor.messageCount()}"
    }

    fun appendLog(message: String) {
        val timestamp = dateFormat.format(java.util.Date())
        val line = "[$timestamp] $message"
        handler.post {
            if (!isAdded) return@post
            logLines.add(line)
            if (logLines.size > 200) logLines.removeAt(0)
            tvLog.text = logLines.joinToString("\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = requireContext().applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff, ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }
}
