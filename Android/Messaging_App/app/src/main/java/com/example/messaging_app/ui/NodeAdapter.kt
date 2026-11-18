package com.example.messaging_app.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.messaging_app.R
import com.example.messaging_app.data.PeerContact
import com.example.messaging_app.databinding.ItemNodeBinding

class NodeAdapter(
    private val onClick: (PeerContact) -> Unit
) : ListAdapter<PeerContact, NodeAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemNodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(peer: PeerContact) {
            val ctx = binding.root.context
            binding.nodeName.text = peer.displayName
            binding.nodeId.text = peer.id
            binding.nodeAvatar.text = MessageAdapter.initials(peer.displayName)
            val lastSeen = DateUtils.getRelativeTimeSpanString(
                peer.lastSeen,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            binding.nodeMeta.text = ctx.getString(
                R.string.node_last_seen
            ) + ": $lastSeen · " +
                ctx.getString(R.string.node_gps) + ": " + ctx.getString(R.string.value_unavailable) +
                " · " + ctx.getString(R.string.node_battery) + ": " + ctx.getString(R.string.value_unknown)
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
