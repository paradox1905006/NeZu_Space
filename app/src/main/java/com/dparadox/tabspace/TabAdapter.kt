package com.dparadox.tabspace

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dparadox.tabspace.databinding.ItemTabRowBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.RelativeCornerSize

/** One row per tab: icon, title, close button. Tap opens, long press shows options. */
class TabAdapter(
    private val onOpen: (Tab) -> Unit,
    private val onClose: (Tab, View) -> Unit,
    private val onMenu: (Tab) -> Unit,
) : RecyclerView.Adapter<TabAdapter.VH>() {

    /** What a row shows. Used to find out which rows changed. */
    private data class Row(
        val id: String, val name: String, val color: Int, val title: String,
        val url: String, val pinned: Boolean, val avatar: String?, val current: Boolean,
        val home: Boolean
    )

    private var items: List<Tab> = emptyList()
    private var rows: List<Row> = emptyList()
    private var currentId: String? = null

    fun tabAt(pos: Int): Tab? = items.getOrNull(pos)
    fun positionOf(id: String?): Int = items.indexOfFirst { it.id == id }

    fun submit(list: List<Tab>, current: String?) {
        val newRows = list.map {
            Row(it.id, it.name, it.color, it.title, it.url, it.pinned, it.avatarUri,
                it.id == current, it.homeShown)
        }
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newRows[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newRows[n]
        })
        items = list.toList()
        rows = newRows
        currentId = current
        diff.dispatchUpdatesTo(this)
    }

    class VH(val b: ItemTabRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(ItemTabRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        vh.b.icon.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(RelativeCornerSize(0.5f)).build()
        vh.b.root.clipToOutline = true
        return vh
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val tab = items[position]
        val b = h.b
        val ctx = b.root.context
        val isCurrent = tab.id == currentId
        b.root.alpha = 1f
        b.root.translationX = 0f

        val onSurface = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
        val onSelected = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val selectedBg = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSecondaryContainer)
        val rowBg = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val host = runCatching { Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.")?.ifBlank { null }
        val isHome = tab.homeShown || tab.url.isBlank()

        // Title
        b.title.text = when {
            isHome                  -> "NeZu Space"
            tab.title.isNotBlank()  -> tab.title
            else                    -> host ?: tab.url
        }
        b.title.setTextColor(if (isCurrent) onSelected else onSurface)
        b.subtitle.text = if (isHome || host == null) tab.name else "${tab.name} · $host"

        // Rounded row; the open tab gets the tonal "selected" colour (Material 3 list highlight).
        b.root.background = GradientDrawable().apply {
            cornerRadius = 20f * ctx.resources.displayMetrics.density
            setColor(if (isCurrent) selectedBg else (rowBg and 0x00FFFFFF) or (0x99 shl 24))
        }

        // Icon: avatar > cached site icon > coloured circle with the first letter
        var icon: android.graphics.Bitmap? = null
        if (tab.avatarUri == null && !isHome && host != null) icon = FaviconStore.get(ctx, host)
        b.icon.background = null
        b.iconLetter.visibility = View.GONE
        when {
            tab.avatarUri != null -> {
                b.icon.setImageDrawable(null)
                runCatching { b.icon.setImageURI(Uri.parse(tab.avatarUri)) }
            }
            icon != null -> b.icon.setImageBitmap(icon)
            else -> {
                b.icon.setImageDrawable(null)
                b.icon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tab.color) }
                b.iconLetter.visibility = View.VISIBLE
                b.iconLetter.text = (if (isHome) "N" else (host ?: tab.name).take(1)).uppercase()
            }
        }

        b.pinMark.visibility = if (tab.pinned) View.VISIBLE else View.GONE
        b.close.visibility = if (tab.pinned) View.GONE else View.VISIBLE

        b.root.setOnClickListener { onOpen(tab) }
        b.root.setOnLongClickListener { onMenu(tab); true }
        b.close.setOnClickListener { onClose(tab, b.root) }
    }
}
