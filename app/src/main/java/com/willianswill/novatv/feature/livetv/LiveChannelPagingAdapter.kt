package com.willianswill.novatv.feature.livetv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.databinding.ItemLiveChannelBinding

class LiveChannelPagingAdapter(
    private val onPreviewOrPlay: (StreamEntity, Int) -> Unit,
    private val onToggleFav: (StreamEntity) -> Unit
) : PagingDataAdapter<StreamEntity, LiveChannelPagingAdapter.VH>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<StreamEntity>() {
        override fun areItemsTheSame(oldItem: StreamEntity, newItem: StreamEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: StreamEntity, newItem: StreamEntity): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLiveChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item, position)
    }

    inner class VH(private val binding: ItemLiveChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StreamEntity, pos: Int) {
            binding.tvName.text = item.baseName
            binding.tvGroup.text = item.categoryName
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

            binding.root.setOnClickListener { onPreviewOrPlay(item, pos) }
            binding.btnFav.setOnClickListener { onToggleFav(item) }
        }
    }
}
