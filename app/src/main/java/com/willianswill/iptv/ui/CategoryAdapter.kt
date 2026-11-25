package com.willianswill.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.iptv.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onCategorySelected: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private var categories: List<String> = emptyList()
    private var selectedCategory: String = "Todos"

    fun submitCategories(list: List<String>, selected: String) {
        categories = list
        selectedCategory = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val category = categories[position]
        holder.bind(category, category == selectedCategory)
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(
        private val binding: ItemCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: String, isSelected: Boolean) {
            binding.tvCategoryName.text = category
            binding.root.isSelected = isSelected
            binding.root.setOnClickListener {
                selectedCategory = category
                notifyDataSetChanged()
                onCategorySelected(category)
            }
        }
    }
}
