package com.example.wechatbot

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private var httpServer: HttpApiServer? = null

    private val messagesFragment = MessagesFragment()
    private val consoleFragment = ConsoleFragment()
    private val statsFragment = StatsFragment()
    private var activeFragment: Fragment = consoleFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 版本号
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${info.versionName}"
        } catch (_: Exception) {}

        setupFragments()
        setupServiceListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHttpServer()
    }

    private fun setupFragments() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        // 添加所有 Fragment，默认显示控制台
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, statsFragment, "stats").hide(statsFragment)
            .add(R.id.fragmentContainer, messagesFragment, "messages").hide(messagesFragment)
            .add(R.id.fragmentContainer, consoleFragment, "console")
            .commit()

        bottomNav.selectedItemId = R.id.nav_console

        bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_messages -> messagesFragment
                R.id.nav_console -> consoleFragment
                R.id.nav_stats -> statsFragment
                else -> consoleFragment
            }
            if (target !== activeFragment) {
                supportFragmentManager.beginTransaction()
                    .hide(activeFragment)
                    .show(target)
                    .commit()
                activeFragment = target
            }
            true
        }
    }

    private fun setupServiceListener() {
        AssistsService.listeners.add(object : AssistsServiceListener {
            override fun onAccessibilityEvent(event: AccessibilityEvent) {
                MessageMonitor.processEvent(event)
            }

            override fun onServiceConnected(service: AssistsService) {
                runOnUiThread {
                    consoleFragment.appendLog("无障碍服务已连接")
                    consoleFragment.updateStatus()
                }
            }

            override fun onUnbind() {
                runOnUiThread {
                    consoleFragment.appendLog("无障碍服务已断开")
                    consoleFragment.updateStatus()
                }
            }
        })
    }

    fun toggleHttpServer() {
        if (httpServer != null) {
            stopHttpServer()
        } else {
            startHttpServer()
        }
    }

    fun isHttpRunning(): Boolean = httpServer != null

    private fun startHttpServer() {
        try {
            httpServer = HttpApiServer(8080).apply {
                onLog = { msg -> consoleFragment.appendLog("[HTTP] $msg") }
                start()
            }
            consoleFragment.appendLog("HTTP 服务已启动，端口 8080")
            consoleFragment.updateStatus()
        } catch (e: Exception) {
            consoleFragment.appendLog("HTTP 服务启动失败: ${e.message}")
        }
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
        if (!isFinishing) {
            consoleFragment.appendLog("HTTP 服务已停止")
            consoleFragment.updateStatus()
        }
    }
}
