package com.hotspot.controller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hotspot.controller.R
import com.hotspot.controller.databinding.ItemLiveRequestBinding
import com.hotspot.controller.model.AccessLogEntry

class LiveRequestAdapter : RecyclerView.Adapter<LiveRequestAdapter.LiveRequestViewHolder>() {

    private val requests = mutableListOf<AccessLogEntry>()

    fun submitList(newRequests: List<AccessLogEntry>) {
        requests.clear()
        requests.addAll(newRequests)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveRequestViewHolder {
        val binding = ItemLiveRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LiveRequestViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LiveRequestViewHolder, position: Int) {
        holder.bind(requests[position])
    }

    override fun getItemCount(): Int = requests.size

    inner class LiveRequestViewHolder(private val binding: ItemLiveRequestBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AccessLogEntry) {
            val context = binding.root.context

            binding.tvLiveProtocol.text = entry.protocol
            binding.tvLiveTargetHost.text = entry.targetHost
            binding.tvLiveDeviceName.text = if (entry.deviceName.isNotBlank()) entry.deviceName else "Client"
            binding.tvLiveClientIp.text = "(${entry.clientIp})"
            binding.tvLiveTimestamp.text = entry.formattedTime
        }
    }
}
