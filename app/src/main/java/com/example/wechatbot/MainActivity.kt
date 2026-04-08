package com.example.wechatbot

import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.example.wechatbot.databinding.ActivityMainBinding
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var httpServer: HttpApiServer? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    // 定期刷新状态
    private val statusChecker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        setupServiceListener()

        // 设置日志回调
        MessageMonitor.onLog = { msg -> appendLog(msg) }
        MessageSender.onLog = { msg -> appendLog(msg) }

        handler.post(statusChecker)
        appendLog("WeChatBot 已启动")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusChecker)
        stopHttpServer()
    }

    private fun setupListeners() {
        binding.btnOpenAccessibility.setOnClickListener {
            AssistsCore.openAccessibilitySetting()
        }

        binding.btnToggleHttp.setOnClickListener {
            if (httpServer != null) {
                stopHttpServer()
            } else {
                startHttpServer()
            }
        }

        binding.btnCheckUpdate.setOnClickListener {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.text = "检查中..."
            val versionCode = try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (e: Exception) { 1 }
            UpdateChecker.checkUpdate(this, versionCode)
        }

        // 更新检查回调
        UpdateChecker.onLog = { msg -> appendLog("[更新] $msg") }
        UpdateChecker.onStatus = { status ->
            when (status) {
                UpdateChecker.UpdateStatus.CHECKING -> {
                    binding.tvUpdateStatus.text = "正在检查更新..."
                }
                UpdateChecker.UpdateStatus.UPDATE_AVAILABLE -> {
                    binding.tvUpdateStatus.text = "发现新版本，准备下载..."
                }
                UpdateChecker.UpdateStatus.DOWNLOADING -> {
                    binding.tvUpdateStatus.text = "正在下载..."
                }
                UpdateChecker.UpdateStatus.DOWNLOADED -> {
                    binding.tvUpdateStatus.text = "下载完成，正在安装..."
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                }
                UpdateChecker.UpdateStatus.NO_UPDATE -> {
                    binding.tvUpdateStatus.text = "已是最新版本"
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                }
                UpdateChecker.UpdateStatus.ERROR -> {
                    binding.tvUpdateStatus.text = "更新检查失败"
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                }
                UpdateChecker.UpdateStatus.IDLE -> {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                }
            }
        }
    }

    /**
     * 注册无障碍服务事件监听
     */
    private fun setupServiceListener() {
        AssistsService.listeners.add(object : AssistsServiceListener {
            override fun onAccessibilityEvent(event: AccessibilityEvent) {
                MessageMonitor.processEvent(event)
            }

            override fun onServiceConnected(service: AssistsService) {
                handler.post {
                    appendLog("无障碍服务已连接")
                    updateStatus()
                }
            }

            override fun onUnbind() {
                handler.post {
                    appendLog("无障碍服务已断开")
                    updateStatus()
                }
            }
        })
    }

    /**
     * 启动 HTTP 服务
     */
    private fun startHttpServer() {
        try {
            httpServer = HttpApiServer(8080).apply {
                onLog = { msg -> appendLog("[HTTP] $msg") }
                start()
            }
            appendLog("HTTP 服务已启动，端口 8080")
            updateStatus()
        } catch (e: Exception) {
            appendLog("HTTP 服务启动失败: ${e.message}")
        }
    }

    /**
     * 停止 HTTP 服务
     */
    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
        appendLog("HTTP 服务已停止")
        updateStatus()
    }

    /**
     * 更新界面状态
     */
    private fun updateStatus() {
        val isAccessibilityEnabled = AssistsCore.isAccessibilityServiceEnabled()
        binding.tvAccessibilityStatus.text = if (isAccessibilityEnabled) "已开启" else "未开启"
        binding.tvAccessibilityStatus.setTextColor(
            if (isAccessibilityEnabled) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        )

        val isHttpRunning = httpServer != null
        binding.tvHttpStatus.text = if (isHttpRunning) "运行中" else "未启动"
        binding.tvHttpStatus.setTextColor(
            if (isHttpRunning) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        )
        binding.btnToggleHttp.text = if (isHttpRunning) "停止 HTTP 服务" else "启动 HTTP 服务"

        if (isHttpRunning) {
            val ip = getLocalIpAddress()
            binding.tvApiAddress.text = "API 地址: http://$ip:8080"
        } else {
            binding.tvApiAddress.text = "API 地址: --"
        }

        binding.tvMessageCount.text = "已缓存消息: ${MessageMonitor.messageCount()}"
    }

    /**
     * 追加日志
     */
    private fun appendLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $message"
        handler.post {
            logLines.add(line)
            if (logLines.size > 200) {
                logLines.removeAt(0)
            }
            binding.tvLog.text = logLines.joinToString("\n")
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    /**
     * 获取本机 WiFi IP 地址
     */
    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }
}
