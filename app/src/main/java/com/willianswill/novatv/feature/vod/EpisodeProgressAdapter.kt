package com.willianswill.novatv.feature.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.databinding.ItemEpisodeBinding

data class EpisodeUiEntry(
    val playable: PlayableItem,
    val progressPercent: Int
)

class EpisodeProgressAdapter(
    private val items: List<EpisodeUiEntry>,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<EpisodeProgressAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val p = entry.playable
        holder.binding.tvEpTitle.text = if (p.subtitle.isNotBlank()) "${p.title} - ${p.subtitle}" else p.title

        if (entry.progressPercent > 0) {
            holder.binding.pbEpProgress.visibility = View.VISIBLE
            holder.binding.pbEpProgress.progress = entry.progressPercent
            holder.binding.tvEpStatus.visibility = View.VISIBLE
            holder.binding.tvEpStatus.text = if (entry.progressPercent >= 95) "✓ Assistido" else "${entry.progressPercent}%"
        } else {
            holder.binding.pbEpProgress.visibility = View.VISIBLE
            holder.binding.pbEpProgress.progress = 0
            holder.binding.tvEpStatus.visibility = View.GONE
        }

        holder.binding.root.setOnClickListener { onSelect(position) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
}
