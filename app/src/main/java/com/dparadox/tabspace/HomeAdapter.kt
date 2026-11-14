package com.dparadox.tabspace

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/** Home screen grid: one tile per bookmark (site logo + name) and a final "+" tile. */
class HomeAdapter(
    private val onOpen: (Bookmark) -> Unit,
    private val onMenu: (Bookmark, View) -> Unit,
    private val onAdd: () -> Unit,
) : RecyclerView.Adapter<HomeAdapter.VH>() {

    private var items: MutableList<Bookmark> = mutableListOf()

    fun submit(list: List<Bookmark>) {
        items = list.toMutableList()
        notifyDataSetChanged()
    }

    fun remove(bm: Bookmark) {
        val i = items.indexOf(bm)
        if (i < 0) return
        items.removeAt(i)
        notifyItemRemoved(i)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: View = v.findViewById(R.id.tileBox)
        val icon: ImageView = v.findViewById(R.id.tileIcon)
        val letter: TextView = v.findViewById(R.id.tileLetter)
        val name: TextView = v.findViewById(R.id.tileName)
    }

    override fun getItemCount() = items.size + 1
    override fun getItemViewType(position: Int) = if (position == items.size) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_tile, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ctx = h.itemView.context
        h.itemView.alpha = 1f
        h.itemView.translationX = 0f
        h.box.clipToOutline = true
        h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        // Material You launcher-style tile: rounded square ("squircle") in a tonal surface colour.
        val radius = 22f * ctx.resources.displayMetrics.density
        h.box.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(MaterialColors.getColor(h.itemView,
                com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }

        if (position == items.size) {
            val pad = (18 * ctx.resources.displayMetrics.density).toInt()
            h.box.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(MaterialColors.getColor(h.itemView,
                    com.google.android.material.R.attr.colorPrimaryContainer))
            }
            h.icon.setPadding(pad, pad, pad, pad)
            h.icon.setImageResource(R.drawable.ic_add)
            ImageViewCompat.setImageTintList(h.icon, android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorOnPrimaryContainer)))
            h.letter.visibility = View.GONE
            h.name.text = "Add"
            h.itemView.setOnClickListener { onAdd() }
            h.itemView.setOnLongClickListener(null)
            h.itemView.isLongClickable = false
            return
        }

        val bm = items[position]
        val host = runCatching { Uri.parse(bm.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        h.name.text = bm.title.ifBlank { host.ifBlank { bm.url } }
        ImageViewCompat.setImageTintList(h.icon, null)
        val smallPad = (12 * ctx.resources.displayMetrics.density).toInt()

        val custom = bm.iconUri
        val logo = if (custom == null && host.isNotBlank()) FaviconStore.get(ctx, host) else null
        when {
            custom != null -> {
                // Own picture: fills the whole round tile.
                h.icon.setPadding(0, 0, 0, 0)
                h.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                h.icon.setImageDrawable(null)
                runCatching { h.icon.setImageURI(Uri.parse(custom)) }
                h.letter.visibility = View.GONE
            }
            logo != null -> {
                val big = logo.width >= 96            // a real logo fills the circle, a tiny favicon sits in the middle
                val pad = if (big) 0 else smallPad
                h.icon.setPadding(pad, pad, pad, pad)
                h.icon.scaleType = if (big) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                h.icon.setImageBitmap(logo)
                h.letter.visibility = View.GONE
                // Old tiles may still hold a small icon: look once for a better one.
                if (!big) FaviconStore.fetch(ctx, host, bm.url, onlyIfBigger = true) {
                    val i = items.indexOf(bm)
                    if (i >= 0) notifyItemChanged(i)
                }
            }
            else -> {
                h.box.background = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(MaterialColors.getColor(h.itemView,
                        com.google.android.material.R.attr.colorSecondaryContainer))
                }
                h.icon.setPadding(smallPad, smallPad, smallPad, smallPad)
                h.icon.setImageDrawable(null)
                h.letter.visibility = View.VISIBLE
                h.letter.text = (bm.title.ifBlank { host }).firstOrNull()?.uppercase() ?: "?"
                if (host.isNotBlank()) FaviconStore.fetch(ctx, host, bm.url) {
                    val i = items.indexOf(bm)
                    if (i >= 0) notifyItemChanged(i)
                }
            }
        }

        h.itemView.setOnClickListener { onOpen(bm) }
        h.itemView.setOnLongClickListener { onMenu(bm, h.itemView); true }
    }
}
