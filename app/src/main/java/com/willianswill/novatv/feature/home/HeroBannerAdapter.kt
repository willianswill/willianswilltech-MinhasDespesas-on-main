package com.willianswill.novatv.feature.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.entity.SeriesEntity
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ItemHeroBannerBinding

sealed class FeaturedBannerItem {
    data class MovieBanner(
        val playable: PlayableItem,
        val rating: String,
        val timestamp: Long
    ) : FeaturedBannerItem()

    data class SeriesBanner(
        val series: SeriesEntity,
        val timestamp: Long
    ) : FeaturedBannerItem()
}

class HeroBannerAdapter(
    private val onBannerClick: (FeaturedBannerItem) -> Unit
) : RecyclerView.Adapter<HeroBannerAdapter.VH>() {

    private var items: List<FeaturedBannerItem> = emptyList()

    fun submitList(list: List<FeaturedBannerItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHeroBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.btnBannerAction.enableCinemaFocusZoom(1.05f)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemHeroBannerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FeaturedBannerItem) {
            val coverUrl: String
            when (item) {
                is FeaturedBannerItem.MovieBanner -> {
                    val p = item.playable
                    binding.tvBannerBadge.text = "✨ LANÇAMENTO • FILME"
                    binding.tvBannerTitle.text = p.title
                    binding.tvBannerSub.text = if (item.rating.isNotBlank() && item.rating != "0") {
                        "${p.subtitle} • ★ ${item.rating}"
                    } else p.subtitle
                    binding.btnBannerAction.text = "▶ Assistir Filme"
                    coverUrl = p.iconUrl
                }
                is FeaturedBannerItem.SeriesBanner -> {
                    val s = item.series
                    binding.tvBannerBadge.text = "✨ LANÇAMENTO • SÉRIE"
                    binding.tvBannerTitle.text = s.name
                    binding.tvBannerSub.text = if (s.rating.isNotBlank() && s.rating != "0") {
                        "${s.categoryName} • ★ ${s.rating}"
                    } else s.categoryName
                    binding.btnBannerAction.text = "▶ Ver Temporadas"
                    coverUrl = s.coverUrl
                }
            }

            if (coverUrl.isNotBlank()) {
                Glide.with(binding.ivBannerBg.context)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_tv_logo)
                    .error(R.drawable.ic_tv_logo)
                    .into(binding.ivBannerBg)
            } else {
                binding.ivBannerBg.setImageResource(R.drawable.ic_tv_logo)
            }

            binding.root.setOnClickListener { onBannerClick(item) }
            binding.btnBannerAction.setOnClickListener { onBannerClick(item) }
        }
    }
}
