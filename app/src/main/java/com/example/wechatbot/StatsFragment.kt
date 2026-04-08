package com.example.wechatbot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.ven.assists.AssistsCore

class StatsFragment : Fragment() {

    private lateinit var tvStatMessages: TextView
    private lateinit var tvStatAiStatus: TextView
    private lateinit var tvStatService: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvStatMessages = view.findViewById(R.id.tvStatMessages)
        tvStatAiStatus = view.findViewById(R.id.tvStatAiStatus)
        tvStatService = view.findViewById(R.id.tvStatService)
        handler.post(refresher)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refresher)
    }

    private fun refreshStats() {
        if (!isAdded) return
        tvStatMessages.text = MessageMonitor.messageCount().toString()
        val httpRunning = (activity as? MainActivity)?.isHttpRunning() == true
        tvStatAiStatus.text = if (httpRunning) "运行" else "停止"
        tvStatAiStatus.setTextColor(
            if (httpRunning) resources.getColor(R.color.status_green, null)
            else resources.getColor(R.color.status_red, null)
        )
        val accessibilityOn = AssistsCore.isAccessibilityServiceEnabled()
        tvStatService.text = if (accessibilityOn) "开启" else "关闭"
        tvStatService.setTextColor(
            if (accessibilityOn) resources.getColor(R.color.status_green, null)
            else resources.getColor(R.color.status_red, null)
        )
    }
}
