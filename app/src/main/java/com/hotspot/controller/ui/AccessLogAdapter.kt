package com.hotspot.controller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hotspot.controller.R
import com.hotspot.controller.databinding.ItemAccessLogBinding
import com.hotspot.controller.model.AccessLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccessLogAdapter : RecyclerView.Adapter<AccessLogAdapter.LogViewHolder>() {

    private val logs = mutableListOf<AccessLogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submitList(newLogs: List<AccessLogEntry>) {
        logs.clear()
        logs.addAll(newLogs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemAccessLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    inner class LogViewHolder(private val binding: ItemAccessLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AccessLogEntry) {
            val context = binding.root.context
            if (entry.isBlocked) {
                binding.tvLogStatusBadge.text = "BLOCKED"
                binding.tvLogStatusBadge.setBackgroundResource(R.drawable.bg_badge_blocked)
                binding.tvLogStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_red))
            } else {
                binding.tvLogStatusBadge.text = "ALLOWED"
                binding.tvLogStatusBadge.setBackgroundResource(R.drawable.bg_badge_allowed)
                binding.tvLogStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_green))
            }

            binding.tvLogClientIp.text = entry.clientIp
            binding.tvLogHost.text = entry.targetHost
            binding.tvLogTime.text = timeFormat.format(Date(entry.timestamp))
        }
    }
}
