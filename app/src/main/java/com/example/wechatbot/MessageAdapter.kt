package com.example.wechatbot

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.wechatbot.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter : ListAdapter<ChatMessage, MessageAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvRoom: TextView = view.findViewById(R.id.tvRoom)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = getItem(position)

        val senderName = msg.sender.ifBlank { "?" }
        holder.tvAvatar.text = senderName.first().toString()
        holder.tvSender.text = senderName
        holder.tvRoom.text = msg.roomName
        holder.tvTime.text = timeFormat.format(Date(msg.timestamp))
        holder.tvContent.text = msg.content
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) = old.id == new.id
        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) = old == new
    }
}
