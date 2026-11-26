package com.willianswill.novatv.core.player

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.novatv.R

class PlayerChannelOverlayAdapter(
    private val items: List<PlayableItem>,
    private var selectedIndex: Int,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<PlayerChannelOverlayAdapter.VH>() {

    fun updateSelected(index: Int) {
        selectedIndex = index
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_tab, parent, false) as TextView
        tv.layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 6
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isCurrent = position == selectedIndex
        holder.tv.text = if (isCurrent) "▶ ${item.title}" else item.title
        holder.tv.isSelected = isCurrent
        holder.tv.setTextColor(if (isCurrent) Color.parseColor("#38BDF8") else Color.WHITE)
        holder.tv.setOnClickListener { onSelect(position) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
