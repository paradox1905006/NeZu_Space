package com.dparadox.tabspace

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {

    private lateinit var bgPreview: ImageView
    private lateinit var tvNoPicture: TextView
    private lateinit var tvOpacity: TextView
    private lateinit var slider: Slider
    private lateinit var btnRemove: MaterialButton

    private val pickBackground = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        Thread {
            val ok = ThemePrefs.importBackground(applicationContext, uri)
            runOnUiThread {
                if (ok) {
                    ThemePrefs.applyToWindow(this)
                    refreshBackgroundUi()
                } else {
                    Toast.makeText(this, "Could not use that picture", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ── Sounds ───────────────────────────────────────────────────────────────
    private var pickingFor: SoundFx.Event? = null
    private val soundRows = HashMap<SoundFx.Event, () -> Unit>()

    private val pickSound = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val e = pickingFor ?: return@registerForActivityResult
        pickingFor = null
        uri ?: return@registerForActivityResult
        Thread {
            val error = SoundFx.importCustom(applicationContext, e, uri)
            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                } else {
                    soundRows[e]?.invoke()
                    SoundFx.preview(this, e)
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemePrefs.applyToWindow(this)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.tvVersion).text = "Version ${BuildConfig.VERSION_NAME}"

        // ── Theme mode ───────────────────────────────────────────────────────
        val group = findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        group.check(
            when (ThemePrefs.mode(this)) {
                ThemePrefs.MODE_LIGHT -> R.id.btnThemeLight
                ThemePrefs.MODE_DARK -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            }
        )
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            ThemePrefs.setMode(
                this,
                when (id) {
                    R.id.btnThemeLight -> ThemePrefs.MODE_LIGHT
                    R.id.btnThemeDark -> ThemePrefs.MODE_DARK
                    else -> ThemePrefs.MODE_SYSTEM
                }
            )
        }

        // ── Material You ─────────────────────────────────────────────────────
        val swDynamic = findViewById<MaterialSwitch>(R.id.switchDynamic)
        if (DynamicColors.isDynamicColorAvailable()) {
            swDynamic.isChecked = ThemePrefs.dynamicColor(this)
            swDynamic.setOnCheckedChangeListener { _, on ->
                ThemePrefs.setDynamicColor(this, on)
                recreate()
            }
        } else {
            swDynamic.isChecked = false
            swDynamic.isEnabled = false
            findViewById<TextView>(R.id.tvDynamicHint).text = "Needs Android 12 or newer"
        }

        // ── Animations ───────────────────────────────────────────────────────
        val swAnim = findViewById<MaterialSwitch>(R.id.switchAnimations)
        swAnim.isChecked = ThemePrefs.animations(this)
        swAnim.setOnCheckedChangeListener { _, on -> ThemePrefs.setAnimations(this, on) }

        // ── Sounds ───────────────────────────────────────────────────────────
        setupSounds()

        // ── Ad blocker ───────────────────────────────────────────────────────
        val swAds = findViewById<MaterialSwitch>(R.id.switchAdBlock)
        swAds.isChecked = ThemePrefs.adBlock(this)
        swAds.setOnCheckedChangeListener { _, on -> ThemePrefs.setAdBlock(this, on) }

        // ── Don't sleep ──────────────────────────────────────────────────────
        val tvAwake = findViewById<android.widget.TextView>(R.id.tvKeepAwake)
        val awakeValues = intArrayOf(0, -1, 5, 15, 30)
        val awakeLabels = arrayOf("Off (normal screen timeout)", "Always on", "5 minutes", "15 minutes", "30 minutes")
        fun awakeText(v: Int) = when (v) {
            0 -> "Off"; -1 -> "Always on while the app is open"
            else -> "On, screen stays on for $v minutes after your last touch"
        }
        tvAwake.text = awakeText(ThemePrefs.keepAwake(this))
        findViewById<View>(R.id.rowKeepAwake).setOnClickListener {
            val cur = awakeValues.indexOf(ThemePrefs.keepAwake(this)).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Don't sleep")
                .setSingleChoiceItems(awakeLabels, cur) { d, which ->
                    ThemePrefs.setKeepAwake(this, awakeValues[which])
                    tvAwake.text = awakeText(awakeValues[which])
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ── Background picture ───────────────────────────────────────────────
        bgPreview = findViewById(R.id.bgPreview)
        tvNoPicture = findViewById(R.id.tvNoPicture)
        tvOpacity = findViewById(R.id.tvOpacity)
        slider = findViewById(R.id.sliderOpacity)
        btnRemove = findViewById(R.id.btnRemoveBg)

        slider.value = ThemePrefs.opacity(this).toFloat()
        tvOpacity.text = "${ThemePrefs.opacity(this)}%"
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ThemePrefs.setOpacity(this, value.toInt())
            tvOpacity.text = "${value.toInt()}%"
            ThemePrefs.applyToWindow(this)   // live preview behind this screen
        }

        findViewById<MaterialButton>(R.id.btnPickBg).setOnClickListener {
            pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnRemove.setOnClickListener {
            ThemePrefs.clearBackground(this)
            ThemePrefs.applyToWindow(this)
            refreshBackgroundUi()
        }

        val swWeb = findViewById<MaterialSwitch>(R.id.switchWebTransparent)
        swWeb.isChecked = ThemePrefs.webTransparent(this)
        swWeb.setOnCheckedChangeListener { _, on -> ThemePrefs.setWebTransparent(this, on) }

        refreshBackgroundUi()

        // ── Help / About ─────────────────────────────────────────────────────
        findViewById<LinearLayout>(R.id.rowGuide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowTelegram).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Parad0x1905006")))
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open Telegram", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSounds() {
        val list = findViewById<LinearLayout>(R.id.soundList)
        val master = findViewById<MaterialSwitch>(R.id.switchSoundMaster)
        val rows = ArrayList<View>()

        fun applyMaster() {
            val on = SoundFx.masterOn(this)
            list.alpha = if (on) 1f else 0.4f
            rows.forEach { r ->
                listOf(R.id.switchSound, R.id.btnSoundChoose, R.id.btnSoundReset).forEach { id ->
                    r.findViewById<View>(id).isEnabled = on
                }
            }
            // Preview stays usable, so a sound can be heard before the master switch is turned on.
        }

        SoundFx.Event.values().forEach { e ->
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = (14 * resources.displayMetrics.density).toInt()
                    bottomMargin = (14 * resources.displayMetrics.density).toInt()
                }
                setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOutlineVariant))
            }
            list.addView(divider)

            val row = layoutInflater.inflate(R.layout.item_sound, list, false)
            list.addView(row)
            rows.add(row)

            row.findViewById<TextView>(R.id.tvSoundTitle).text = e.title
            row.findViewById<TextView>(R.id.tvSoundHint).text = e.hint
            val source = row.findViewById<TextView>(R.id.tvSoundSource)
            val sw = row.findViewById<MaterialSwitch>(R.id.switchSound)
            val reset = row.findViewById<MaterialButton>(R.id.btnSoundReset)

            val refresh = {
                val name = SoundFx.customName(this, e)
                source.text = if (name != null) "Custom: $name" else "Default sound"
                reset.visibility = if (name != null) View.VISIBLE else View.GONE
            }
            soundRows[e] = refresh
            refresh()

            sw.isChecked = SoundFx.eventOn(this, e)
            sw.setOnCheckedChangeListener { _, on -> SoundFx.setEventOn(this, e, on) }

            row.findViewById<View>(R.id.btnSoundPreview).setOnClickListener { SoundFx.preview(this, e) }
            row.findViewById<View>(R.id.btnSoundChoose).setOnClickListener {
                pickingFor = e
                pickSound.launch("audio/*")
            }
            reset.setOnClickListener {
                SoundFx.resetToDefault(this, e)
                refresh()
            }
        }

        master.isChecked = SoundFx.masterOn(this)
        master.setOnCheckedChangeListener { _, on ->
            SoundFx.setMasterOn(this, on)
            applyMaster()
        }
        applyMaster()
    }

    override fun onStop() {
        SoundFx.stopAll()   // a preview must not keep playing after leaving Settings
        super.onStop()
    }

    private fun refreshBackgroundUi() {
        val bmp = ThemePrefs.previewBitmap(this)
        bgPreview.setImageBitmap(bmp)
        tvNoPicture.visibility = if (bmp == null) View.VISIBLE else View.GONE
        btnRemove.isEnabled = bmp != null
        slider.isEnabled = bmp != null
    }
}
