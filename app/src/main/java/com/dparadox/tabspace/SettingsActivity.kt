package com.dparadox.tabspace

import android.content.Intent
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            val ok = ThemePrefs.importBackgroundMedia(applicationContext, uri)
            runOnUiThread {
                if (ok) {
                    ThemePrefs.applyToWindow(this)
                    refreshBackgroundUi()
                } else {
                    Toast.makeText(this, "Could not use that file (pictures, GIFs up to 30 MB and videos up to 150 MB work)", Toast.LENGTH_LONG).show()
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

        // Large collapsing title (Material 3); the toolbar only carries the back arrow.
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
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

        // ── Privacy & security ───────────────────────────────────────────────
        setupPrivacy()

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
            pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        btnRemove.setOnClickListener {
            ThemePrefs.clearBackground(this)
            ThemePrefs.applyToWindow(this)
            refreshBackgroundUi()
        }

        val swWeb = findViewById<MaterialSwitch>(R.id.switchWebTransparent)
        swWeb.isChecked = ThemePrefs.webTransparent(this)
        swWeb.setOnCheckedChangeListener { _, on -> ThemePrefs.setWebTransparent(this, on) }

        val swVideo = findViewById<MaterialSwitch>(R.id.switchVideoSound)
        swVideo.isChecked = ThemePrefs.videoSound(this)
        swVideo.setOnCheckedChangeListener { _, on -> ThemePrefs.setVideoSound(this, on) }

        refreshBackgroundUi()

        // Tapping the whole row toggles its switch.
        listOf(
            R.id.rowDynamic to R.id.switchDynamic,
            R.id.rowAnimations to R.id.switchAnimations,
            R.id.rowAdBlock to R.id.switchAdBlock,
            R.id.rowSoundMaster to R.id.switchSoundMaster,
            R.id.rowWebTransparent to R.id.switchWebTransparent,
            R.id.rowVideoSound to R.id.switchVideoSound,
        ).forEach { (rowId, switchId) ->
            val sw = findViewById<MaterialSwitch>(switchId)
            findViewById<View>(rowId).setOnClickListener { if (sw.isEnabled) sw.toggle() }
        }

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
                val d = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)).apply {
                    marginStart = (72 * d).toInt()
                    marginEnd = (20 * d).toInt()
                    topMargin = (4 * d).toInt()
                    bottomMargin = (4 * d).toInt()
                }
                alpha = 0.6f
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
                source.text = when {
                    name != null -> "Custom: $name"
                    e.rawRes != null -> "Default sound"
                    else -> "No sound - tap Choose sound to add yours"
                }
                reset.text = if (e.rawRes != null) "Reset" else "Remove"
                reset.visibility = if (name != null) View.VISIBLE else View.GONE
            }
            soundRows[e] = refresh
            refresh()

            sw.isChecked = SoundFx.eventOn(this, e)
            sw.setOnCheckedChangeListener { _, on -> SoundFx.setEventOn(this, e, on) }

            row.findViewById<View>(R.id.btnSoundPreview).setOnClickListener {
                if (SoundFx.hasSound(this, e)) SoundFx.preview(this, e)
                else Toast.makeText(this, "Choose a sound first", Toast.LENGTH_SHORT).show()
            }
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

    /** The picture / sound picker is not "leaving the app": do not re-lock when it closes. */
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: android.os.Bundle?) {
        LockState.markExternalLaunch()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStart() {
        super.onStart()
        LockState.onStart()
    }

    override fun onResume() {
        super.onResume()
        // The app lock also protects this screen: when the lock is due, go back and let the browser screen ask.
        if (LockState.appLockNeeded(this)) { finish(); return }
        if (PrivacyPrefs.secureScreen(this) || PrivacyPrefs.appLock(this))
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        SoundFx.stopAll()   // a preview must not keep playing after leaving Settings
        LockState.onStop()
        super.onStop()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Privacy & security
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupPrivacy() {
        val list = findViewById<LinearLayout>(R.id.privacyList)
        val dp = resources.displayMetrics.density
        var firstHeader = true

        fun header(text: String) {
            list.addView(TextView(this).apply {
                this.text = text.lowercase().replaceFirstChar { it.uppercase() }
                setTextAppearance(R.style.TextAppearance_Nezu_LabelLarge)
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorPrimary))
                setPadding((72 * dp).toInt(), ((if (firstHeader) 8 else 20) * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
            })
            firstHeader = false
        }

        fun setIcon(row: View, icon: Int?) {
            val iv = row.findViewById<ImageView>(R.id.ivPrefIcon)
            if (icon == null) { iv.visibility = View.GONE; return }
            iv.setImageResource(icon); iv.visibility = View.VISIBLE
        }

        /** A row with a switch. [onToggle] returns false to refuse the change (the switch jumps back). */
        fun switchRow(title: String, hint: String, checked: Boolean, icon: Int? = null, onToggle: (Boolean) -> Boolean) {
            val row = layoutInflater.inflate(R.layout.item_pref_row, list, false)
            setIcon(row, icon)
            row.findViewById<TextView>(R.id.tvPrefTitle).text = title
            row.findViewById<TextView>(R.id.tvPrefHint).text = hint
            val sw = row.findViewById<MaterialSwitch>(R.id.switchPref)
            sw.isChecked = checked
            var reverting = false
            sw.setOnCheckedChangeListener { _, on ->
                if (reverting) return@setOnCheckedChangeListener
                if (!onToggle(on)) { reverting = true; sw.isChecked = !on; reverting = false }
            }
            row.setOnClickListener { sw.toggle() }
            list.addView(row)
        }

        /** A row that opens a dialog. [value] is shown under the title and is read again after [onClick] calls its argument. */
        fun actionRow(title: String, value: () -> String, icon: Int? = null, onClick: (refresh: () -> Unit) -> Unit) {
            val row = layoutInflater.inflate(R.layout.item_pref_row, list, false)
            setIcon(row, icon)
            row.findViewById<TextView>(R.id.tvPrefTitle).text = title
            val hint = row.findViewById<TextView>(R.id.tvPrefHint)
            hint.text = value()
            row.findViewById<View>(R.id.switchPref).visibility = View.GONE
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            row.setBackgroundResource(tv.resourceId)
            row.setOnClickListener { onClick { hint.text = value() } }
            list.addView(row)
        }

        fun noScreenLock(): Boolean {
            if (LockScreen.available(this)) return false
            Toast.makeText(this, "Set a screen lock (PIN, pattern, password or fingerprint) in your phone's Settings first", Toast.LENGTH_LONG).show()
            return true
        }

        // ── Lock ─────────────────────────────────────────────────────────────
        header("LOCK AND SCREEN")
        switchRow("App lock", "Ask for fingerprint, face or phone lock when the app opens",
            PrivacyPrefs.appLock(this), R.drawable.ic_fingerprint) { on ->
            if (on && noScreenLock()) false else { PrivacyPrefs.setAppLock(this, on); true }
        }
        val delays = longArrayOf(0L, 30_000L, 120_000L, 600_000L)
        val delayNames = arrayOf("Immediately", "After 30 seconds", "After 2 minutes", "After 10 minutes")
        actionRow("Lock again", { delayNames[delays.indexOf(PrivacyPrefs.lockDelayMs(this)).coerceAtLeast(0)] }, R.drawable.ic_lock_clock) { refresh ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Lock again after leaving the app")
                .setSingleChoiceItems(delayNames, delays.indexOf(PrivacyPrefs.lockDelayMs(this)).coerceAtLeast(0)) { d, i ->
                    PrivacyPrefs.setLockDelayMs(this, delays[i]); refresh(); d.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }
        actionRow("Locked profiles", {
            val n = PrivacyPrefs.lockedProfiles(this).size
            if (n == 0) "None. A locked profile asks for unlock before it opens." else "$n locked"
        }, R.drawable.ic_lock_person) { refresh ->
            if (!noScreenLock()) pickProfiles("Locked profiles", PrivacyPrefs.lockedProfiles(this),
                { PrivacyPrefs.setLockedProfiles(this, it) }, refresh)
        }
        switchRow("Hide screen in recent apps", "Blocks screenshots and shows a blank picture in the recent-apps list. Always on when the app lock or a locked or private profile is used",
            PrivacyPrefs.secureScreen(this), R.drawable.ic_visibility_off) { on ->
            PrivacyPrefs.setSecureScreen(this, on)
            if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else if (!PrivacyPrefs.appLock(this)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            true
        }

        // ── Private browsing ─────────────────────────────────────────────────
        header("PRIVATE BROWSING")
        actionRow("Private profiles", {
            val n = PrivacyPrefs.privateProfiles(this).size
            if (n == 0) "None. A private profile forgets cookies, storage, cache and history when the app is closed." else "$n private"
        }, R.drawable.ic_incognito) { refresh ->
            pickProfiles("Private profiles", PrivacyPrefs.privateProfiles(this),
                { PrivacyPrefs.setPrivateProfiles(this, it) }, refresh)
        }
        switchRow("Clear data when the app closes", "Removes the data chosen below from every profile",
            PrivacyPrefs.autoClear(this), R.drawable.ic_auto_delete) { on -> PrivacyPrefs.setAutoClear(this, on); true }
        actionRow("What to clear", {
            val m = PrivacyPrefs.autoClearMask(this)
            val names = DataWiper.BITS.indices.filter { m and DataWiper.BITS[it] != 0 }.map { DataWiper.LABELS[it].substringBefore(" (") }
            if (names.isEmpty()) "Nothing selected" else names.joinToString(", ")
        }, R.drawable.ic_checklist) { refresh ->
            val m = PrivacyPrefs.autoClearMask(this)
            val checked = BooleanArray(DataWiper.BITS.size) { m and DataWiper.BITS[it] != 0 }
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear when the app closes")
                .setMultiChoiceItems(DataWiper.LABELS, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Save") { _, _ ->
                    var mask = 0
                    checked.forEachIndexed { i, on -> if (on) mask = mask or DataWiper.BITS[i] }
                    PrivacyPrefs.setAutoClearMask(this, mask); refresh()
                }
                .setNegativeButton("Cancel", null).show()
        }

        // ── Web ──────────────────────────────────────────────────────────────
        header("WEB")
        switchRow("Remove tracking from links", "Strips utm_, fbclid, gclid and similar parameters. Copy link gives the clean link",
            PrivacyPrefs.stripTracking(this), R.drawable.ic_link_off) { on -> PrivacyPrefs.setStripTracking(this, on); true }
        switchRow("Block trackers", "Blocks analytics and tracking hosts, next to the ad blocker. A few sites may break",
            PrivacyPrefs.blockTrackers(this), R.drawable.ic_shield) { on -> PrivacyPrefs.setBlockTrackers(this, on); true }
        switchRow("Block third-party cookies", "Cookies that come from a site other than the page you are on",
            PrivacyPrefs.blockThirdPartyCookies(this), R.drawable.ic_cookie) { on -> PrivacyPrefs.setBlockThirdPartyCookies(this, on); true }
        switchRow("HTTPS only", "Opens http links as https. If a site has no https, you are asked first",
            PrivacyPrefs.httpsOnly(this), R.drawable.ic_https) { on -> PrivacyPrefs.setHttpsOnly(this, on); true }
        switchRow("Safe Browsing", "Warns about phishing and malware pages (needs Google Play services)",
            PrivacyPrefs.safeBrowsing(this), R.drawable.ic_security) { on -> PrivacyPrefs.setSafeBrowsing(this, on); true }
        switchRow("Ask before opening other apps", "A page cannot open another app (mail, maps, stores) without your OK",
            PrivacyPrefs.confirmExternal(this), R.drawable.ic_open_in_new) { on -> PrivacyPrefs.setConfirmExternal(this, on); true }
        actionRow("Site permissions", {
            val n = PrivacyPrefs.siteDecisions(this).size
            if (n == 0) "Camera, microphone and location choices for sites. Nothing saved yet" else "$n sites with saved choices"
        }, R.drawable.ic_admin_panel) { refresh -> showSitePermissions(refresh) }

        // ── Downloads and data ───────────────────────────────────────────────
        header("DOWNLOADS AND DATA")
        switchRow("Warn before risky downloads", "Asks before program files (apk, exe ...) and before downloads that no tap started",
            PrivacyPrefs.downloadWarn(this), R.drawable.ic_warning) { on -> PrivacyPrefs.setDownloadWarn(this, on); true }
        switchRow("Clear clipboard after copying cookies", "Empties the clipboard one minute after Copy cookies",
            PrivacyPrefs.clipboardClear(this), R.drawable.ic_content_paste_off) { on -> PrivacyPrefs.setClipboardClear(this, on); true }
        switchRow("Encrypt saved data", "Tabs, profiles, shortcuts and the download list are stored encrypted, with a key that stays in this phone's Keystore. Pictures and downloaded files are not encrypted",
            PrivacyPrefs.encryptData(this), R.drawable.ic_encrypted) { on -> PrivacyPrefs.setEncryptData(this, on); true }
    }

    /** Pick profiles from a list; [refresh] updates the row text afterwards. */
    private fun pickProfiles(title: String, current: Set<String>, save: (Set<String>) -> Unit, refresh: () -> Unit) {
        val profiles = TabStore(this).load().profiles
        if (profiles.isEmpty()) { Toast.makeText(this, "No profiles yet", Toast.LENGTH_SHORT).show(); return }
        val names = profiles.map { it.name }.toTypedArray()
        val checked = BooleanArray(profiles.size) { profiles[it].id in current }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                val ids = profiles.indices.filter { checked[it] }.map { profiles[it].id }.toSet()
                save(ids); refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSitePermissions(refresh: () -> Unit) {
        val items = PrivacyPrefs.siteDecisions(this)
        if (items.isEmpty()) { Toast.makeText(this, "No saved choices yet", Toast.LENGTH_SHORT).show(); return }
        val labels = items.map { (host, m) ->
            host + "\n" + m.entries.joinToString(", ") { (t, ok) ->
                t.replaceFirstChar { it.uppercase() } + ": " + (if (ok) "allowed" else "blocked")
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Tap a site to reset it")
            .setItems(labels) { _, i ->
                PrivacyPrefs.clearSite(this, items[i].first); refresh()
                Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset all") { _, _ -> PrivacyPrefs.clearAllSites(this); refresh() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun refreshBackgroundUi() {
        val bmp = ThemePrefs.previewBitmap(this)
        bgPreview.setImageBitmap(bmp)
        tvNoPicture.visibility = if (bmp == null) View.VISIBLE else View.GONE
        btnRemove.isEnabled = bmp != null
        slider.isEnabled = bmp != null
        // The sound switch only matters for a video (the preview above shows its first frame).
        findViewById<View>(R.id.rowVideoSound).visibility =
            if (ThemePrefs.bgType(this) == ThemePrefs.TYPE_VIDEO) View.VISIBLE else View.GONE
    }
}
