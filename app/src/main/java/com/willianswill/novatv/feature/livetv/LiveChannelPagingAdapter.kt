package com.willianswill.novatv.feature.livetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.entity.EpgProgramEntity
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ItemLiveChannelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class LiveChannelPagingAdapter(
    private val scope: CoroutineScope,
    private val lookupEpg: suspend (Long, String) -> EpgProgramEntity?,
    private val onPreviewOrPlay: (StreamEntity, Int) -> Unit,
    private val onToggleFav: (StreamEntity) -> Unit
) : PagingDataAdapter<StreamEntity, LiveChannelPagingAdapter.VH>(DiffCallback) {

    private val epgCache = ConcurrentHashMap<String, EpgProgramEntity>()

    object DiffCallback : DiffUtil.ItemCallback<StreamEntity>() {
        override fun areItemsTheSame(oldItem: StreamEntity, newItem: StreamEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: StreamEntity, newItem: StreamEntity): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLiveChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.enableCinemaFocusZoom(1.02f)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item, position)
    }

    inner class VH(private val binding: ItemLiveChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        private var epgJob: Job? = null

        fun bind(item: StreamEntity, pos: Int) {
            epgJob?.cancel()
            binding.tvName.text = item.baseName
            binding.tvGroup.text = item.categoryName
            binding.pbEpg.visibility = View.GONE
            binding.btnFav.text = if (item.isFavorite) "★" else "☆"

            if (item.iconUrl.isNotBlank()) {
                Glide.with(binding.ivLogo.context)
                    .load(item.iconUrl)
                    .placeholder(R.drawable.ic_tv_logo)
                    .error(R.drawable.ic_tv_logo)
                    .into(binding.ivLogo)
            } else {
                binding.ivLogo.setImageResource(R.drawable.ic_tv_logo)
            }

            if (item.epgChannelId.isNotBlank()) {
                val cached = epgCache[item.epgChannelId]
                val now = System.currentTimeMillis()
                if (cached != null && cached.endMs > now) {
                    applyEpgUi(cached, now)
                } else {
                    epgJob = scope.launch(Dispatchers.IO) {
                        val prog = lookupEpg(item.profileId, item.epgChannelId)
                        if (prog != null) {
                            epgCache[item.epgChannelId] = prog
                            withContext(Dispatchers.Main) {
                                applyEpgUi(prog, System.currentTimeMillis())
                            }
                        }
                    }
                }
            }

            binding.root.setOnClickListener { onPreviewOrPlay(item, pos) }
            binding.btnFav.setOnClickListener { onToggleFav(item) }
        }

        private fun applyEpgUi(prog: EpgProgramEntity, now: Long) {
            binding.tvGroup.text = "Agora: ${prog.title}"
            val dur = (prog.endMs - prog.startMs).coerceAtLeast(1L)
            val pct = (((now - prog.startMs) * 100) / dur).toInt().coerceIn(0, 100)
            binding.pbEpg.visibility = View.VISIBLE
            binding.pbEpg.progress = pct
        }
    }
}
