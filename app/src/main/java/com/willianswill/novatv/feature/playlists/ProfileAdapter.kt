package com.willianswill.novatv.feature.playlists

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.databinding.ItemProfileBinding

class ProfileAdapter(
    private val onActivate: (ProfileEntity) -> Unit,
    private val onSync: (ProfileEntity) -> Unit,
    private val onDelete: (ProfileEntity) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    private var items: List<ProfileEntity> = emptyList()

    fun submitList(list: List<ProfileEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: ProfileEntity) {
            val activeTag = if (profile.isActive) "✓ [ATIVO] " else ""
            binding.tvProfileName.text = "$activeTag${profile.name}"
            binding.tvProfileMeta.text =
                "${profile.type} • ${profile.totalChannels} canais • ${profile.totalMovies} filmes • ${profile.totalSeries} séries"

            binding.root.setOnClickListener { onActivate(profile) }
            binding.btnProfileSync.setOnClickListener { onSync(profile) }
            binding.btnProfileDelete.setOnClickListener { onDelete(profile) }
        }
    }
}
