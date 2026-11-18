package com.example.messaging_app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.messaging_app.data.PeerContact
import com.example.messaging_app.databinding.ItemChatPeerBinding

class ChatPeerAdapter(
    private val onClick: (PeerContact) -> Unit
) : ListAdapter<PeerContact, ChatPeerAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemChatPeerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(peer: PeerContact) {
            binding.peerName.text = peer.displayName
            binding.peerPreview.text = peer.lastMessagePreview.ifBlank {
                binding.root.context.getString(com.example.messaging_app.R.string.chat_peer_subtitle)
            }
            binding.peerAvatar.text = MessageAdapter.initials(peer.displayName)
            binding.peerTime.text = MessageAdapter.formatTime(peer.lastSeen)
            binding.root.setOnClickListener { onClick(peer) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PeerContact>() {
        override fun areItemsTheSame(oldItem: PeerContact, newItem: PeerContact) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PeerContact, newItem: PeerContact) =
            oldItem == newItem
    }
}
