package com.neuroskill.muserecorder.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.neuroskill.muserecorder.MainActivity
import com.neuroskill.muserecorder.R
import com.neuroskill.muserecorder.ble.ConnState
import com.neuroskill.muserecorder.ble.ConnStatus
import com.neuroskill.muserecorder.ble.Protocol
import com.neuroskill.muserecorder.ble.ScanItem
import com.neuroskill.muserecorder.core.MuseHub
import com.neuroskill.muserecorder.databinding.FragmentConnectBinding
import com.neuroskill.muserecorder.databinding.ItemDeviceBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    private val deviceAdapter = DeviceAdapter { MuseHub.connect(it) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.deviceList.layoutManager = LinearLayoutManager(requireContext())
        binding.deviceList.adapter = deviceAdapter

        binding.btnScan.setOnClickListener { startScan(deep = false) }
        binding.btnDeepScan.setOnClickListener { startScan(deep = true) }
        binding.btnDisconnect.setOnClickListener { MuseHub.disconnect() }

        val lastAddr = MuseHub.prefs.lastDeviceAddress
        if (lastAddr.isNotBlank()) {
            binding.lastDeviceCard.visibility = View.VISIBLE
            binding.lastDeviceName.text = MuseHub.prefs.lastDeviceName.ifBlank { lastAddr }
            binding.btnQuickConnect.setOnClickListener {
                ensurePermissions { MuseHub.connectAddress(lastAddr, MuseHub.prefs.lastDeviceName) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.conn.collect { render(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.scanItems.collect { deviceAdapter.submitList(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.battery.collect {
                binding.batteryText.text = if (it >= 0) getString(R.string.battery_value, it) else ""
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.deviceInfo.collect { renderInfo() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.caps.collect { renderInfo() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.errors.collect {
                binding.errorText.text = it ?: ""
                binding.errorText.visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MuseHub.logLines.collect { lines ->
                val ctx = context ?: return@collect
                // 最新在前，最多 20 条，按类别着色
                val sb = android.text.SpannableStringBuilder()
                for (l in lines.asReversed().take(20)) {
                    val start = sb.length
                    sb.append(l.time).append("  ").append(l.text).append('\n')
                    val colorRes = when (l.kind) {
                        com.neuroskill.muserecorder.ble.LogKind.OK -> R.color.good
                        com.neuroskill.muserecorder.ble.LogKind.WARN -> R.color.medium
                        com.neuroskill.muserecorder.ble.LogKind.ERROR -> R.color.bad
                        com.neuroskill.muserecorder.ble.LogKind.INFO -> R.color.hintGray
                    }
                    sb.setSpan(
                        android.text.style.ForegroundColorSpan(ContextCompat.getColor(ctx, colorRes)),
                        start, sb.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                binding.logText.text = sb
            }
        }
    }

    private fun startScan(deep: Boolean) {
        ensurePermissions {
            if (!MuseHub.ble.isBluetoothEnabled()) {
                Toast.makeText(requireContext(), R.string.bluetooth_off, Toast.LENGTH_LONG).show()
                return@ensurePermissions
            }
            binding.scanHint.visibility = View.GONE
            if (MuseHub.startScan(deep)) {
                binding.scanProgress.visibility = View.VISIBLE
                // 扫描结束后给出提示
                viewLifecycleOwner.lifecycleScope.launch {
                    while (MuseHub.conn.value.status == ConnStatus.SCANNING) delay(200)
                    binding.scanProgress.visibility = View.GONE
                    if (deviceAdapter.currentList.isEmpty()) {
                        binding.scanHint.text = getString(R.string.scan_empty_hint) +
                            if (Build.VERSION.SDK_INT < 31) "\n" + getString(R.string.scan_location_hint) else ""
                        binding.scanHint.visibility = View.VISIBLE
                    }
                }
            } else {
                Toast.makeText(requireContext(), R.string.need_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ensurePermissions(action: () -> Unit) {
        if (MuseHub.ble.hasScanPermission() && MuseHub.ble.hasConnectPermission()) {
            action()
        } else {
            (activity as? MainActivity)?.requestBlePermissions()
            Toast.makeText(requireContext(), R.string.need_permission, Toast.LENGTH_LONG).show()
        }
    }

    private fun render(s: ConnState) {
        val ctx = context ?: return
        val (text, colorRes) = when (s.status) {
            ConnStatus.IDLE -> R.string.status_idle to R.color.bad
            ConnStatus.SCANNING -> R.string.status_scanning to R.color.medium
            ConnStatus.CONNECTING -> R.string.status_connecting to R.color.medium
            ConnStatus.CONNECTED -> R.string.status_connected to R.color.medium
            ConnStatus.STREAMING -> R.string.status_streaming to R.color.good
            ConnStatus.RECONNECTING -> R.string.status_reconnecting to R.color.medium
            ConnStatus.DISCONNECTED -> R.string.status_disconnected to R.color.bad
        }
        binding.statusText.text = if (s.status == ConnStatus.RECONNECTING) {
            getString(text, s.attempt)
        } else getString(text)
        val dot = ContextCompat.getDrawable(ctx, R.drawable.dot)?.mutate()
        dot?.setTint(ContextCompat.getColor(ctx, colorRes))
        binding.statusDot.background = dot

        binding.protocolText.visibility =
            if (s.protocol != null) View.VISIBLE else View.GONE
        binding.protocolText.setText(
            if (s.protocol == Protocol.ATHENA) R.string.protocol_athena else R.string.protocol_legacy,
        )

        val connected = s.status != ConnStatus.IDLE && s.status != ConnStatus.DISCONNECTED
        binding.btnDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
        renderInfo()
    }

    private fun renderInfo() {
        val s = MuseHub.conn.value
        val info = MuseHub.deviceInfo.value
        val caps = MuseHub.caps.value
        if (s.name != null || info?.name != null) {
            binding.deviceInfoText.visibility = View.VISIBLE
            binding.deviceInfoText.text = getString(
                R.string.device_info_fmt,
                info?.name ?: s.name ?: getString(R.string.unknown),
                info?.firmware ?: getString(R.string.unknown),
                info?.serial ?: getString(R.string.unknown),
            )
        } else {
            binding.deviceInfoText.visibility = View.GONE
        }
        if (caps != null) {
            binding.capsText.visibility = View.VISIBLE
            binding.capsText.text = getString(
                R.string.caps_fmt,
                if (caps.accel) getString(R.string.cap_accel) else "",
                if (caps.gyro) getString(R.string.cap_gyro) else "",
                if (caps.ppg || caps.optics) getString(R.string.cap_ppg) else "",
            )
        } else {
            binding.capsText.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class DeviceAdapter(private val onClick: (ScanItem) -> Unit) :
        ListAdapter<ScanItem, DeviceAdapter.VH>(object : DiffUtil.ItemCallback<ScanItem>() {
            override fun areItemsTheSame(a: ScanItem, b: ScanItem) = a.address == b.address
            override fun areContentsTheSame(a: ScanItem, b: ScanItem) =
                a.name == b.name && a.rssi == b.rssi
        }) {

        class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.deviceName.text = item.name ?: "Muse-?"
            holder.binding.deviceAddress.text = item.address
            holder.binding.deviceRssi.text = "${item.rssi} dBm"
            holder.binding.root.setOnClickListener { onClick(item) }
        }
    }
}
