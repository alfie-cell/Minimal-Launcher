package dev.minimal.launcher

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val icons: IconCache,
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    var items: List<AppEntry> = emptyList()
        private set
    private var highlightFirst = false

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AppEntry>, highlightFirst: Boolean) {
        items = list
        this.highlightFirst = highlightFirst
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.entry = entry
        holder.label.text = entry.label
        holder.itemView.isActivated = highlightFirst && position == 0
        val cached = icons.peek(entry.key)
        if (cached != null) {
            holder.icon.setImageBitmap(cached)
        } else {
            holder.icon.setImageDrawable(null)
            icons.load(entry) { bitmap ->
                if (holder.entry === entry) holder.icon.setImageBitmap(bitmap)
            }
        }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
        var entry: AppEntry? = null

        init {
            view.setOnClickListener { v -> entry?.let { onClick(it, v) } }
            view.setOnLongClickListener { v -> entry?.let { onLongClick(it, v) }; true }
        }
    }
}
