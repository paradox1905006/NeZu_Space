package com.dparadox.tabspace

import android.app.DownloadManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadHistAdapter(
    private val onClick: (DownloadRecord) -> Unit,
    private val onToggle: (DownloadRecord) -> Unit,
) : RecyclerView.Adapter<DownloadHistAdapter.VH>() {

    private var items: List<DownloadRecord> = emptyList()

    fun submit(list: List<DownloadRecord>) {
        items = list.toList()
        notifyDataSetChanged()
    }

    /** Redraws the rows (progress changes while downloading). */
    fun refreshRows() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.dlhName)
        val info: TextView = v.findViewById(R.id.dlhInfo)
        val status: TextView = v.findViewById(R.id.dlhStatus)
        val toggle: Button = v.findViewById(R.id.dlhToggle)
        val bar: LinearProgressIndicator = v.findViewById(R.id.dlhBar)
    }

    private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_download_hist, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = items[pos]
        h.name.text = d.fileName

        val st = d.status
        val inProgress = st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING ||
                st == DownloadManager.STATUS_PAUSED
        val known = d.isEngine && d.totalBytes > 0
        val pct = if (known) (d.downloadedBytes * 100 / d.totalBytes).toInt().coerceIn(0, 100) else 0

        h.info.text = when {
            inProgress && d.isEngine && known -> "${size(d.downloadedBytes)} of ${size(d.totalBytes)}"
            inProgress && d.isEngine -> size(d.downloadedBytes)
            else -> (if (d.totalBytes > 0) size(d.totalBytes) + " · " else "") + fmt.format(Date(d.addedAt))
        }
        h.status.text = when (st) {
            DownloadManager.STATUS_SUCCESSFUL -> "Done"
            DownloadManager.STATUS_FAILED -> "Failed"
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> when {
                d.isEngine && known && d.speed > 0 -> "$pct% · ${size(d.speed)}/s"
                d.isEngine && known -> "$pct%"
                else -> "Downloading"
            }
            DownloadRecord.STATUS_MISSING -> "File missing"
            else -> if (known) "Paused $pct%" else "Paused"
        }

        h.status.setTextColor(com.google.android.material.color.MaterialColors.getColor(h.status,
            if (st == DownloadManager.STATUS_FAILED || st == DownloadRecord.STATUS_MISSING)
                androidx.appcompat.R.attr.colorError
            else com.google.android.material.R.attr.colorPrimary))

        h.toggle.visibility = View.GONE
        if (d.isEngine) {
            val label = when (st) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> "Pause"
                DownloadManager.STATUS_PAUSED -> "Resume"
                DownloadManager.STATUS_FAILED -> "Retry"
                else -> null
            }
            if (label != null) {
                h.toggle.text = label
                h.toggle.visibility = View.VISIBLE
                h.toggle.setOnClickListener { onToggle(d) }
            }
        }

        if (inProgress) {
            h.bar.visibility = View.VISIBLE
            if (known) {
                h.bar.isIndeterminate = false
                h.bar.setProgressCompat(pct, false)
            } else if (st == DownloadManager.STATUS_PAUSED) {
                h.bar.isIndeterminate = false
                h.bar.setProgressCompat(0, false)
            } else h.bar.isIndeterminate = true
        } else h.bar.visibility = View.GONE

        h.itemView.setOnClickListener { onClick(d) }
    }

    private fun size(b: Long): String = when {
        b >= 1_073_741_824L -> "%.2f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024L -> "${b / 1024} KB"
        else -> "$b B"
    }
}
