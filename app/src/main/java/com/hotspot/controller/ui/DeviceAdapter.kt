package com.hotspot.controller.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hotspot.controller.R
import com.hotspot.controller.databinding.ItemDeviceBinding
import com.hotspot.controller.model.ConnectedDevice

class DeviceAdapter(
    private val context: Context,
    private val onSpeedLimitChanged: (clientIp: String, limitKbps: Int) -> Unit,
    private val onBlockStatusChanged: (clientIp: String, isBlocked: Boolean) -> Unit,
    private val onRenameDevice: (clientIp: String, newName: String) -> Unit,
    private val onDeleteDevice: (clientIp: String) -> Unit,
    private val onShowSetupGuide: (clientIp: String) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<ConnectedDevice>()

    fun submitList(newDevices: List<ConnectedDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position], position)
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: ConnectedDevice, index: Int) {
            val displayName = device.getDisplayName(index)
            binding.tvDeviceName.text = displayName

            // Device OS & Icon (Android, Windows, Mac, iOS, etc.)
            val osLabel = device.getOsLabel()
            binding.tvDeviceCategoryBadge.text = osLabel
            binding.ivDeviceIcon.setImageResource(device.getDeviceCategoryIcon())

            when (osLabel) {
                "Android" -> {
                    binding.tvDeviceCategoryBadge.setTextColor(ContextCompat.getColor(context, R.color.status_green))
                    binding.ivDeviceIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_green))
                }
                "Windows" -> {
                    binding.tvDeviceCategoryBadge.setTextColor(ContextCompat.getColor(context, R.color.accent))
                    binding.ivDeviceIcon.setColorFilter(ContextCompat.getColor(context, R.color.accent))
                }
                "Mac", "iOS" -> {
                    binding.tvDeviceCategoryBadge.setTextColor(ContextCompat.getColor(context, R.color.primary_light))
                    binding.ivDeviceIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary_light))
                }
                else -> {
                    binding.tvDeviceCategoryBadge.setTextColor(ContextCompat.getColor(context, R.color.status_amber))
                    binding.ivDeviceIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_amber))
                }
            }

            // MAC Address: ONLY show MAC address if fetched and valid!
            if (device.hasValidMac()) {
                val vendorInfo = if (device.manufacturerVendor.isNotBlank()) " (${device.manufacturerVendor})" else ""
                binding.tvDeviceIpMac.text = "${device.ipAddress} • MAC: ${device.macAddress}$vendorInfo"
            } else {
                binding.tvDeviceIpMac.text = device.ipAddress
            }

            // Online / Offline badge
            if (device.isOnline) {
                binding.tvOnlineBadge.text = "ONLINE"
                binding.tvOnlineBadge.setBackgroundResource(R.drawable.bg_badge_allowed)
                binding.tvOnlineBadge.setTextColor(ContextCompat.getColor(context, R.color.status_green))
            } else {
                binding.tvOnlineBadge.text = "OFFLINE"
                binding.tvOnlineBadge.setBackgroundResource(R.drawable.bg_pill)
                binding.tvOnlineBadge.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            }

            // Routing status badge
            val isRooted = com.hotspot.controller.network.RootFirewallHelper.isDeviceRooted()
            if (device.isProxyRouted) {
                binding.tvRoutingBadge.text = "● Controlled (8080 Active)"
                binding.tvRoutingBadge.setBackgroundResource(R.drawable.bg_badge_allowed)
                binding.tvRoutingBadge.setTextColor(ContextCompat.getColor(context, R.color.status_green))
                binding.tvRoutingBadge.setOnClickListener(null)
            } else if (isRooted) {
                binding.tvRoutingBadge.text = "● Hardware Governed (Root)"
                binding.tvRoutingBadge.setBackgroundResource(R.drawable.bg_badge_allowed)
                binding.tvRoutingBadge.setTextColor(ContextCompat.getColor(context, R.color.accent))
                binding.tvRoutingBadge.setOnClickListener(null)
            } else {
                binding.tvRoutingBadge.text = "Direct Hotspot (Tap to Setup)"
                binding.tvRoutingBadge.setBackgroundResource(R.drawable.bg_pill)
                binding.tvRoutingBadge.setTextColor(ContextCompat.getColor(context, R.color.accent))
                binding.tvRoutingBadge.setOnClickListener {
                    onShowSetupGuide(device.ipAddress)
                }
            }

            // Internet Pause / Resume switch
            binding.switchInternetAccess.setOnCheckedChangeListener(null)
            binding.switchInternetAccess.isChecked = !device.isBlocked
            binding.switchInternetAccess.setOnCheckedChangeListener { _, isChecked ->
                onBlockStatusChanged(device.ipAddress, !isChecked)
            }

            // Delete button
            binding.btnDeleteDevice.setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("Remove Device")
                    .setMessage("Remove ${device.ipAddress} ($displayName) from device list?")
                    .setPositiveButton("Remove") { _, _ ->
                        onDeleteDevice(device.ipAddress)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            // Connection time & Data usage
            binding.tvDeviceConnectedTime.text = device.getFormattedConnectionTime()
            binding.tvDeviceTotalData.text = device.getFormattedTotalData()
            binding.tvDeviceDataBreakdown.text = device.getFormattedDataBreakdown()

            // Live speed
            val speedKbps = device.currentDownSpeedKbps
            binding.tvDeviceLiveSpeed.text = if (speedKbps >= 1024) {
                String.format("%.1f Mbps", speedKbps / 1024.0)
            } else {
                String.format("%.0f Kbps", speedKbps)
            }

            // Speed Limit label
            binding.tvDeviceSpeedLimitValue.text = if (device.speedLimitKbps <= 0) {
                "Unlimited"
            } else if (device.speedLimitKbps >= 1024) {
                "${device.speedLimitKbps / 1024} Mbps"
            } else {
                "${device.speedLimitKbps} Kbps"
            }

            // Highlight active speed button
            updateSpeedButtons(device.speedLimitKbps)

            binding.btnDevSpeedUnlimited.setOnClickListener {
                onSpeedLimitChanged(device.ipAddress, 0)
            }
            binding.btnDevSpeed256k.setOnClickListener {
                onSpeedLimitChanged(device.ipAddress, 256)
            }
            binding.btnDevSpeed512k.setOnClickListener {
                onSpeedLimitChanged(device.ipAddress, 512)
            }
            binding.btnDevSpeed1m.setOnClickListener {
                onSpeedLimitChanged(device.ipAddress, 1024)
            }
            binding.btnDevSpeed2m.setOnClickListener {
                onSpeedLimitChanged(device.ipAddress, 2048)
            }

            // Rename dialog
            binding.btnEditName.setOnClickListener {
                showRenameDialog(device, index)
            }
        }

        private fun updateSpeedButtons(currentLimit: Int) {
            fun highlight(btn: MaterialButton, active: Boolean) {
                if (active) {
                    btn.setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
                    btn.setTextColor(ContextCompat.getColor(context, R.color.white))
                } else {
                    btn.setBackgroundColor(ContextCompat.getColor(context, R.color.transparent))
                    btn.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
            }

            highlight(binding.btnDevSpeedUnlimited, currentLimit <= 0)
            highlight(binding.btnDevSpeed256k, currentLimit == 256)
            highlight(binding.btnDevSpeed512k, currentLimit == 512)
            highlight(binding.btnDevSpeed1m, currentLimit == 1024)
            highlight(binding.btnDevSpeed2m, currentLimit == 2048)
        }

        private fun showRenameDialog(device: ConnectedDevice, index: Int) {
            val initial = if (device.customName.isNotBlank()) device.customName else ""
            val input = EditText(context).apply {
                setText(initial)
                hint = "e.g. Work Laptop"
                setSelection(text.length)
                setPadding(50, 40, 50, 40)
            }

            AlertDialog.Builder(context)
                .setTitle("Rename Device (D${index + 1})")
                .setMessage("Enter custom nickname for ${device.ipAddress} (leave empty to reset to D${index + 1}):")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newName = input.text.toString().trim()
                    onRenameDevice(device.ipAddress, newName)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
