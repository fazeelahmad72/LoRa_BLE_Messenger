package com.example.messaging_app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.messaging_app.data.ScannedDevice
import com.example.messaging_app.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onBindClick: (ScannedDevice) -> Unit
) : ListAdapter<ScannedDevice, DeviceAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_RSSI)) {
            holder.bindRssi(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: ScannedDevice) {
            binding.deviceName.text = device.name
            binding.deviceMeta.text = "${device.address} · ${device.rssi} dBm"
            binding.bindButton.setOnClickListener { onBindClick(device) }
        }

        fun bindRssi(device: ScannedDevice) {
            binding.deviceMeta.text = "${device.address} · ${device.rssi} dBm"
        }
    }

    private object Diff : DiffUtil.ItemCallback<ScannedDevice>() {
        override fun areItemsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice) =
            oldItem.address == newItem.address

        override fun areContentsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice) =
            oldItem.address == newItem.address &&
                oldItem.name == newItem.name &&
                oldItem.rssi == newItem.rssi

        override fun getChangePayload(oldItem: ScannedDevice, newItem: ScannedDevice): Any? {
            return if (oldItem.address == newItem.address &&
                oldItem.name == newItem.name &&
                oldItem.rssi != newItem.rssi
            ) {
                PAYLOAD_RSSI
            } else {
                null
            }
        }
    }

    companion object {
        private const val PAYLOAD_RSSI = "rssi"
    }
}
