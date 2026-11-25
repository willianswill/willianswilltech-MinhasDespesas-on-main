package com.willianswill.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.iptv.R
import com.willianswill.iptv.databinding.ItemChannelBinding
import com.willianswill.iptv.model.Channel

class ChannelAdapter(
    private val onChannelClick: (Channel, Int) -> Unit,
    private val onFavoriteToggle: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel, position: Int) {
            binding.tvChannelName.text = channel.name
            binding.tvChannelGroup.text = channel.group
            binding.btnItemFav.setImageResource(
                if (channel.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )

            if (channel.logo.isNotBlank()) {
                Glide.with(binding.ivChannelLogo.context)
                    .load(channel.logo)
                    .placeholder(R.drawable.ic_tv)
                    .error(R.drawable.ic_tv)
                    .into(binding.ivChannelLogo)
            } else {
                binding.ivChannelLogo.setImageResource(R.drawable.ic_tv)
            }

            binding.root.setOnClickListener {
                onChannelClick(channel, position)
            }

            binding.btnItemFav.setOnClickListener {
                onFavoriteToggle(channel)
            }
        }
    }
}
