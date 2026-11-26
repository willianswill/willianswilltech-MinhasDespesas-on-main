package com.willianswill.novatv.feature.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.entity.SeriesEntity
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ItemVodCardBinding

class SeriesPagingAdapter(
    private val onClick: (SeriesEntity) -> Unit
) : PagingDataAdapter<SeriesEntity, SeriesPagingAdapter.VH>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<SeriesEntity>() {
        override fun areItemsTheSame(oldItem: SeriesEntity, newItem: SeriesEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SeriesEntity, newItem: SeriesEntity): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVodCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.enableCinemaFocusZoom(1.06f)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item)
    }

    inner class VH(private val binding: ItemVodCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SeriesEntity) {
            binding.tvVodName.text = item.name
            binding.tvVodCat.text = item.categoryName
            if (item.rating.isNotBlank() && item.rating != "0") {
                binding.tvVodRating.visibility = View.VISIBLE
                binding.tvVodRating.text = "★ ${item.rating}"
            } else {
                binding.tvVodRating.visibility = View.GONE
            }

            if (item.coverUrl.isNotBlank()) {
                Glide.with(binding.ivVodCover.context)
                    .load(item.coverUrl)
                    .placeholder(R.drawable.ic_tv_logo)
                    .error(R.drawable.ic_tv_logo)
                    .into(binding.ivVodCover)
            } else {
                binding.ivVodCover.setImageResource(R.drawable.ic_tv_logo)
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
