package com.willianswill.novatv.feature.admin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.novatv.core.network.ActiveSessionInfo
import com.willianswill.novatv.core.network.TelemetryManager
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityControlPanelBinding
import com.willianswill.novatv.databinding.ItemActiveDeviceBinding
import kotlinx.coroutines.launch

class ControlPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlPanelBinding
    private val adapter = ActiveDeviceAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            loadSessions()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadSessions() }
        binding.rvActiveDevices.layoutManager = LinearLayoutManager(this)
        binding.rvActiveDevices.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(autoRefresh)
        handler.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            try {
                val list = TelemetryManager.fetchActiveSessions(this@ControlPanelActivity)
                val tvCount = list.count { it.deviceType == "TV Box" }
                val mobCount = list.size - tvCount

                binding.tvCountTotal.text = list.size.toString()
                binding.tvCountTvbox.text = tvCount.toString()
                binding.tvCountMobile.text = mobCount.toString()
                binding.tvPanelStatus.text = if (list.isEmpty()) {
                    "Nenhum dispositivo ativo nos últimos 90 segundos."
                } else {
                    "Atualização automática a cada 10s"
                }
                adapter.submitList(list)
            } catch (e: Exception) {
                binding.tvPanelStatus.text = "Aviso: ${e.message}"
            }
        }
    }

    private class ActiveDeviceAdapter : RecyclerView.Adapter<ActiveDeviceAdapter.VH>() {
        private var items: List<ActiveSessionInfo> = emptyList()

        fun submitList(list: List<ActiveSessionInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemActiveDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.enableCinemaFocusZoom(1.02f)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            val icon = if (s.deviceType == "TV Box") "📺" else "📱"
            holder.b.tvDeviceTitle.text = "$icon ${s.deviceType} • Perfil: ${s.profileName}"

            val watchLabel = if (s.watchingTitle.isNotBlank()) {
                "▶ ${s.status}: ${s.watchingTitle}"
            } else {
                "🏠 ${s.status}"
            }
            holder.b.tvDeviceWatching.text = watchLabel

            val agoSec = ((System.currentTimeMillis() - s.lastSeenAt) / 1000L).coerceAtLeast(0L)
            holder.b.tvDeviceTime.text = if (agoSec <= 5L) "Agora" else "há ${agoSec}s"
            holder.b.tvDeviceMeta.text = "${s.deviceName} • ${s.appVersion}"
        }

        override fun getItemCount(): Int = items.size
        class VH(val b: ItemActiveDeviceBinding) : RecyclerView.ViewHolder(b.root)
    }
}
