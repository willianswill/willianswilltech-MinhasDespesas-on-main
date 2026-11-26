package com.willianswill.novatv.feature.livetv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.novatv.core.database.entity.CategoryEntity
import com.willianswill.novatv.databinding.ItemCategoryTabBinding

class CategoryTabAdapter(
    private val onSelect: (CategoryEntity?) -> Unit
) : RecyclerView.Adapter<CategoryTabAdapter.VH>() {

    private var items: List<CategoryEntity?> = emptyList() // null = Todos, categoryId="__FAV__" = Favoritos
    private var selectedKey: String? = null

    fun submitCategories(list: List<CategoryEntity>, includeFavoritesTab: Boolean = true) {
        val full = mutableListOf<CategoryEntity?>(null)
        if (includeFavoritesTab) {
            full.add(CategoryEntity(profileId = 0, categoryId = "__FAV__", name = "★ Favoritos", mediaType = ""))
        }
        full.addAll(list)
        items = full
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCategoryTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val key = item?.categoryId
        holder.binding.tvCatName.text = item?.name ?: "Todos"
        holder.binding.tvCatName.isSelected = key == selectedKey
        holder.binding.tvCatName.setOnClickListener {
            selectedKey = key
            notifyDataSetChanged()
            onSelect(item)
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemCategoryTabBinding) : RecyclerView.ViewHolder(binding.root)
}
