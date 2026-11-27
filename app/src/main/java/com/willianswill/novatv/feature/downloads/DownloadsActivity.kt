package com.willianswill.novatv.feature.downloads

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.downloads.DownloadStatus
import com.willianswill.novatv.core.downloads.OfflineDownloadItem
import com.willianswill.novatv.core.downloads.OfflineDownloadManager
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityDownloadsBinding
import com.willianswill.novatv.databinding.ItemDownloadBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.backgroundTintList = null
        binding.btnBack.enableCinemaFocusZoom(1.06f)
        binding.btnBack.setOnClickListener { finish() }

        adapter = DownloadsAdapter(
            onPlay = { item -> playOffline(item) },
            onDelete = { item -> confirmDelete(item) }
        )
        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter

        OfflineDownloadManager.ensureLoaded(this)
        lifecycleScope.launch {
            OfflineDownloadManager.downloadsFlow.collectLatest { list ->
                adapter.submitList(list)
                binding.tvDownloadsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvDownloads.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        binding.btnBack.post { binding.btnBack.requestFocus() }
    }

    private fun playOffline(item: OfflineDownloadItem) {
        val file = File(item.localPath)
        if (item.status != DownloadStatus.COMPLETED || !file.exists()) {
            Toast.makeText(this, "Aguarde o download concluir.", Toast.LENGTH_SHORT).show()
            return
        }
        val playable = PlayableItem(
            contentKey = item.streamUrl,
            title = item.title,
            subtitle = "Offline • ${item.subtitle}".trimEnd(' ', '•'),
            streamUrl = Uri.fromFile(file).toString(),
            iconUrl = item.iconUrl,
            mediaType = item.mediaType
        )
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_ITEMS, arrayListOf(playable))
                putExtra(PlayerActivity.EXTRA_INDEX, 0)
            }
        )
    }

    private fun confirmDelete(item: OfflineDownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Excluir download")
            .setMessage("Remover \"${item.title}\" do aparelho?")
            .setPositiveButton("Excluir") { _, _ ->
                OfflineDownloadManager.deleteDownload(this, item.id)
                Toast.makeText(this, "Download removido.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private class DownloadsAdapter(
        private val onPlay: (OfflineDownloadItem) -> Unit,
        private val onDelete: (OfflineDownloadItem) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

        private var items: List<OfflineDownloadItem> = emptyList()

        fun submitList(list: List<OfflineDownloadItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: OfflineDownloadItem) {
                b.tvDlTitle.text = item.title
                if (item.iconUrl.isNotBlank()) {
                    Glide.with(b.ivDlPoster.context)
                        .load(item.iconUrl)
                        .placeholder(R.drawable.ic_tv_logo)
                        .error(R.drawable.ic_tv_logo)
                        .into(b.ivDlPoster)
                } else {
                    b.ivDlPoster.setImageResource(R.drawable.ic_tv_logo)
                }

                val mbDone = String.format(Locale.getDefault(), "%.1f MB", item.downloadedBytes / (1024.0 * 1024.0))
                val mbTotal = if (item.totalBytes > 0L) {
                    String.format(Locale.getDefault(), "%.1f MB", item.totalBytes / (1024.0 * 1024.0))
                } else ""
                val pct = if (item.totalBytes > 0L) {
                    ((item.downloadedBytes * 100L) / item.totalBytes).toInt().coerceIn(0, 100)
                } else 0

                when (item.status) {
                    DownloadStatus.COMPLETED -> {
                        b.pbDlProgress.visibility = View.GONE
                        b.tvDlStatus.text = "✓ Baixado ($mbDone) • Pronto offline"
                        b.btnDlPlay.visibility = View.VISIBLE
                    }
                    DownloadStatus.DOWNLOADING -> {
                        b.pbDlProgress.visibility = View.VISIBLE
                        b.pbDlProgress.isIndeterminate = item.totalBytes <= 0L
                        b.pbDlProgress.progress = pct
                        b.tvDlStatus.text = if (mbTotal.isNotBlank()) "⬇ Baixando $pct% ($mbDone / $mbTotal)" else "⬇ Baixando ($mbDone)..."
                        b.btnDlPlay.visibility = View.GONE
                    }
                    DownloadStatus.QUEUED -> {
                        b.pbDlProgress.visibility = View.VISIBLE
                        b.pbDlProgress.isIndeterminate = true
                        b.tvDlStatus.text = "⏳ Na fila para baixar..."
                        b.btnDlPlay.visibility = View.GONE
                    }
                    else -> {
                        b.pbDlProgress.visibility = View.GONE
                        b.tvDlStatus.text = "✖ Falha: ${item.errorMessage.ifBlank { "erro ao baixar" }}"
                        b.btnDlPlay.visibility = View.GONE
                    }
                }

                b.btnDlPlay.backgroundTintList = null
                b.btnDlDelete.backgroundTintList = null
                b.root.enableCinemaFocusZoom(1.02f)
                b.btnDlPlay.enableCinemaFocusZoom(1.06f)
                b.btnDlDelete.enableCinemaFocusZoom(1.06f)

                b.root.setOnClickListener {
                    if (item.status == DownloadStatus.COMPLETED) onPlay(item)
                }
                b.btnDlPlay.setOnClickListener { onPlay(item) }
                b.btnDlDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
