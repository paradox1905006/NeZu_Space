package com.dparadox.tabspace

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat

/** Shortcuts that are put on the phone's own home screen (launcher). */
object PhoneShortcuts {
    const val EXTRA_PROFILE = "nezu_profile"

    /** @return false when the launcher cannot pin shortcuts. */
    fun pin(ctx: Context, id: String, label: String, url: String?, profileId: String, icon: IconCompat): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = if (url != null) Intent.ACTION_VIEW else Intent.ACTION_MAIN
            if (url != null) data = Uri.parse(url)
            putExtra(EXTRA_PROFILE, profileId)
        }
        val short = label.ifBlank { "NeZu Space" }
        val info = ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(short.take(24))
            .setLongLabel(short.take(60))
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    /**
     * Round picture for the launcher: own picture, else the site's logo, else the first letter on [color].
     * Drawn as an adaptive icon, so it stays inside the safe zone.
     */
    fun icon(ctx: Context, title: String, color: Int, pictureUri: String?, host: String?): IconCompat {
        val size = 216
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var pic: Bitmap? = null
        if (pictureUri != null) pic = runCatching {
            ctx.contentResolver.openInputStream(Uri.parse(pictureUri))?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        val own = pic != null
        if (pic == null && !host.isNullOrBlank()) pic = FaviconStore.get(ctx, host)

        if (pic != null) {
            paint.color = Color.WHITE
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            val inset = size * if (own || pic.width >= 96) 0.17f else 0.28f
            val dst = RectF(inset, inset, size - inset, size - inset)
            c.save()
            c.clipPath(Path().apply { addCircle(size / 2f, size / 2f, (size - 2 * inset) / 2f, Path.Direction.CW) })
            c.drawBitmap(pic, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            paint.color = color
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.color = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
            paint.textSize = size * 0.36f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            val letter = title.trim().firstOrNull()?.uppercase() ?: "N"
            c.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(out)
    }
}
