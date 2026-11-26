package com.willianswill.novatv.feature.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ItemMediaPosterBinding

data class HomeCarouselItem(
    val playable: PlayableItem,
    val progressPercent: Int = 0
)

class HomeCarouselAdapter(
    private val onClick: (List<PlayableItem>, Int) -> Unit
) : RecyclerView.Adapter<HomeCarouselAdapter.VH>() {

    private var items: List<HomeCarouselItem> = emptyList()

    fun submitList(list: List<HomeCarouselItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.enableCinemaFocusZoom(1.06f)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemMediaPosterBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: HomeCarouselItem, pos: Int) {
            val p = entry.playable
            binding.tvPosterTitle.text = p.title
            binding.tvPosterSub.text = p.subtitle

            if (entry.progressPercent > 0) {
                binding.pbWatch.visibility = View.VISIBLE
                binding.pbWatch.progress = entry.progressPercent
            } else {
                binding.pbWatch.visibility = View.GONE
            }

            if (p.iconUrl.isNotBlank()) {
                Glide.with(binding.ivPoster.context)
                    .load(p.iconUrl)
                    .placeholder(R.drawable.ic_tv_logo)
                    .error(R.drawable.ic_tv_logo)
                    .into(binding.ivPoster)
            } else {
                binding.ivPoster.setImageResource(R.drawable.ic_tv_logo)
            }

            binding.root.setOnClickListener {
                onClick(items.map { it.playable }, pos)
            }
        }
    }
}
