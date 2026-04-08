package com.example.wechatbot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MessagesFragment : Fragment() {

    private lateinit var rvMessages: RecyclerView
    private lateinit var tvEmpty: TextView
    private val adapter = MessageAdapter()
    private val handler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refreshMessages()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_messages, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMessages = view.findViewById(R.id.rvMessages)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        rvMessages.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        rvMessages.adapter = adapter

        handler.post(refresher)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refresher)
    }

    private fun refreshMessages() {
        if (!isAdded) return
        val messages = MessageMonitor.getMessages()
        adapter.submitList(messages)

        if (messages.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvMessages.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvMessages.visibility = View.VISIBLE
        }
    }
}
