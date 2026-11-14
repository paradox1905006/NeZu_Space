package com.dparadox.tabspace

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ProfileAdapter(
    private val profiles: List<AppProfile>,
    private val currentId: () -> String?,
    private val tabCount: (AppProfile) -> Int,
    private val onClick: (AppProfile) -> Unit,
    private val onLongClick: (AppProfile, View) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.profileCard)
        val avatar: ImageView = v.findViewById(R.id.profileAvatar)
        val initial: TextView = v.findViewById(R.id.profileInitial)
        val name: TextView = v.findViewById(R.id.profileName)
        val tabCountView: TextView = v.findViewById(R.id.profileTabCount)
        val check: ImageView = v.findViewById(R.id.profileCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

    override fun getItemCount() = profiles.size

    fun profileAt(pos: Int): AppProfile? = profiles.getOrNull(pos)

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = profiles[pos]
        h.itemView.alpha = 1f
        h.itemView.translationX = 0f
        h.name.text = p.name
        val n = tabCount(p)
        h.tabCountView.text = "$n ${if (n == 1) "tab" else "tabs"}"
        // Current profile: tonal "selected" card + a ring in the profile colour + check mark.
        val isCurrent = p.id == currentId()
        val density = h.itemView.resources.displayMetrics.density
        h.card.setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(h.card,
            if (isCurrent) com.google.android.material.R.attr.colorSecondaryContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHighest))
        h.card.strokeWidth = if (isCurrent) (2 * density).toInt() else 0
        h.card.strokeColor = p.color
        h.check.visibility = if (isCurrent) View.VISIBLE else View.GONE

        if (p.avatarUri != null) {
            h.avatar.visibility = View.VISIBLE
            h.initial.visibility = View.GONE
            runCatching { h.avatar.setImageURI(Uri.parse(p.avatarUri)) }
        } else {
            h.avatar.visibility = View.GONE
            h.initial.visibility = View.VISIBLE
            val fg = if (ColorUtils.calculateLuminance(p.color) > 0.5) Color.BLACK else Color.WHITE
            h.initial.text = p.name.firstOrNull()?.uppercase() ?: "P"
            h.initial.setTextColor(fg)
            h.initial.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(p.color)
            }
        }

        h.card.setOnClickListener { onClick(p) }
        h.card.setOnLongClickListener { onLongClick(p, h.itemView); true }
    }
}
