package com.dparadox.tabspace

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Crop screen for profile pictures, tab avatars and shortcut icons.
 * The picture is moved and zoomed under a round window; what is inside the circle is saved as a
 * round 512 x 512 PNG in app storage. The result is the file URI (see [Contract]).
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OUT = "out"
    }

    /** Input: picture URI. Output: URI string of the saved round picture, or null when cancelled. */
    class Contract : ActivityResultContract<Uri, String?>() {
        override fun createIntent(context: Context, input: Uri) =
            Intent(context, CropActivity::class.java).apply {
                data = input
                clipData = ClipData.newRawUri("", input)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_OUT) else null
    }

    private lateinit var cropView: CropImageView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val uri = intent.data
        val bmp = uri?.let { loadBitmap(it) }
        if (bmp == null) {
            Toast.makeText(this, "Could not open this picture", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED); finish(); return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); fitsSystemWindows = true }
        cropView = CropImageView(this).also { it.setBitmap(bmp) }
        root.addView(cropView, FrameLayout.LayoutParams(-1, -1))

        // Top: cancel + hint
        val close = roundButton(R.drawable.ic_close) { setResult(Activity.RESULT_CANCELED); finish() }
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Move and zoom to choose the area"
            setTextColor(0xCCFFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })

        // Bottom left: rotate / flip / reset
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(0xE62A2A2E.toInt()) }
        }
        bar.addView(plainButton(R.drawable.ic_rotate) { cropView.rotate90() })
        bar.addView(plainButton(R.drawable.ic_flip) { cropView.flipHorizontal() })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(16), 0, 0, dp(20))
        })

        // Bottom right: confirm
        val ok = FloatingActionButton(this).apply {
            setImageResource(R.drawable.ic_check)
            contentDescription = "Use this picture"
            setOnClickListener { save() }
        }
        root.addView(ok, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16), dp(20))
        })

        setContentView(root)
    }

    private fun plainButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        background = null
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun roundButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x802A2A2E.toInt()) }
        setOnClickListener { onClick() }
    }

    private fun save() {
        val out = cropView.crop(512) ?: return
        val dir = File(filesDir, "avatars").apply { mkdirs() }
        val f = File(dir, "img_${System.currentTimeMillis()}.png")
        runCatching { FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure {
                Toast.makeText(this, "Could not save the picture", Toast.LENGTH_SHORT).show(); return
            }
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, Uri.fromFile(f).toString()))
        finish()
    }

    /** Decodes the picture (at most about 2048 px) and applies its EXIF rotation. */
    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE  -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }.getOrNull()
}

/** Shows a picture that can be moved and zoomed under a fixed round crop window. */
class CropImageView(c: Context) : View(c) {

    private var bmp: Bitmap? = null
    private val m = Matrix()
    private val vals = FloatArray(9)
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val density = c.resources.displayMetrics.density

    private val dim = Paint().apply { color = 0xBF000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2f * density
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hole = Path()

    private var lastX = 0f
    private var lastY = 0f
    private var pointerId = -1

    private val scaleDetector = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            fix(); invalidate(); return true
        }
    })

    fun setBitmap(b: Bitmap) { bmp = b; resetFit() }

    fun rotate90() {
        val b = bmp ?: return
        bmp = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
        resetFit()
    }

    fun flipHorizontal() {
        val b = bmp ?: return
        bmp = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postScale(-1f, 1f) }, true)
        resetFit()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f
        cy = h * 0.46f
        r = min(w / 2f - 20 * density, h * 0.36f)
        resetFit()
    }

    private fun minScale(): Float {
        val b = bmp ?: return 1f
        return max(2f * r / b.width, 2f * r / b.height)
    }

    private fun resetFit() {
        val b = bmp ?: return
        if (r <= 0f) return
        val s = minScale()
        m.reset()
        m.postScale(s, s)
        m.postTranslate(cx - b.width * s / 2f, cy - b.height * s / 2f)
        invalidate()
    }

    /** Keeps the zoom in range and the circle always inside the picture. */
    private fun fix() {
        val b = bmp ?: return
        m.getValues(vals)
        val s = vals[Matrix.MSCALE_X]
        val lo = minScale()
        val hi = lo * 10f
        if (s < lo) m.postScale(lo / s, lo / s, cx, cy)
        else if (s > hi) m.postScale(hi / s, hi / s, cx, cy)
        val rect = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.left > cx - r) dx = cx - r - rect.left else if (rect.right < cx + r) dx = cx + r - rect.right
        if (rect.top > cy - r) dy = cy - r - rect.top else if (rect.bottom < cy + r) dy = cy + r - rect.bottom
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0); lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) {
                    m.postTranslate(e.getX(i) - lastX, e.getY(i) - lastY)
                    lastX = e.getX(i); lastY = e.getY(i)
                    fix(); invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Continue the drag with the finger that is left (no jump).
                val up = e.actionIndex
                if (e.getPointerId(up) == pointerId) {
                    val n = if (up == 0) 1 else 0
                    pointerId = e.getPointerId(n); lastX = e.getX(n); lastY = e.getY(n)
                } else {
                    val i = e.findPointerIndex(pointerId)
                    if (i >= 0) { lastX = e.getX(i); lastY = e.getY(i) }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) { lastX = e.getX(i); lastY = e.getY(i) }
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        bmp?.let { canvas.drawBitmap(it, m, bmpPaint) }
        hole.reset(); hole.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(hole, Region.Op.DIFFERENCE)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.restore()
        canvas.drawCircle(cx, cy, r, ring)
    }

    /** The part of the picture inside the circle as a round [size] x [size] bitmap (corners transparent). */
    fun crop(size: Int): Bitmap? {
        val b = bmp ?: return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val mm = Matrix(m)
        mm.postTranslate(-(cx - r), -(cy - r))
        val k = size / (2f * r)
        mm.postScale(k, k)
        val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(mm) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p)
        return out
    }
}
