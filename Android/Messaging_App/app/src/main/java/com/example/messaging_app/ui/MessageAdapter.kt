package com.example.messaging_app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.messaging_app.data.ChatMessage
import com.example.messaging_app.data.MessageStatus
import com.example.messaging_app.databinding.ItemMessageIncomingBinding
import com.example.messaging_app.databinding.ItemMessageOutgoingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isMine) VIEW_OUT else VIEW_IN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_OUT) {
            OutgoingVH(ItemMessageOutgoingBinding.inflate(inflater, parent, false))
        } else {
            IncomingVH(ItemMessageIncomingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is OutgoingVH -> holder.bind(item)
            is IncomingVH -> holder.bind(item)
        }
    }

    class OutgoingVH(private val binding: ItemMessageOutgoingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.messageText.text = message.text
            binding.metaText.text = "${formatTime(message.timestamp)} · ${statusLabel(message.status)}"
        }
    }

    class IncomingVH(private val binding: ItemMessageIncomingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.senderName.text = message.senderName
            binding.messageText.text = message.text
            binding.metaText.text = formatTime(message.timestamp)
            binding.avatarText.text = initials(message.senderName)
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_OUT = 1
        private const val VIEW_IN = 2
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))

        fun statusLabel(status: MessageStatus): String = when (status) {
            MessageStatus.SENDING -> "Sending"
            MessageStatus.SENT -> "Sent"
            MessageStatus.DELIVERED -> "Delivered"
            MessageStatus.FAILED -> "Failed"
            MessageStatus.RECEIVED -> "Received"
        }

        fun initials(name: String): String {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0].take(2).uppercase(Locale.getDefault())
                else -> "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
            }
        }
    }
}
