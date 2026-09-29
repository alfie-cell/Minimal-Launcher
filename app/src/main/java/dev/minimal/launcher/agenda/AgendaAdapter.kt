package dev.minimal.launcher.agenda

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.minimal.launcher.R
import java.time.ZoneId
import java.util.Locale

class AgendaAdapter(
    private val words: AgendaWords,
    private val onClick: (AgendaItem) -> Unit,
) : RecyclerView.Adapter<AgendaAdapter.Holder>() {

    private var items: List<AgendaItem> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AgendaItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_agenda, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val label = AgendaFormat.label(
            item, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault(),
            DateFormat.is24HourFormat(context), words,
        )
        holder.item = item
        holder.label.text = label
        holder.title.text = if (item.repeating) "${item.title}  ↻" else item.title
        holder.dot.backgroundTintList = ColorStateList.valueOf(item.color or 0xFF000000.toInt())
        holder.itemView.contentDescription = "$label, ${item.title}"
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.agenda_dot)
        val label: TextView = view.findViewById(R.id.agenda_label)
        val title: TextView = view.findViewById(R.id.agenda_title)
        var item: AgendaItem? = null

        init {
            view.setOnClickListener { item?.let(onClick) }
        }
    }
}
