package com.dparadox.tabspace

import android.animation.ValueAnimator
import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.MotionEvent
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.drawToBitmap
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewFeature
import com.dparadox.tabspace.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    companion object {
        /** True until the first MainActivity of this process has started (= app was closed and opened again). */
        private var coldStart = true
        const val FLIP_MS = 420L
        const val DUST_MS = 700L
        /** Remembers the download="name" of the last clicked link, so blob: downloads keep their real file name. */
        const val DOWNLOAD_NAME_HOOK =
            "(function(){if(window.__nezuHook)return;window.__nezuHook=1;" +
            "function rec(a){try{if(a&&a.download)window.__nezuName=a.download;}catch(e){}}" +
            "var oc=HTMLAnchorElement.prototype.click;" +
            "HTMLAnchorElement.prototype.click=function(){rec(this);return oc.apply(this,arguments);};" +
            "document.addEventListener('click',function(e){var t=e.target;" +
            "while(t&&t.tagName!=='A')t=t.parentElement;rec(t);},true);})();"
        val PALETTE = intArrayOf(
            0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(),
            0xFF6D4C41.toInt(), 0xFF546E7A.toInt()
        )
    }

    // ── Data ──────────────────────────────────────────────────────────────────
    private val allProfiles = mutableListOf<AppProfile>()
    private var currentProfile: AppProfile? = null
    private val allTabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private val lastTabOfProfile = mutableMapOf<String, String>()

    private val profileTabs: List<Tab>
        get() = allTabs.filter { it.profileId == (currentProfile?.id ?: "") }

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var b: ActivityMainBinding
    private lateinit var store: TabStore
    private lateinit var tabAdapter: TabAdapter
    private lateinit var homeAdapter: HomeAdapter
    private var profileAdapter: ProfileAdapter? = null
    private var profileDialog: android.app.Dialog? = null

    /** True while a full-page animation overlay is running; gestures are ignored meanwhile. */
    private var busy = false
    private var busyGen = 0

    /** Marks an animation as running. A watchdog clears the flag so a lost callback can never block animations. */
    private fun beginBusy() {
        busy = true
        val g = ++busyGen
        b.root.postDelayed({ if (busy && g == busyGen) busy = false }, 3000)
    }

    /** Runs [block] as soon as no full-page animation is running (so it still gets its own animation). */
    private fun whenIdle(tries: Int = 0, block: () -> Unit) {
        if (!busy || tries > 15) { block(); return }
        b.root.postDelayed({ whenIdle(tries + 1, block) }, 80)
    }

    // Tab panel state
    private var switcherClosing = false
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var heightAnimator: ValueAnimator? = null
    private var newTabPending = false

    // Fullscreen video
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenHost: FrameLayout? = null

    private val profilesSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    /** Receives blob: downloads from pages (see saveBlob). */
    private val blobBridge = BlobBridge()
    private val srcBridge = SourceBridge()
    private val extBridge by lazy { ExtensionBridge(this) }
    private val extHandlers = java.util.WeakHashMap<WebView, List<ScriptHandler>>()
    private var extVersionSeen = ExtensionStore.version

    // ── Privacy & security state ──────────────────────────────────────────────
    private val lockScreen by lazy { LockScreen(this) { onUnlockSuccess() } }
    private val lockBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { moveTaskToBack(true) }   // the lock cover must not be closed with Back
    }
    private var lockBackAdded = false

    /** Sites the user chose to open over http although "HTTPS only" is on (until the app is closed). */
    private val httpAllowed = HashSet<String>()

    private var permCallback: ((Boolean) -> Unit)? = null
    private var permTypes: List<String> = emptyList()
    private val sitePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val cb = permCallback
        permCallback = null
        // Location: "approximate" is enough. Camera / microphone: every one that was asked for.
        val ok = r.isNotEmpty() && (if (permTypes == listOf(PrivacyPrefs.PERM_LOCATION)) r.values.any { it } else r.values.all { it })
        cb?.invoke(ok)
    }

    /** Moving (video) background behind the whole UI. */
    private val videoBg by lazy { VideoBackground(this) }

    // ── Saving (debounced so page loads never block the UI) ────────────────────
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { store.save(allTabs, current?.id, allProfiles, currentProfile?.id) }
    private fun persist() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 400)
    }
    private fun persistNow() {
        saveHandler.removeCallbacks(saveRunnable)
        store.save(allTabs, current?.id, allProfiles, currentProfile?.id)
    }

    // ── Image pickers: pick a picture, then crop it to a circle ──────────────
    private enum class CropFor { TAB, PROFILE, SHORTCUT }
    private var cropFor: CropFor? = null
    private var avatarPickTarget: Tab? = null
    private var profileAvatarTarget: AppProfile? = null
    private var pendingIcon: String? = null                // shortcut dialog: picture chosen so far
    private val createdIcons = mutableListOf<String>()     // pictures made while the shortcut dialog is open
    private var shortcutIconRefresh: (() -> Unit)? = null

    // Website upload buttons (<input type=file>): the system file picker needs no storage permission.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val cropImage = registerForActivityResult(CropActivity.Contract()) { out ->
        val kind = cropFor
        cropFor = null
        if (out != null) {
            when (kind) {
                CropFor.TAB -> avatarPickTarget?.let {
                    deleteImageFile(it.avatarUri); it.avatarUri = out
                    refreshSwitcher(); persist()
                }
                CropFor.PROFILE -> profileAvatarTarget?.let {
                    deleteImageFile(it.avatarUri); it.avatarUri = out
                    updateProfileBar(); profileAdapter?.notifyDataSetChanged(); persist()
                }
                CropFor.SHORTCUT -> {
                    createdIcons.add(out); pendingIcon = out
                    shortcutIconRefresh?.invoke()
                }
                null -> {}
            }
        }
        avatarPickTarget = null
        profileAvatarTarget = null
    }

    private fun startCrop(kind: CropFor, uri: Uri) { cropFor = kind; cropImage.launch(uri) }

    private val pickTabAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) startCrop(CropFor.TAB, uri) else avatarPickTarget = null
    }
    private val pickProfileAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) startCrop(CropFor.PROFILE, uri) else profileAvatarTarget = null
    }
    private val pickShortcutIcon = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) startCrop(CropFor.SHORTCUT, uri)
    }

    /** Deletes a cropped picture made by this app (never the user's own gallery picture). */
    private fun deleteImageFile(uri: String?) {
        val path = uri?.takeIf { it.startsWith("file:") }?.let { Uri.parse(it).path } ?: return
        if (path.contains("/avatars/")) runCatching { java.io.File(path).delete() }
    }

    private var themeVersionSeen = 0

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Colour swatch for the colour pickers. The chosen one gets an outer ring and a check mark. */
    private fun swatch(color: Int, selected: Boolean, ring: Int): android.graphics.drawable.Drawable {
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        if (!selected) return android.graphics.drawable.InsetDrawable(dot, dp(4))
        val outer = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), ring) }
        val inner = android.graphics.drawable.InsetDrawable(dot, dp(5))
        val check = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_check)?.mutate()?.apply {
            setTint(if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE)
        }
        val layers = listOfNotNull<android.graphics.drawable.Drawable>(outer, inner, check?.let { android.graphics.drawable.InsetDrawable(it, dp(11)) })
        return android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }
    private fun spanCount() = if (resources.configuration.screenWidthDp >= 600) 3 else 2
    private fun homeSpan() = if (resources.configuration.screenWidthDp >= 600) 6 else 4
    private fun animOn() = ThemePrefs.animations(this)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun siteHost(url: String?): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.ifBlank { null }

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = TabStore(this)
        themeVersionSeen = ThemePrefs.version
        AdBlocker.enabled = ThemePrefs.adBlock(this)
        AdBlocker.load(this)
        applyAppTheme()

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, b.root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        tabAdapter = TabAdapter(
            onOpen  = { selectTab(it) },
            onClose = { tab, row -> closeTab(tab, row) },
            onMenu  = { showTabOptions(it) },
        )
        b.tabList.layoutManager = LinearLayoutManager(this)
        b.tabList.adapter = tabAdapter
        // Swipe a tab card sideways to close it (like Chrome).
        b.tabList.addOnItemTouchListener(SwipeDismissListener { pos, view, restore ->
            val tab = tabAdapter.tabAt(pos)
            if (tab == null) restore() else closeTab(tab, view, restore)
        })

        homeAdapter = HomeAdapter(
            onOpen = { bm -> openInCurrent(bm.url) },
            onMenu = { bm, v -> showTileMenu(bm, v) },
            onAdd  = { showBookmarkDialog(null) },
        )
        b.homeGrid.layoutManager = GridLayoutManager(this, homeSpan())
        b.homeGrid.adapter = homeAdapter

        // Swipe the address bar to change tab, swipe the bottom bar to change profile.
        b.swipeUrl.canSwipe = { !b.urlInput.hasFocus() && !isSwitcherOpen() }
        b.swipeUrl.onSwipe = { d -> switchTabBy(d) }
        b.swipeBottom.onSwipe = { d -> switchProfileBy(d) }
        b.swipeBottom.excluded = b.btnTabs   // the tab button has its own swipe (tabs), the bar swipes profiles

        DownloadEngine.listener = { persist() }
        DownloadEngine.cookieProvider = { rec -> cookieForDownload(rec) }
        setupListeners()
        loadState()

        if (savedInstanceState == null) {
            SoundFx.play(this, SoundFx.Event.APP_OPEN)   // cold start only
            handleIntent(intent)
        }
        if (!getSharedPreferences("nezu_app", MODE_PRIVATE).getBoolean("terms_ok", false)) showTerms()

        if (!profilesSupported) Toast.makeText(
            this, "Update Android System WebView for full profile isolation.", Toast.LENGTH_LONG
        ).show()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = current?.webView
                when {
                    customView != null                    -> exitFullscreen()
                    b.switcher.visibility == View.VISIBLE -> hideSwitcher()
                    current?.homeShown == true && current?.url?.isNotBlank() == true -> hideHome()
                    wv != null && wv.canGoBack()          -> wv.goBack()
                    else                                  -> finish()
                }
            }
        })
    }

    private fun loadState() {
        val r = store.load()
        if (coldStart) wipeProfiles(live = false, profiles = r.profiles)   // private profiles / "clear when closed"
        allProfiles.addAll(r.profiles)
        if (allProfiles.isEmpty()) allProfiles.add(AppProfile(name = "Default", color = PALETTE[5]))
        currentProfile = allProfiles.firstOrNull { it.id == r.currentProfileId } ?: allProfiles.first()

        allTabs.addAll(r.tabs)
        sortPinnedFirst()
        cleanupWebKitProfiles()

        if (profileTabs.isEmpty()) addTab()
        val target = allTabs.firstOrNull { it.id == r.currentTabId && it.profileId == currentProfile?.id }
            ?: profileTabs.first()
        showTab(target)
        if (coldStart) {
            // App was closed and opened again: start on the NeZu Space home, not on a web page.
            coldStart = false
            current?.homeShown = true
            applyHomeState(); applyChrome(); refreshSwitcher()
        }
        updateProfileBar()
        refreshHome()

        // Create the WebViews of the other profiles while the app is idle, so switching profile is instant.
        b.root.post { prewarmProfiles() }
    }

    private fun prewarmProfiles() {
        val queue = allProfiles
            .filter { it.id != currentProfile?.id }
            .mapNotNull { p -> allTabs.firstOrNull { it.profileId == p.id } }
            .toMutableList()
        if (queue.isEmpty()) return
        Looper.myQueue().addIdleHandler {
            val t = queue.removeFirstOrNull()
            if (t != null && t.webView == null && allTabs.contains(t) && !isDestroyed) {
                t.webView = createWebView(t)
            }
            queue.isNotEmpty()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        // A shortcut from the phone's home screen also tells which profile it belongs to.
        val target = i.getStringExtra(PhoneShortcuts.EXTRA_PROFILE)
            ?.let { id -> allProfiles.firstOrNull { it.id == id } }
        if (target != null && target !== currentProfile) {
            hideSwitcher(animate = false)
            applyProfile(target)
        }
        val u = i.data?.toString()
        if (u != null) {
            val existing = if (target != null) profileTabs.firstOrNull { it.url == u } else null
            if (existing != null) { existing.homeShown = false; showTab(existing) }
            else showTab(addTab(u))
        } else if (target != null) {
            current?.homeShown = true
            applyHomeState(); applyChrome()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        b.root.post { if (b.switcher.visibility == View.VISIBLE) updateListHeight(false) }
        (b.homeGrid.layoutManager as? GridLayoutManager)?.spanCount = homeSpan()
        applyAppTheme()   // picture is cropped to the new screen size
    }

    /** System pickers, the crop screen and file choosers are not "leaving the app": do not re-lock when they close. */
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        LockState.markExternalLaunch()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStart() {
        super.onStart()
        LockState.onStart()
    }

    override fun onStop() {
        LockState.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        awakeHandler.removeCallbacks(awakeOff)
        if (customView == null) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        videoBg.pause()
        current?.webView?.onPause(); persistNow()
    }
    override fun onResume() {
        super.onResume()
        if (themeVersionSeen != ThemePrefs.version) { LockState.markExternalLaunch(); recreate(); return }   // Material You toggled
        AdBlocker.enabled = ThemePrefs.adBlock(this)
        applyAppTheme()   // picks up picture / opacity changes made in Settings
        current?.webView?.onResume()
        applyKeepAwake()
        applyPrivacy()
        if (extVersionSeen != ExtensionStore.version) {
            extVersionSeen = ExtensionStore.version
            allTabs.forEach { t -> t.webView?.let { applyExtensions(t, it) } }
            current?.webView?.reload()   // the open page picks up the new extension setup
        }
    }
    override fun onDestroy() {
        if (isFinishing) wipeProfiles(live = true)
        SoundFx.stopAll()
        videoBg.release()
        DownloadEngine.listener = null
        DownloadEngine.cookieProvider = null
        exitFullscreen()
        persistNow()
        allTabs.forEach { destroyWebView(it) }
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UI wiring
    // ══════════════════════════════════════════════════════════════════════════

    /** Window picture + translucent bars/sheets + optional transparent web pages. */
    private fun applyAppTheme() {
        ThemePrefs.applyToWindow(this, videoOverlay = true)
        videoBg.refresh()
        fun col(attr: Int) = MaterialColors.getColor(b.root, attr)
        // Floating dock + search bar: rounded shapes, tinted (slightly see-through over a picture).
        b.bottomBar.backgroundTintList = ColorStateList.valueOf(
            ThemePrefs.chrome(this, col(com.google.android.material.R.attr.colorSurfaceContainer)))
        b.urlCard.setCardBackgroundColor(
            ThemePrefs.chrome(this, col(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0xE6))
        b.switcherPanel.setCardBackgroundColor(
            ThemePrefs.chrome(this, col(com.google.android.material.R.attr.colorSurfaceContainerLow), 0xF2))
        allTabs.forEach { it.webView?.let { wv -> applyWebBackground(wv) } }
        // Home logo + "NeZu Space" title follow the picture opacity (Settings > Background).
        val homeAlpha = if (ThemePrefs.hasBackground(this)) (ThemePrefs.opacity(this) / 100f).coerceIn(0.1f, 1f) else 1f
        b.homeLogo.alpha = homeAlpha
        b.homeTitle.alpha = homeAlpha
    }

    private fun applyWebBackground(wv: WebView) {
        val see = ThemePrefs.hasBackground(this) && ThemePrefs.webTransparent(this)
        wv.setBackgroundColor(if (see) Color.TRANSPARENT else Color.WHITE)
    }

    private fun setupListeners() {
        b.urlInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate(v.text.toString()); true
            } else false
        }
        b.btnReload.setOnClickListener    { current?.webView?.reload() }
        b.btnBack.setOnClickListener      { current?.webView?.takeIf { it.canGoBack() }?.goBack() }
        b.btnForward.setOnClickListener   { current?.webView?.takeIf { it.canGoForward() }?.goForward() }
        b.btnHome.setOnClickListener      { showHome() }
        b.btnNewInPanel.setOnClickListener { newTab() }
        b.switcherScrim.setOnClickListener { hideSwitcher() }
        // Tab button: tap = tab panel, long press = new tab, swipe sideways = previous / next tab.
        b.btnTabs.setOnClickListener      { toggleSwitcher() }
        b.btnTabs.setOnTouchListener(TabButtonTouchListener(
            context    = this,
            moveTarget = { b.btnTabs.parent as? View },
            onLongPress = { newTab() },
            onSwipe    = { d -> switchTabBy(d) },
        ))
        b.btnProfiles.setOnClickListener  { showProfileSwitcher() }
        b.btnMenu.setOnClickListener      { showMenu() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Animations
    // ══════════════════════════════════════════════════════════════════════════

    /** Picture of what is on screen inside [v]. */
    private fun capture(v: View, cb: (Bitmap?) -> Unit) {
        if (v.width == 0 || v.height == 0) { cb(null); return }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        try {
            PixelCopy.request(
                window, Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height), bmp,
                { r -> cb(if (r == PixelCopy.SUCCESS) bmp else runCatching { v.drawToBitmap() }.getOrNull()) },
                Handler(Looper.getMainLooper())
            )
        } catch (_: Exception) {
            cb(runCatching { v.drawToBitmap() }.getOrNull())
        }
    }

    private fun addOverlay(v: View) {
        b.contentRoot.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * Book page turn. The old page curls like a page of a book and rolls away, revealing the new one.
     * [change] swaps the content while the picture of the old page covers it.
     */
    private fun flipPage(forward: Boolean, sound: Boolean = true, change: () -> Unit) {
        if (sound) SoundFx.play(this, SoundFx.Event.CHANGE)
        if (!animOn() || busy) { change(); return }
        beginBusy()
        capture(b.pageArea) { bmp ->
            if (bmp == null) { busy = false; change(); return@capture }
            val curl = PageCurlView(this, bmp, forward)
            addOverlay(curl)
            try { change() } catch (t: Throwable) {
                b.contentRoot.removeView(curl); busy = false; throw t
            }
            curl.start(FLIP_MS + 200) {
                b.contentRoot.removeView(curl)
                if (!bmp.isRecycled) bmp.recycle()
                busy = false
            }
        }
    }

    /** Whole page turns to dust while [change] shows what is behind it. */
    private fun dustOutPage(change: () -> Unit) {
        if (!animOn()) { SoundFx.play(this, SoundFx.Event.DELETE); change(); return }
        if (busy) { whenIdle { dustOutPage(change) }; return }
        SoundFx.play(this, SoundFx.Event.DELETE)
        beginBusy()
        capture(b.pageArea) { bmp ->
            if (bmp == null) { busy = false; change(); return@capture }
            val loc = IntArray(2)
            b.pageArea.getLocationOnScreen(loc)
            val dust = DustView(this, bmp, loc[0], loc[1], false)
            addOverlay(dust)
            try { change() } catch (t: Throwable) {
                b.contentRoot.removeView(dust); busy = false; throw t
            }
            dust.start(DUST_MS) { b.contentRoot.removeView(dust); bmp.recycle(); busy = false }
        }
    }

    /** The page area together with the window picture behind it, so dust makes up the WHOLE page. */
    private fun pageBitmapWithBackground(): Bitmap? {
        val w = b.pageArea.width; val h = b.pageArea.height
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val loc = IntArray(2)
        b.pageArea.getLocationInWindow(loc)
        val decor = window.decorView
        decor.background?.constantState?.newDrawable()?.mutate()?.let { bg ->
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            bg.setBounds(0, 0, decor.width, decor.height)
            bg.draw(c)
            c.restore()
        }
        b.pageArea.draw(c)
        return bmp
    }

    /** Reverse dust: [change] builds the new page, dust then gathers into it. */
    private fun dustInPage(change: () -> Unit) {
        if (!animOn()) { SoundFx.play(this, SoundFx.Event.CREATE); change(); return }
        if (busy) { whenIdle { dustInPage(change) }; return }
        SoundFx.play(this, SoundFx.Event.CREATE)
        beginBusy()
        b.pageArea.visibility = View.INVISIBLE
        try { change() } catch (t: Throwable) {
            b.pageArea.visibility = View.VISIBLE; busy = false; throw t
        }
        b.pageArea.doOnPreDraw {
            val bmp = runCatching { pageBitmapWithBackground() }.getOrNull()
            if (bmp == null) {
                b.pageArea.visibility = View.VISIBLE; busy = false
                return@doOnPreDraw
            }
            val loc = IntArray(2)
            b.pageArea.getLocationOnScreen(loc)
            val dust = DustView(this, bmp, loc[0], loc[1], true)
            addOverlay(dust)
            dust.start(DUST_MS) {
                b.pageArea.visibility = View.VISIBLE
                b.contentRoot.removeView(dust); bmp.recycle(); busy = false
            }
        }
    }

    /** A single view (card, row, tile) turns to dust. [atStart] runs when the dust begins, [atEnd] when it is gone. */
    private fun dustOutView(v: View, atStart: () -> Unit = {}, atEnd: () -> Unit = {}, backdrop: Int? = null) {
        SoundFx.play(this, SoundFx.Event.DELETE)
        val host = v.rootView as? ViewGroup
        val bmp = if (animOn() && host != null && v.width > 0 && v.height > 0)
            runCatching {
                // With a backdrop the whole row (not just its thin text) turns to dust, so it is easy to see.
                val bm = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bm)
                if (backdrop != null) cv.drawColor(backdrop)
                v.draw(cv)
                bm
            }.getOrNull() else null
        if (bmp == null || host == null) { atStart(); atEnd(); return }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val dust = DustView(this, bmp, loc[0], loc[1], false)
        host.addView(dust, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.alpha = 0f
        atStart()
        dust.start(DUST_MS) { host.removeView(dust); bmp.recycle(); atEnd() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Home screen
    // ══════════════════════════════════════════════════════════════════════════

    private fun showHome() {
        val tab = current ?: return
        tab.homeShown = true
        b.urlInput.clearFocus(); hideKeyboard()
        hideSwitcher(animate = false)
        applyHomeState(animate = true)
        applyChrome()
    }

    private fun hideHome() {
        current?.homeShown = false
        applyHomeState(animate = false)
        applyChrome()
    }

    private fun applyHomeState(animate: Boolean = false) {
        val show = current?.homeShown == true
        b.homeView.visibility = if (show) View.VISIBLE else View.GONE
        b.webContainer.visibility = if (show) View.INVISIBLE else View.VISIBLE
        if (show) {
            refreshHome()
            if (animate && animOn()) {
                b.homeView.alpha = 0f
                b.homeView.animate().alpha(1f).setDuration(200).start()
            } else b.homeView.alpha = 1f
        }
    }

    private fun refreshHome() {
        b.homeProfileName.text = currentProfile?.name.orEmpty()
        homeAdapter.submit(currentProfile?.bookmarks.orEmpty())
    }

    private fun openInCurrent(url: String) {
        val tab = current ?: return
        tab.url = url
        tab.loaded = true
        tab.homeShown = false
        applyHomeState()
        tab.webView?.loadUrl(prepareUrl(tab, url))
        applyChrome()
    }

    private fun addCurrentToHome() {
        val tab = current ?: return
        showBookmarkDialog(null, tab.title, tab.url)
    }

    private fun showBookmarkDialog(edit: Bookmark?, presetTitle: String = "", presetUrl: String = "") {
        val prof = currentProfile ?: return
        val view = layoutInflater.inflate(R.layout.dialog_bookmark, null)
        val nameIn = view.findViewById<TextInputEditText>(R.id.bmNameInput)
        val urlIn = view.findViewById<TextInputEditText>(R.id.bmUrlInput)
        val preview = view.findViewById<ShapeableImageView>(R.id.bmIconPreview)
        preview.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(RelativeCornerSize(0.5f)).build()
        nameIn.setText(edit?.title ?: presetTitle)
        urlIn.setText(edit?.url ?: presetUrl)

        pendingIcon = edit?.iconUri
        createdIcons.clear()
        var saved = false

        fun refreshPreview() {
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MaterialColors.getColor(preview,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest))
            }
            val ic = pendingIcon
            val host = siteHost(urlIn.text?.toString()?.trim())
            val logo = if (ic == null && host != null) FaviconStore.get(this, host) else null
            preview.setImageDrawable(null)
            when {
                ic != null   -> runCatching { preview.setImageURI(Uri.parse(ic)) }
                logo != null -> preview.setImageBitmap(logo)
            }
        }
        shortcutIconRefresh = { refreshPreview() }
        refreshPreview()
        view.findViewById<View>(R.id.bmIconChoose).setOnClickListener {
            pickShortcutIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        view.findViewById<View>(R.id.bmIconReset).setOnClickListener { pendingIcon = null; refreshPreview() }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (edit == null) "Add to Home" else "Edit shortcut")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                var url = urlIn.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.contains("://")) url = "https://$url"
                val title = nameIn.text?.toString()?.trim().orEmpty()
                    .ifBlank { siteHost(url) ?: url }
                if (edit != null) {
                    edit.title = title; edit.url = url
                    if (edit.iconUri != pendingIcon) { deleteImageFile(edit.iconUri); edit.iconUri = pendingIcon }
                } else prof.bookmarks.add(Bookmark(title = title, url = url, iconUri = pendingIcon))
                saved = true
                // No own picture: take the picture of the website.
                if (pendingIcon == null) siteHost(url)?.let { h ->
                    FaviconStore.fetch(this, h, url, force = true) { refreshHome() }
                }
                refreshHome(); persist()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                val keep = if (saved) pendingIcon else null
                createdIcons.filter { it != keep }.forEach { deleteImageFile(it) }
                createdIcons.clear()
                shortcutIconRefresh = null
            }
            .show()
    }

    /** Long press on a home shortcut. */
    private fun showTileMenu(bm: Bookmark, tile: View) {
        val pm = PopupMenu(this, tile)
        pm.menu.apply {
            add(0, 1, 0, "Open in background").setIcon(R.drawable.ic_tab_new)
            add(0, 2, 1, "Open in new tab").setIcon(R.drawable.ic_open_in_new)
            add(0, 3, 2, "Sort").setIcon(R.drawable.ic_sort)
            add(0, 4, 3, "Edit").setIcon(R.drawable.ic_edit)
            add(0, 5, 4, "Delete").setIcon(R.drawable.ic_delete)
            add(0, 6, 5, "Copy link").setIcon(R.drawable.ic_content_copy)
            add(0, 7, 6, "Share").setIcon(R.drawable.ic_share)
            add(0, 8, 7, "Add to phone home screen").setIcon(R.drawable.ic_add_home_screen)
        }
        pm.setForceShowIcon(true)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openInBackground(bm.url)
                2 -> openInNewTab(bm.url)
                3 -> showSortDialog()
                4 -> showBookmarkDialog(bm)
                5 -> dustOutView(tile, atStart = {
                    currentProfile?.bookmarks?.remove(bm)
                    deleteImageFile(bm.iconUri)
                    homeAdapter.remove(bm)
                    persist()
                })
                6 -> copyText("Link", bm.url)
                7 -> shareText(bm.url)
                8 -> pinBookmarkToPhone(bm)
            }
            true
        }
        pm.show()
    }

    // ── Shortcuts on the phone's own home screen ─────────────────────────────

    private fun pinToPhone(id: String, label: String, url: String?, profile: AppProfile,
                           title: String, color: Int, pictureUri: String?, host: String?) {
        val icon = PhoneShortcuts.icon(this, title, color, pictureUri, host)
        val ok = runCatching { PhoneShortcuts.pin(this, id, label, url, profile.id, icon) }.getOrDefault(false)
        if (!ok) toast("Your launcher cannot add shortcuts to the home screen")
    }

    private fun pinBookmarkToPhone(bm: Bookmark) {
        val prof = currentProfile ?: return
        val host = runCatching { Uri.parse(bm.url).host }.getOrNull()?.removePrefix("www.")
        pinToPhone("bm_${prof.id}_${bm.id}", bm.title.ifBlank { host.orEmpty() }, bm.url, prof,
            bm.title.ifBlank { host.orEmpty() }, prof.color, bm.iconUri, host)
    }

    private fun pinCurrentPageToPhone() {
        val tab = current ?: return
        val prof = currentProfile ?: return
        if (tab.homeShown || tab.url.isBlank()) { pinProfileToPhone(prof); return }
        val host = siteHost(tab.url)
        val label = tab.title.ifBlank { host.orEmpty() }
        pinToPhone("pg_${prof.id}_${tab.url.hashCode()}", label, tab.url, prof, label, prof.color, null, host)
    }

    private fun pinProfileToPhone(p: AppProfile) {
        pinToPhone("profile_${p.id}", p.name, null, p, p.name, p.color, p.avatarUri, null)
    }

    // ── First start: terms and privacy policy ────────────────────────────────

    private fun showTerms() {
        val tv = TextView(this).apply {
            text = LegalText.TEXT
            setTextAppearance(R.style.TextAppearance_Nezu_BodyMedium)
            setLineSpacing(0f, 1.15f)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Terms & Privacy Policy")
            .setView(sv)
            .setCancelable(false)
            .setPositiveButton("I agree") { _, _ ->
                getSharedPreferences("nezu_app", MODE_PRIVATE).edit().putBoolean("terms_ok", true).apply()
            }
            .setNegativeButton("Decline") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showSortDialog() {
        val prof = currentProfile ?: return
        val labels = arrayOf("Name A to Z", "Name Z to A", "Oldest first", "Newest first")
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort shortcuts")
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> prof.bookmarks.sortBy { it.title.lowercase() }
                    1 -> prof.bookmarks.sortByDescending { it.title.lowercase() }
                    2 -> prof.bookmarks.sortBy { it.addedAt }
                    3 -> prof.bookmarks.sortByDescending { it.addedAt }
                }
                refreshHome(); persist()
            }.show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Open / copy / share helpers and the long-press menus of web pages
    // ══════════════════════════════════════════════════════════════════════════

    private fun copyText(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied")
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, null))
    }

    private fun openInNewTab(url: String) {
        hideSwitcher(animate = false)
        dustInPage { val t = addTab(url); attachTab(t); persist() }
    }

    /** New tab that loads in the background; the page you are on stays. */
    private fun openInBackground(url: String) {
        val t = addTab(url)
        t.webView = createWebView(t).also { it.loadUrl(prepareUrl(t, url)) }
        t.loaded = true
        refreshSwitcher(); persist()
        toast("Opened in background")
    }

    private var touchX = 0f
    private var touchY = 0f

    /** Invisible 1 px view that popup menus are attached to (so they open where you touched). */
    private val menuAnchor: View by lazy {
        View(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            b.contentRoot.addView(this)
        }
    }

    private fun anchorAt(rawX: Float, rawY: Float): View {
        val loc = IntArray(2)
        b.contentRoot.getLocationOnScreen(loc)
        menuAnchor.translationX = rawX - loc[0]
        menuAnchor.translationY = rawY - loc[1]
        return menuAnchor
    }

    /** Long press inside a page. Returns true when a menu was shown (images and links). */
    private fun handleWebLongPress(tab: Tab, wv: WebView): Boolean {
        val hit = wv.hitTestResult
        val extra = hit.extra ?: return false
        return when (hit.type) {
            WebView.HitTestResult.IMAGE_TYPE -> { showImageMenu(tab, wv, extra, null); true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Handler(Looper.getMainLooper()) { m ->
                    showImageMenu(tab, wv, extra, m.data?.getString("url")?.takeIf { it.startsWith("http") })
                    true
                }.obtainMessage()
                wv.requestFocusNodeHref(msg)
                true
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE ->
                if (extra.startsWith("http")) { showLinkMenu(extra); true } else false
            else -> false
        }
    }

    private fun showImageMenu(tab: Tab, wv: WebView, img: String, link: String?) {
        val pm = PopupMenu(this, anchorAt(touchX, touchY))
        pm.menu.apply {
            add(0, 1, 0, "View image").setIcon(R.drawable.ic_visibility)
            add(0, 2, 1, "Download image").setIcon(R.drawable.ic_download)
            add(0, 3, 2, "Share image").setIcon(R.drawable.ic_share)
            add(0, 4, 3, "Search by image").setIcon(R.drawable.ic_image_search)
            if (link != null) add(0, 5, 4, "Open link in new tab").setIcon(R.drawable.ic_open_in_new)
            add(0, 6, 5, "Copy image link").setIcon(R.drawable.ic_content_copy)
            add(0, 7, 6, "Page info").setIcon(R.drawable.ic_info)
            add(0, 8, 7, "Mark as ad").setIcon(R.drawable.ic_block)
        }
        pm.setForceShowIcon(true)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openInNewTab(img)
                2 -> downloadImage(tab, img)
                3 -> shareImage(tab, img)
                4 -> if (img.startsWith("http")) openInNewTab("https://lens.google.com/uploadbyurl?url=" + Uri.encode(img))
                     else toast("Search by image needs an online picture")
                5 -> link?.let { l -> openInNewTab(l) }
                6 -> copyText("Image link", img)
                7 -> showPageInfo(tab)
                8 -> markAsAd(wv, img)
            }
            true
        }
        pm.show()
    }

    private fun showLinkMenu(link: String) {
        val pm = PopupMenu(this, anchorAt(touchX, touchY))
        pm.menu.apply {
            add(0, 1, 0, "Open in new tab").setIcon(R.drawable.ic_open_in_new)
            add(0, 2, 1, "Open in background").setIcon(R.drawable.ic_tab_new)
            add(0, 3, 2, "Copy link").setIcon(R.drawable.ic_content_copy)
            add(0, 4, 3, "Share link").setIcon(R.drawable.ic_share)
        }
        pm.setForceShowIcon(true)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openInNewTab(link)
                2 -> openInBackground(link)
                3 -> copyText("Link", link)
                4 -> shareText(link)
            }
            true
        }
        pm.show()
    }

    private fun imageFileName(url: String, mime: String): String {
        val type = mime.substringBefore(';').trim().lowercase()
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
            ?: Regex("\\.(jpe?g|png|gif|webp|bmp|avif)(?:$|\\?)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)?.lowercase()
            ?: "jpg"
        val seg = Uri.parse(url).lastPathSegment.orEmpty().substringBefore('?')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = if (seg.length in 3..60 && seg.contains('.')) seg.substringBeforeLast('.')
                   else "image_${System.currentTimeMillis()}"
        return "$base.$ext"
    }

    private fun downloadImage(tab: Tab, url: String) {
        if (!url.startsWith("http")) { toast("This image cannot be downloaded"); return }
        val ua = tab.webView?.settings?.userAgentString.orEmpty()
        val cookie = CookieManager.getInstance().getCookie(url)
        toast("Downloading image")
        Thread {
            var mime = ""
            runCatching { Net.open(url, ua, tab.url, cookie)?.let { mime = it.contentType.orEmpty(); it.disconnect() } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                startDownload(tab, url, ua, "", mime.substringBefore(';').ifBlank { "image/jpeg" },
                    -1L, imageFileName(url, mime))
            }
        }.start()
    }

    /** Downloads the picture to the cache and opens the share sheet with the picture itself. */
    private fun shareImage(tab: Tab, url: String) {
        if (!url.startsWith("http")) { shareText(url); return }
        val ua = tab.webView?.settings?.userAgentString.orEmpty()
        val cookie = CookieManager.getInstance().getCookie(url)
        toast("Preparing image")
        Thread {
            var file: java.io.File? = null
            var mime = "image/*"
            runCatching {
                val c = Net.open(url, ua, tab.url, cookie) ?: return@runCatching
                mime = c.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { "image/*" }
                val bytes = (try { Net.readBytes(c, 25_000_000) } finally { c.disconnect() }) ?: return@runCatching
                val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }
                    ?.forEach { it.delete() }
                file = java.io.File(dir, imageFileName(url, mime)).also { it.writeBytes(bytes) }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val f = file
                if (f == null) { toast("Could not load the image"); return@runOnUiThread }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null))
            }
        }.start()
    }

    private fun showPageInfo(tab: Tab) {
        val url = tab.webView?.url ?: tab.url
        val host = siteHost(url) ?: "-"
        MaterialAlertDialogBuilder(this)
            .setTitle("Page info")
            .setMessage("Title: ${tab.title.ifBlank { host }}\n\nAddress: $url\n\n" +
                "Connection: ${if (url.startsWith("https://")) "Secure (HTTPS)" else "Not secure"}\n\n" +
                "Ad blocker: ${if (AdBlocker.enabled) "On" else "Off"}")
            .setPositiveButton("Close", null)
            .show()
    }

    /** Hides this picture now and blocks it on every page from now on. */
    private fun markAsAd(wv: WebView, imgUrl: String) {
        AdBlocker.markUrl(this, imgUrl)
        val u = org.json.JSONObject.quote(imgUrl)
        wv.evaluateJavascript(
            "(function(u){document.querySelectorAll('img,source').forEach(function(i){" +
            "var s=i.currentSrc||i.src||i.srcset||'';if(s===u||s.indexOf(u)===0){i.style.display='none';}});})($u);", null)
        toast("Marked as ad")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Don't sleep
    // ══════════════════════════════════════════════════════════════════════════

    private val awakeHandler = Handler(Looper.getMainLooper())
    private val awakeOff = Runnable {
        if (customView == null) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private var lastAwakeReset = 0L

    /** Applies Settings -> Don't sleep: off, always, or N minutes after the last touch. */
    private fun applyKeepAwake() {
        awakeHandler.removeCallbacks(awakeOff)
        lastAwakeReset = android.os.SystemClock.uptimeMillis()
        val m = ThemePrefs.keepAwake(this)
        when {
            customView != null || m == -1 -> window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            m == 0 -> window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                awakeHandler.postDelayed(awakeOff, m * 60_000L)
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (ThemePrefs.keepAwake(this) > 0 &&
            android.os.SystemClock.uptimeMillis() - lastAwakeReset > 3000) applyKeepAwake()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Profiles
    // ══════════════════════════════════════════════════════════════════════════

    private fun showProfileSwitcher() {
        val sheetView = layoutInflater.inflate(R.layout.sheet_profiles, null)
        val rv = sheetView.findViewById<RecyclerView>(R.id.profileList)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = ProfileAdapter(
            profiles    = allProfiles,
            currentId   = { currentProfile?.id },
            tabCount    = { p -> allTabs.count { it.profileId == p.id } },
            onClick     = { p -> profileDialog?.dismiss(); switchProfile(p) },
            onLongClick = { p, row -> showProfileOptions(p, row) },
        )
        profileAdapter = adapter
        rv.adapter = adapter
        // Swipe a profile sideways to delete it.
        rv.addOnItemTouchListener(SwipeDismissListener { pos, row, restore ->
            val p = adapter.profileAt(pos)
            if (p == null) restore()
            else confirmDeleteProfile(p, row, onCancel = restore)
        })
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(sheetView)
        sheetView.findViewById<View>(R.id.btnNewProfile).setOnClickListener {
            sheet.dismiss(); showCreateProfileDialog()
        }
        sheet.setOnDismissListener { if (profileDialog === sheet) profileDialog = null }
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        profileDialog = sheet
        sheet.show()
    }

    private fun showCreateProfileDialog(editTarget: AppProfile? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tab_edit, null)
        val nameInput  = dialogView.findViewById<TextInputEditText>(R.id.nameInput)
        val colorGrid  = dialogView.findViewById<android.widget.GridLayout>(R.id.colorGrid)
        if (editTarget != null) nameInput.setText(editTarget.name)

        var chosen = editTarget?.color ?: PALETTE[allProfiles.size % PALETTE.size]
        val ring = MaterialColors.getColor(dialogView, com.google.android.material.R.attr.colorOnSurface)
        val dots = mutableListOf<View>()

        fun refresh() {
            dots.forEachIndexed { i, d ->
                d.background = swatch(PALETTE[i], PALETTE[i] == chosen, ring)
            }
        }
        PALETTE.forEach { c ->
            val d = View(this)
            d.layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = dp(44); height = dp(44); setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            d.setOnClickListener { chosen = c; refresh() }
            colorGrid.addView(d); dots.add(d)
        }
        refresh()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (editTarget != null) "Edit profile" else "New profile")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { "Profile" }
                if (editTarget != null) {
                    editTarget.name = name; editTarget.color = chosen
                    updateProfileBar(); refreshHome(); profileAdapter?.notifyDataSetChanged(); persist()
                } else {
                    createProfile(name, chosen)
                }
            }
            .setNegativeButton("Cancel", null)
        if (editTarget != null) {
            builder.setNeutralButton("Set avatar") { _, _ ->
                profileAvatarTarget = editTarget
                pickProfileAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        builder.show()
    }

    private fun showProfileOptions(p: AppProfile, row: View) {
        val items = mutableListOf("Rename or recolor", "Change avatar", "Add to phone home screen")
        if (allProfiles.size > 1) items.add("Delete profile")
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Rename or recolor" -> showCreateProfileDialog(editTarget = p)
                    "Change avatar"     -> { profileAvatarTarget = p
                        pickProfileAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    "Add to phone home screen" -> pinProfileToPhone(p)
                    "Delete profile"    -> confirmDeleteProfile(p, row)
                }
            }.show()
    }

    private fun createProfile(name: String, color: Int) {
        val p = AppProfile(name = name, color = color)
        allProfiles.add(p)
        if (profilesSupported) runCatching {
            ProfileStore.getInstance().getOrCreateProfile(p.webkitProfileName)
        }
        dustInPage { applyProfile(p) }
    }

    private fun switchProfile(p: AppProfile, forward: Boolean? = null) {
        if (p === currentProfile) return
        val fwd = forward ?: (allProfiles.indexOf(p) > allProfiles.indexOf(currentProfile))
        if (b.switcher.visibility == View.VISIBLE) {      // let the panel slide away first
            hideSwitcher { flipPage(fwd) { applyProfile(p) } }
            return
        }
        flipPage(fwd) { applyProfile(p) }
    }

    private fun switchProfileBy(d: Int) {
        if (busy) return
        val i = allProfiles.indexOf(currentProfile)
        val p = allProfiles.getOrNull(i + d) ?: return
        switchProfile(p, forward = d > 0)
    }

    /** The actual switch. Runs under the animation overlay. */
    private fun applyProfile(p: AppProfile) {
        current?.webView?.onPause()
        currentProfile = p
        updateProfileBar()
        if (profileTabs.isEmpty()) addTab()
        sortPinnedFirst()
        val tabs = profileTabs
        showTab(tabs.firstOrNull { it.id == lastTabOfProfile[p.id] } ?: tabs.first())
        refreshHome()
        persist()
        applySecureFlag()
        enforceLock()
    }

    private fun confirmDeleteProfile(p: AppProfile, row: View?, onCancel: () -> Unit = {}) {
        if (allProfiles.size <= 1) {
            toast("At least one profile is required"); onCancel(); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${p.name}\"?")
            .setMessage("All tabs and data in this profile will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ ->
                if (p === currentProfile) {
                    // Page on screen belongs to this profile: dialogs leave first, then the page turns to dust.
                    profileDialog?.dismiss()
                    b.root.postDelayed({
                        if (allProfiles.contains(p)) dustOutPage { removeProfileData(p, animatedSwitch = false) }
                    }, 320)
                    return@setPositiveButton
                }
                val remove = {
                    val pos = allProfiles.indexOf(p)
                    removeProfileData(p)
                    if (pos >= 0) profileAdapter?.notifyItemRemoved(pos)
                }
                if (row != null) dustOutView(row, atStart = remove) else { SoundFx.play(this, SoundFx.Event.DELETE); remove() }
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun removeProfileData(p: AppProfile, animatedSwitch: Boolean = true) {
        val wasCurrent = currentProfile?.id == p.id
        allTabs.filter { it.profileId == p.id }.forEach { destroyWebView(it) }
        allTabs.removeAll { it.profileId == p.id }
        if (profilesSupported) b.root.postDelayed({
            runCatching { ProfileStore.getInstance().deleteProfile(p.webkitProfileName) }
        }, 800)
        p.downloads.toList().forEach { DownloadEngine.discard(it) }
        HistoryStore.clear(this, p.id)
        allProfiles.remove(p)
        if (wasCurrent) {
            val next = allProfiles.first()
            if (animatedSwitch) flipPage(false, sound = false) { applyProfile(next) } else applyProfile(next)
        } else persist()
    }

    private fun updateProfileBar() {
        val p = currentProfile ?: return
        b.profileInitialBar.text = p.name.firstOrNull()?.uppercase() ?: "P"
        b.btnProfiles.imageTintList = ColorStateList.valueOf(p.color)
        b.profileInitialBar.setTextColor(
            if (androidx.core.graphics.ColorUtils.calculateLuminance(p.color) > 0.5) Color.BLACK else Color.WHITE)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Tab menu
    // ══════════════════════════════════════════════════════════════════════════

    /** Main menu: a Material 3 bottom sheet with the page on top, quick actions and the full list. */
    private fun showMenu() {
        val tab = current ?: return
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_menu, null)

        // Header: the page (or NeZu Space home) and the profile
        val host = siteHost(tab.url)
        val onHome = tab.homeShown || tab.url.isBlank()
        v.findViewById<TextView>(R.id.menuTitle).text = when {
            onHome                 -> "NeZu Space"
            tab.title.isNotBlank() -> tab.title
            else                   -> host ?: tab.url
        }
        v.findViewById<TextView>(R.id.menuSubtitle).text =
            if (onHome || host == null) (currentProfile?.name ?: tab.name) else "${currentProfile?.name ?: tab.name} · $host"
        val headerIcon = v.findViewById<ShapeableImageView>(R.id.menuIcon)
        val favicon = if (!onHome && host != null) FaviconStore.get(this, host) else null
        if (favicon != null) headerIcon.setImageBitmap(favicon)
        else { headerIcon.setPadding(0, 0, 0, 0); headerIcon.scaleType = ImageView.ScaleType.CENTER_CROP; headerIcon.setImageResource(R.mipmap.ic_launcher) }

        fun act(id: Int) { sheet.dismiss(); onMenuAction(id, tab) }

        // Quick actions, 4 per row
        val quick = listOf(
            Triple(R.id.action_home, R.drawable.ic_home, "Home"),
            Triple(R.id.action_add_home, R.drawable.ic_bookmark_add, "Add to Home"),
            Triple(R.id.action_downloads, R.drawable.ic_download, "Downloads"),
            Triple(R.id.action_history, R.drawable.ic_history, "History"),
            Triple(R.id.action_copy, R.drawable.ic_content_copy, "Copy link"),
            Triple(R.id.action_site_info, R.drawable.ic_info, "Site info"),
            Triple(R.id.action_clear_data, R.drawable.ic_delete_sweep, "Clear data"),
            Triple(R.id.action_settings, R.drawable.ic_settings, "Settings"),
            Triple(R.id.action_view_source, R.drawable.ic_code, "View source"),
            Triple(R.id.action_extensions, R.drawable.ic_extension, "Extensions"),
        )
        val quickBox = v.findViewById<android.widget.LinearLayout>(R.id.menuQuick)
        quick.chunked(4).forEach { rowItems ->
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            rowItems.forEach { (id, icon, label) ->
                val cell = layoutInflater.inflate(R.layout.item_menu_quick, row, false)
                cell.findViewById<ImageView>(R.id.quickIcon).setImageResource(icon)
                cell.findViewById<TextView>(R.id.quickLabel).text = label
                cell.contentDescription = label
                cell.setOnClickListener { act(id) }
                row.addView(cell)
            }
            // keep the last row's cells the same size as the full rows above
            repeat(4 - rowItems.size) {
                val gap = layoutInflater.inflate(R.layout.item_menu_quick, row, false)
                gap.visibility = View.INVISIBLE
                row.addView(gap)
            }
            quickBox.addView(row)
        }

        // Full list
        val list = listOf(
            Triple(R.id.action_pin, R.drawable.ic_pin, if (tab.pinned) "Unpin tab" else "Pin tab"),
            Triple(R.id.action_edit, R.drawable.ic_edit, "Edit tab"),
            Triple(R.id.action_avatar, R.drawable.ic_image, "Set tab avatar"),
            Triple(R.id.action_add_phone, R.drawable.ic_add_home_screen, "Add to phone home screen"),
            Triple(R.id.action_search_engine, R.drawable.ic_travel_explore, "Search engine"),
            Triple(R.id.action_cookies, R.drawable.ic_cookie, "Cookies"),
            Triple(R.id.action_close, R.drawable.ic_tab_close, "Close tab"),
        )
        val listBox = v.findViewById<android.widget.LinearLayout>(R.id.menuList)
        val error = MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorError)
        list.forEach { (id, icon, label) ->
            val row = layoutInflater.inflate(R.layout.item_menu_row, listBox, false)
            val iv = row.findViewById<ImageView>(R.id.rowIcon)
            val tv = row.findViewById<TextView>(R.id.rowLabel)
            iv.setImageResource(icon)
            tv.text = label
            if (id == R.id.action_close) {
                iv.imageTintList = ColorStateList.valueOf(error); tv.setTextColor(error)
            }
            row.setOnClickListener { act(id) }
            listBox.addView(row)
        }

        sheet.setContentView(v)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private fun onMenuAction(id: Int, tab: Tab) {
        if (!allTabs.contains(tab)) return
        when (id) {
            R.id.action_home          -> showHome()
            R.id.action_add_home      -> addCurrentToHome()
            R.id.action_add_phone     -> pinCurrentPageToPhone()
            R.id.action_pin           -> togglePin(tab)
            R.id.action_edit          -> editTab(tab)
            R.id.action_avatar        -> pickAvatarForTab(tab)
            R.id.action_search_engine -> showSearchEngineDialog()
            R.id.action_downloads     -> showDownloadsSheet()
            R.id.action_copy          -> copyLink(tab)
            R.id.action_history       -> showHistory()
            R.id.action_cookies       -> showCookieMenu(tab)
            R.id.action_view_source   -> viewSource(tab)
            R.id.action_copy_source   -> copySource(tab)
            R.id.action_extensions    -> showExtensionsDialog()
            R.id.action_clear_data    -> showClearDataDialog()
            R.id.action_site_info     -> showSiteInfo(tab)
            R.id.action_close         -> closeTab(tab)
            R.id.action_settings      -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Search engine (one per profile)
    // ══════════════════════════════════════════════════════════════════════════

    private fun showSearchEngineDialog() {
        val prof = currentProfile ?: return
        val engines = SearchEngine.entries.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Search engine - ${prof.name}")
            .setSingleChoiceItems(
                engines.map { it.label }.toTypedArray(), engines.indexOf(prof.searchEngine)
            ) { d, i ->
                prof.searchEngine = engines[i]
                persist()
                applyChrome()
                toast("${engines[i].label} for all tabs in ${prof.name}")
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Tab avatar
    // ══════════════════════════════════════════════════════════════════════════

    private fun pickAvatarForTab(tab: Tab) {
        avatarPickTarget = tab
        pickTabAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Downloads per tab
    // ══════════════════════════════════════════════════════════════════════════

    private fun downloadManager() = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun fileExists(rec: DownloadRecord): Boolean =
        if (rec.isEngine) DownloadEngine.fileExists(rec)
        else try { downloadManager().openDownloadedFile(rec.dmId).close(); true }
        catch (_: Exception) { false }

    private fun isInProgress(rec: DownloadRecord) = rec.status == DownloadManager.STATUS_RUNNING ||
            rec.status == DownloadManager.STATUS_PENDING || rec.status == DownloadManager.STATUS_PAUSED

    /** Downloads of the current profile (all its tabs). Empty list shows an empty page with "Add download". */
    private fun showDownloadsSheet() {
        val prof = currentProfile ?: return

        // Finished downloads whose file was deleted from the phone are marked.
        var changed = false
        prof.downloads.forEach {
            if (it.status == DownloadManager.STATUS_SUCCESSFUL && !fileExists(it)) {
                it.status = DownloadRecord.STATUS_MISSING; changed = true
            }
        }
        if (changed) persist()

        val view = layoutInflater.inflate(R.layout.dialog_downloads, null)
        val rv = view.findViewById<RecyclerView>(R.id.dlList)
        val empty = view.findViewById<View>(R.id.dlEmpty)
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        var dlg: androidx.appcompat.app.AlertDialog? = null
        lateinit var adapter: DownloadHistAdapter
        val refresh = {
            val list = prof.downloads.sortedByDescending { it.addedAt }
            adapter.submit(list)
            empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            dlg?.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.visibility =
                if (list.isEmpty()) View.GONE else View.VISIBLE
        }
        adapter = DownloadHistAdapter(
            onClick = { rec -> showDownloadOptions(prof, rec, refresh) },
            onToggle = { rec -> toggleDownload(rec); adapter.refreshRows() },
        )
        rv.adapter = adapter

        val sheet = MaterialAlertDialogBuilder(this)
            .setTitle("Downloads - ${prof.name}")
            .setView(view)
            .setPositiveButton("Add download", null)
            .setNeutralButton("Clear history", null)
            .setNegativeButton("Close", null)
            .create()
        dlg = sheet
        sheet.show()
        refresh()
        // These two keep the sheet open.
        sheet.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            showAddDownloadDialog(prof) { refresh() }
        }
        sheet.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val gone = prof.downloads.filter { !isInProgress(it) }
            gone.forEach { DownloadEngine.discard(it) }
            prof.downloads.removeAll(gone.toSet())
            persist(); refresh()
            toast("Download history cleared")
        }

        // Live progress while the sheet is open.
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!sheet.isShowing || isDestroyed) return
                adapter.refreshRows()
                handler.postDelayed(this, 700)
            }
        }
        handler.postDelayed(tick, 700)
    }

    /** Paste a link: it is downloaded in the current profile. */
    private fun showAddDownloadDialog(prof: AppProfile, onAdded: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_add_download, null)
        val input = view.findViewById<TextInputEditText>(R.id.dlUrlInput)
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Add download")
            .setView(view)
            .setPositiveButton("Download", null)
            .setNeutralButton("Paste", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()

        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clip.isNullOrBlank()) toast("Clipboard is empty")
            else { input.setText(clip.trim()); input.setSelection(input.text?.length ?: 0) }
        }
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val url = normalizeDownloadUrl(input.text?.toString().orEmpty())
            if (url == null) { input.error = "Enter a valid link"; return@setOnClickListener }
            val tab = profileTabs.firstOrNull { it === current } ?: profileTabs.firstOrNull()
            if (tab == null) { dlg.dismiss(); return@setOnClickListener }
            val ua = tab.webView?.settings?.userAgentString
                ?: android.webkit.WebSettings.getDefaultUserAgent(this)
            dlg.dismiss()
            startDownload(tab, url, ua, "", "", -1L, showProgress = false, rename = true)
            toast("Download started")
            onAdded()
        }
    }

    private fun normalizeDownloadUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val u = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            t.contains('.') -> "https://$t"
            else -> return null
        }
        return if (Uri.parse(u).host.isNullOrBlank()) null else u
    }

    private fun toggleDownload(rec: DownloadRecord) {
        if (!rec.isEngine) return
        when (rec.status) {
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> DownloadEngine.pause(rec)
            DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_FAILED -> {
                ensureNotificationPermission()
                DownloadEngine.start(rec)
            }
        }
        persist()
    }

    private fun cancelDownload(prof: AppProfile, rec: DownloadRecord) {
        if (rec.isEngine) DownloadEngine.discard(rec)
        else runCatching { downloadManager().remove(rec.dmId) }
        prof.downloads.remove(rec)
        persist()
        toast("Download cancelled")
    }

    private fun showDownloadOptions(prof: AppProfile, rec: DownloadRecord, refresh: () -> Unit) {
        val st = rec.status
        val done = st == DownloadManager.STATUS_SUCCESSFUL
        val going = isInProgress(rec)
        val items = mutableListOf<String>()
        if (done) items.add("Open")
        if (rec.isEngine) when (st) {
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> items.add("Pause")
            DownloadManager.STATUS_PAUSED -> items.add("Resume")
            DownloadManager.STATUS_FAILED -> items.add("Retry")
        }
        items.add("Copy download link")
        if (!going && st != DownloadManager.STATUS_FAILED || (!rec.isEngine && st == DownloadManager.STATUS_FAILED))
            items.add("Download again")
        if (done) items.add("Delete file")
        if (going) items.add("Cancel download") else items.add("Remove from list")

        MaterialAlertDialogBuilder(this)
            .setTitle(rec.fileName)
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Open"               -> openDownloadedFile(rec)
                    "Pause", "Resume", "Retry" -> { toggleDownload(rec); refresh() }
                    "Copy download link" -> {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("download link", rec.url))
                        toast("Download link copied")
                    }
                    "Download again"     -> {
                        if (rec.status == DownloadRecord.STATUS_MISSING) {
                            DownloadEngine.discard(rec); prof.downloads.remove(rec)
                        }
                        val tab = current ?: return@setItems
                        val ua = rec.userAgent.ifBlank { tab.webView?.settings?.userAgentString.orEmpty() }
                        startDownload(tab, rec.url, ua, "", rec.mimeType, rec.totalBytes,
                            fileNameOverride = rec.fileName, showProgress = false)
                        toast("Download started")
                        refresh()
                    }
                    "Delete file"        -> confirmDeleteFile(prof, rec, refresh)
                    "Cancel download"    -> { cancelDownload(prof, rec); refresh() }
                    "Remove from list"   -> {
                        DownloadEngine.discard(rec); prof.downloads.remove(rec); persist(); refresh()
                    }
                }
            }.show()
    }

    private fun confirmDeleteFile(prof: AppProfile, rec: DownloadRecord, refresh: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete file?")
            .setMessage("\"${rec.fileName}\" will be deleted from your phone.")
            .setPositiveButton("Delete") { _, _ ->
                if (rec.isEngine) DownloadEngine.deleteFile(rec)
                else runCatching { downloadManager().remove(rec.dmId) }
                prof.downloads.remove(rec); persist(); refresh()
                toast("File deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openDownloadedFile(rec: DownloadRecord) {
        val uri = if (rec.isEngine) DownloadEngine.uriOf(rec) else downloadManager().getUriForDownloadedFile(rec.dmId)
        if (uri != null && fileExists(rec)) {
            val mime = if (rec.isEngine) DownloadEngine.mimeFor(rec) else rec.fileName.mimeTypeGuess()
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) { toast("Cannot open file") }
        } else {
            toast("File not found")
        }
    }

    private fun String.mimeTypeGuess(): String = when (substringAfterLast('.', "").lowercase()) {
        "pdf"       -> "application/pdf"
        "jpg","jpeg"-> "image/jpeg"
        "png"       -> "image/png"
        "mp4"       -> "video/mp4"
        "mp3"       -> "audio/mpeg"
        "zip"       -> "application/zip"
        "apk"       -> "application/vnd.android.package-archive"
        else        -> "*/*"
    }

    // ── Download initiation ───────────────────────────────────────────────────

    /** The download belongs to the profile of [tab]. [rename]: take the file name from the server's answer. */
    private fun startDownload(tab: Tab, url: String, userAgent: String,
                              contentDisposition: String, mimeType: String, contentLength: Long,
                              fileNameOverride: String? = null,
                              showProgress: Boolean = true, rename: Boolean = false) {
        val prof = allProfiles.firstOrNull { it.id == tab.profileId } ?: currentProfile ?: return
        val fileName = fileNameOverride ?: DownloadEngine.fileNameFor(url, contentDisposition, mimeType)

        val rec = DownloadRecord(fileName = fileName, url = url, totalBytes = contentLength,
            mimeType = mimeType, userAgent = userAgent, referer = tab.url)
        prof.downloads.add(rec)
        ensureNotificationPermission()
        DownloadEngine.start(rec, rename = rename)
        persist()

        if (showProgress) showDownloadProgress(rec)
    }

    /** Cookies of the profile that owns [rec] (a profile has its own cookie jar, the default one is empty for it). */
    private fun cookieForDownload(rec: DownloadRecord): String? {
        val prof = allProfiles.firstOrNull { p -> p.downloads.any { it.id == rec.id } } ?: return null
        return runCatching {
            val cm = if (profilesSupported)
                ProfileStore.getInstance().getProfile(prof.webkitProfileName)?.cookieManager else null
            (cm ?: CookieManager.getInstance()).getCookie(rec.url)
        }.getOrNull()
    }

    /**
     * blob: links only exist inside the page, so the page is asked to read the blob and hand it over in pieces
     * (BlobBridge). The file name comes from the link's download="..." attribute (see DOWNLOAD_NAME_HOOK).
     */
    private fun saveBlob(tab: Tab, wv: WebView, url: String, mime: String?) {
        val prof = allProfiles.firstOrNull { it.id == tab.profileId } ?: currentProfile ?: return
        val type = mime.orEmpty().substringBefore(';').trim()
        val rec = DownloadRecord(fileName = DownloadEngine.fileNameFor("download", null, type),
            url = url, mimeType = type, referer = tab.url)
        // Read the blob straight away (the page may free it soon) and ask for the name the page chose.
        val grab = "(function(u,t){window.__nezuB=window.__nezuB||{};" +
            "window.__nezuB[t]=fetch(u).then(function(r){return r.blob();});" +
            "return window.__nezuName||'';})(${org.json.JSONObject.quote(url)},${org.json.JSONObject.quote(rec.id)})"
        wv.evaluateJavascript(grab) { raw ->
            val hinted = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                ?.let { DownloadEngine.cleanFileName(it) }.orEmpty()
            if (hinted.isNotBlank()) {
                rec.fileName = if (hinted.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 6 }) hinted
                    else DownloadEngine.fileNameFor(hinted, null, type)
            }
            prof.downloads.add(rec)
            if (!blobBridge.register(rec)) {
                rec.status = android.app.DownloadManager.STATUS_FAILED
                persist(); toast("Could not start the download"); return@evaluateJavascript
            }
            ensureNotificationPermission()
            persist()
            wv.evaluateJavascript(blobStreamJs(rec.id), null)
            showDownloadProgress(rec)
        }
    }

    private fun blobStreamJs(token: String): String {
        val t = org.json.JSONObject.quote(token)
        return "(function(t){" +
            "function fail(){try{NezuBlob.fail(t);}catch(e){}}" +
            "try{var p=window.__nezuB&&window.__nezuB[t]; if(!p){fail();return;}" +
            "p.then(function(b){" +
            "delete window.__nezuB[t];" +
            "var size=b.size,CH=393216,pos=0;" +
            "NezuBlob.size(t,size);" +
            "(function next(){" +
            "if(pos>=size){NezuBlob.end(t);return;}" +
            "var fr=new FileReader();" +
            "fr.onload=function(){var s=fr.result;NezuBlob.chunk(t,s.substring(s.indexOf(',')+1));pos+=CH;next();};" +
            "fr.onerror=fail;" +
            "fr.readAsDataURL(b.slice(pos,Math.min(pos+CH,size)));" +
            "})();" +
            "}).catch(fail);}catch(e){fail();}" +
            "})($t);"
    }

    /** data: links carry the file inside the link itself. */
    private fun saveDataUrl(tab: Tab, url: String) {
        val prof = allProfiles.firstOrNull { it.id == tab.profileId } ?: currentProfile ?: return
        val comma = url.indexOf(',')
        if (comma < 0) return
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val type = meta.removeSuffix(";base64").substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = runCatching {
            if (meta.endsWith(";base64")) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
        }.getOrNull() ?: run { toast("Could not read that file"); return }

        val rec = DownloadRecord(fileName = DownloadEngine.fileNameFor("download", null, type),
            url = "data:$type", mimeType = type, totalBytes = bytes.size.toLong())
        prof.downloads.add(rec)
        val out = DownloadEngine.openLocal(rec) ?: run { persist(); toast("Could not start the download"); return }
        ensureNotificationPermission()
        persist()
        Thread {
            val ok = runCatching { out.use { it.write(bytes) } }.isSuccess
            rec.downloadedBytes = bytes.size.toLong()
            DownloadEngine.finishLocal(rec, ok)
        }.start()
        toast("Download started")
    }

    private fun showDownloadProgress(rec: DownloadRecord) {
        val view    = layoutInflater.inflate(R.layout.dialog_download, null)
        val tvName  = view.findViewById<TextView>(R.id.dlFileName)
        val tvProg  = view.findViewById<TextView>(R.id.dlProgress)
        val tvSpeed = view.findViewById<TextView>(R.id.dlSpeed)
        val bar     = view.findViewById<LinearProgressIndicator>(R.id.dlProgressBar)

        tvName.text = rec.fileName
        bar.isIndeterminate = rec.totalBytes <= 0

        val prof = allProfiles.firstOrNull { p -> p.downloads.any { it === rec } } ?: currentProfile ?: return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Downloading")
            .setView(view)
            .setPositiveButton("Pause", null)
            .setNeutralButton("Cancel") { _, _ -> cancelDownload(prof, rec) }
            .setNegativeButton("Hide", null)
            .create()
        dialog.show()
        val pause = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        val cancel = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
        val hide = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
        pause.setOnClickListener { toggleDownload(rec) }

        val handler = Handler(Looper.getMainLooper())
        var toasted = false
        val poller = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || isDestroyed) return
                val total = rec.totalBytes
                val downloaded = rec.downloadedBytes
                val spd = rec.speed

                tvProg.text = if (total > 0)
                    "%.2f / %.2f MB".format(downloaded / 1_048_576.0, total / 1_048_576.0)
                else "%.2f MB".format(downloaded / 1_048_576.0)
                if (total > 0) {
                    bar.isIndeterminate = false
                    bar.setProgressCompat((downloaded * 100 / total).toInt().coerceIn(0, 100), true)
                }

                when (rec.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        bar.isIndeterminate = false
                        bar.setProgressCompat(100, true); tvSpeed.text = "Done"
                        pause.visibility = View.GONE; cancel.visibility = View.GONE
                        hide.text = "Close"
                        if (!toasted) {
                            toasted = true
                            Toast.makeText(this@MainActivity, "Download done: ${rec.fileName}", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    DownloadManager.STATUS_FAILED -> {
                        tvSpeed.text = "Failed"; pause.text = "Retry"
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        tvSpeed.text = "Paused"; pause.text = "Resume"
                    }
                    else -> {
                        pause.text = "Pause"
                        tvSpeed.text = when {
                            spd >= 1_000_000 -> "%.1f MB/s".format(spd / 1_000_000.0)
                            spd >= 1_000     -> "${spd / 1_000} KB/s"
                            spd > 0          -> "$spd B/s"
                            else             -> "-"
                        }
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(poller, 300)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Cookies
    // ══════════════════════════════════════════════════════════════════════════

    private fun showCookieMenu(tab: Tab) {
        val host = runCatching { Uri.parse(tab.url).host ?: tab.url }.getOrDefault(tab.url)
        MaterialAlertDialogBuilder(this)
            .setTitle("Cookies - $host")
            .setItems(arrayOf("Copy cookies", "Clear cookies (this profile)")) { _, w ->
                when (w) { 0 -> copyCookies(tab); 1 -> confirmClearCookies(tab) }
            }.show()
    }

    private fun copyCookies(tab: Tab) {
        val wv = tab.webView ?: run { toast("Tab not loaded"); return }
        wv.evaluateJavascript("(function(){ return document.cookie; })()") { raw ->
            val c = raw?.trim('"') ?: ""
            if (c.isBlank() || c == "null") {
                toast("No cookies found"); return@evaluateJavascript
            }
            val clip = ClipData.newPlainText("cookies", c)
            if (Build.VERSION.SDK_INT >= 33) {
                // Keyboards and the clipboard preview do not show the text.
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            scheduleClipboardClear("cookies")
            toast("Cookies copied")
        }
    }

    private fun confirmClearCookies(tab: Tab) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear cookies?")
            .setMessage("All cookies for the \"${currentProfile?.name}\" profile will be wiped. " +
                    "This affects every tab in this profile.")
            .setPositiveButton("Clear") { _, _ ->
                if (profilesSupported) {
                    val name = tab.webkitProfileName
                    runCatching {
                        ProfileStore.getInstance().deleteProfile(name)
                        ProfileStore.getInstance().getOrCreateProfile(name)
                    }
                    allTabs.filter { it.profileId == tab.profileId }.forEach { destroyWebView(it) }
                    attachTab(tab)
                } else {
                    CookieManager.getInstance().removeAllCookies { tab.webView?.reload() }
                }
                toast("Cookies cleared")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  History (Menu > History)
    // ══════════════════════════════════════════════════════════════════════════

    /** Pages visited in the current profile: search, tap to open, long-press to remove one, Clear all. */
    private fun showHistory() {
        val prof = currentProfile ?: return
        val all = HistoryStore.list(this, prof.id).toMutableList()
        val shown = ArrayList(all)
        val dp = resources.displayMetrics.density
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)

        val searchLayout = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "Search history"
            startIconDrawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity, R.drawable.ic_search)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
        }
        val search = TextInputEditText(searchLayout.context).apply { setSingleLine(true) }
        searchLayout.addView(search, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        val empty = TextView(this).apply {
            text = "No history yet"
            setTextAppearance(R.style.TextAppearance_Nezu_BodyLarge)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, (32 * dp).toInt(), 0, (32 * dp).toInt())
            gravity = android.view.Gravity.CENTER
        }
        val list = android.widget.ListView(this).apply {
            divider = null
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (360 * dp).toInt())
        }
        lateinit var dlg: androidx.appcompat.app.AlertDialog

        val adapter = object : android.widget.ArrayAdapter<HistoryStore.Entry>(this, 0, shown) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val e = getItem(position)!!
                val row = (convertView as? android.widget.LinearLayout) ?: android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt())
                    addView(TextView(context).apply {
                        setTextAppearance(R.style.TextAppearance_Nezu_TitleSmall)
                        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        setTextAppearance(R.style.TextAppearance_Nezu_BodySmall)
                        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                }
                (row.getChildAt(0) as TextView).text = e.title.ifBlank { e.url }
                (row.getChildAt(1) as TextView).text = e.url.substringAfter("://") + "  -  " + fmt.format(java.util.Date(e.time))
                return row
            }
        }
        list.adapter = adapter

        fun refilter() {
            val q = search.text.toString().trim().lowercase()
            shown.clear()
            shown.addAll(if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) })
            adapter.notifyDataSetChanged()
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (all.isEmpty()) "No history yet" else "Nothing found"
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = shown.getOrNull(pos) ?: return@setOnItemClickListener
            dlg.dismiss()
            openInCurrent(e.url)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = shown.getOrNull(pos) ?: return@setOnItemLongClickListener true
            HistoryStore.remove(this, prof.id, e.url)
            all.removeAll { it.url == e.url }
            refilter()
            toast("Removed")
            true
        }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(searchLayout); addView(empty); addView(list)
        }
        dlg = MaterialAlertDialogBuilder(this)
            .setTitle("History - ${prof.name}")
            .setView(box)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear all", null)
            .create()
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            if (all.isEmpty()) return@setOnClickListener
            HistoryStore.clear(this, prof.id)
            all.clear()
            refilter()
            toast("History cleared")
        }
        refilter()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Clear data (per profile, the user picks what to clear)
    // ══════════════════════════════════════════════════════════════════════════

    private fun showClearDataDialog() {
        val prof = currentProfile ?: return
        val labels = DataWiper.LABELS
        val checked = booleanArrayOf(true, true, true, false, false, false)
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Clear data - ${prof.name}")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Clear") { _, _ ->
                if (checked.none { it }) toast("Nothing selected") else clearProfileData(prof, checked)
            }
            .setNeutralButton("Select all", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { btn ->
            val selectAll = checked.any { !it }
            for (i in checked.indices) { checked[i] = selectAll; dlg.listView.setItemChecked(i, selectAll) }
            (btn as android.widget.Button).text = if (selectAll) "Clear selection" else "Select all"
        }
    }

    /** [sel] follows the order of [DataWiper.LABELS]. Only [prof] is touched, except "App cache". */
    private fun clearProfileData(prof: AppProfile, sel: BooleanArray) {
        var mask = 0
        sel.forEachIndexed { i, on -> if (on) mask = mask or DataWiper.BITS[i] }
        val wvs = allTabs.filter { it.profileId == prof.id }.mapNotNull { it.webView }
        DataWiper.wipe(this, if (profilesSupported) prof.webkitProfileName else null, wvs, mask, prof.id)
        if (mask and DataWiper.HISTORY != 0) updateNav()
        toast("Cleared")
        if (mask and (DataWiper.STORAGE or DataWiper.COOKIES) != 0) current?.webView?.reload()   // pages show the signed-out state
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Privacy & security (Settings > Privacy & Security)
    // ══════════════════════════════════════════════════════════════════════════

    /** Called when the screen comes back: picks up every switch that was changed in Settings. */
    private fun applyPrivacy() {
        AdBlocker.blockTrackers = PrivacyPrefs.blockTrackers(this)
        allTabs.forEach { t -> t.webView?.let { applyWebPrivacy(it) } }
        applySecureFlag()
        enforceLock()
    }

    // ── View / copy page source ────────────────────────────────────────────────

    /** The page's HTML as the server sent it (read again by a script in the page); if that fails, the page as it is now. */
    private fun loadSource(tab: Tab, rendered: Boolean, cb: (String?) -> Unit) {
        val wv = tab.webView ?: run { cb(null); return }
        if (rendered) { renderedSource(wv, cb); return }
        val nonce = java.util.UUID.randomUUID().toString()
        var done = false
        val finish = { t: String? -> if (!done) { done = true; if (t != null) cb(t) else renderedSource(wv, cb) } }
        srcBridge.expect(nonce) { finish(it) }
        wv.postDelayed({ if (!done) { srcBridge.cancel(nonce); finish(null) } }, 10_000)
        wv.evaluateJavascript(
            """(function(n){function d(t){NezuSrc.put(n,t);}try{fetch(location.href,{credentials:'include'}).then(function(r){return r.text();}).then(d).catch(function(){d(null);});}catch(e){d(null);}})("$nonce")""",
            null)
    }

    private fun renderedSource(wv: WebView, cb: (String?) -> Unit) {
        wv.evaluateJavascript(
            """(function(){var t=document.doctype;return (t?'<!DOCTYPE '+t.name+'>\n':'')+document.documentElement.outerHTML;})()""") { r ->
            cb(runCatching { org.json.JSONTokener(r).nextValue() as? String }.getOrNull())
        }
    }

    private fun sourceHost(tab: Tab): String? {
        val u = Uri.parse(tab.url)
        return if (tab.webView != null && (u.scheme == "http" || u.scheme == "https")) u.host else null
    }

    private fun copySource(tab: Tab) {
        if (sourceHost(tab) == null) { toast("No web page is open"); return }
        toast("Getting source...")
        loadSource(tab, false) { text ->
            if (text == null) toast("Could not get the source") else copyBigText(text)
        }
    }

    private fun copyBigText(text: String) {
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("page source", text))
            toast("Source copied")
        } catch (_: Exception) { toast("This page is too big to copy at once") }
    }

    private fun viewSource(tab: Tab) {
        val host = sourceHost(tab)
        if (host == null) { toast("No web page is open"); return }
        toast("Getting source...")
        loadSource(tab, false) { text ->
            if (text == null) toast("Could not get the source") else showSourceDialog(tab, host, text)
        }
    }

    private fun showSourceDialog(tab: Tab, host: String, first: String) {
        val limit = 200_000
        var srcText = first
        var rendered = false
        val surface = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface)
        val onSurface = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
        val dp = resources.displayMetrics.density

        val title = TextView(this).apply {
            textSize = 15f; maxLines = 1; setTextColor(onSurface)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val body = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f; setTextColor(onSurface)
            setTextIsSelectable(true)
            val p = (12 * dp).toInt(); setPadding(p, p, p, (88 * dp).toInt())   // room for the floating button
        }
        fun show() {
            title.text = (if (rendered) "Rendered HTML" else "Source") + " - " + host
            body.text = if (srcText.length > limit) srcText.substring(0, limit) + "\n\n... showing the first $limit of ${srcText.length} characters. Copy gets everything." else srcText
        }
        show()

        fun button(label: String) = com.google.android.material.button.MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { this.text = label }
        val toggle = button("Show rendered")
        val close = button("Close")

        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            addView(title, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        val actions = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            addView(toggle)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(bar, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()))
            addView(actions, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Floating "Copy source" button (bottom right)
        val fab = com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton(this).apply {
            text = "Copy source"
            setIconResource(R.drawable.ic_content_copy)
            setOnClickListener { copyBigText(srcText) }
        }
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(surface)
            addView(column, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(fab, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(0, 0, (16 * dp).toInt(), (24 * dp).toInt())
            })
        }
        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(root)
        dlg.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
        }
        close.setOnClickListener { dlg.dismiss() }
        toggle.setOnClickListener {
            toggle.isEnabled = false
            loadSource(tab, !rendered) { t ->
                toggle.isEnabled = true
                if (t == null) { toast("Could not get the source"); return@loadSource }
                rendered = !rendered
                srcText = t
                toggle.text = if (rendered) "Show original" else "Show rendered"
                show()
                scroll.scrollTo(0, 0)
            }
        }
        dlg.show()
    }

    // ── Sign-in pop-ups ─────────────────────────────────────────────────────────
    private val popupClosers = java.util.WeakHashMap<WebView, () -> Unit>()

    /** window.open() from a page (GitHub / Google sign-in ...): a full-screen window with its own WebView, linked to the page that opened it. */
    private fun openPopupWindow(tab: Tab, resultMsg: android.os.Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val pop = WebView(this)
        if (tab.webkitProfileName.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { WebViewCompat.setProfile(pop, tab.webkitProfileName) }
        }
        with(pop.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
        applyWebPrivacy(pop)
        pop.addJavascriptInterface(extBridge, "NezuExt")
        applyExtensions(tab, pop)

        val host = TextView(this).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface))
        }
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_tab_close)
            imageTintList = ColorStateList.valueOf(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = "Close"
        }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val h = (16 * resources.displayMetrics.density).toInt()
            setPadding(h, 0, 4, 0)
            addView(host, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, android.widget.LinearLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt()))
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface))
            addView(bar, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pop, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(root)
        dlg.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface)))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var closed = false
        val closeIt = {
            if (!closed) {
                closed = true
                popupClosers.remove(pop)
                runCatching { dlg.dismiss() }
                pop.stopLoading(); pop.loadUrl("about:blank"); (pop.parent as? ViewGroup)?.removeView(pop); pop.destroy()
            }
        }
        popupClosers[pop] = closeIt
        close.setOnClickListener { closeIt() }
        dlg.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                if (pop.canGoBack()) pop.goBack() else closeIt()
                true
            } else false
        }
        dlg.setOnCancelListener { closeIt() }

        pop.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                host.text = runCatching { Uri.parse(url).host }.getOrNull() ?: ""
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (AdBlocker.enabled && !req.hasGesture() && AdBlocker.isBlockedHost(req.url.host)) return true
                val scheme = req.url.scheme ?: return false
                if (scheme in listOf("http", "https", "about", "data", "blob", "javascript")) return false
                openExternal(req.url)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest) =
                ExtensionStore.serve(this@MainActivity, req.url)
                    ?: AdBlocker.intercept(req.url.host, req.isForMainFrame, req.url.toString())
        }
        pop.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) { window.post { closeIt() } }
        }

        transport.webView = pop
        resultMsg.sendToTarget()
        dlg.show()
        return true
    }

    /** Menu > Extensions: switch extensions on or off for the current profile. Adding and removing is in Settings. */
    private fun showExtensionsDialog() {
        val prof = currentProfile ?: return
        val exts = ExtensionStore.all(this)
        val openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (exts.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Extensions")
                .setMessage("No extensions yet. Add one from a .zip in Settings > Extensions.")
                .setPositiveButton("Open Settings") { _, _ -> openSettings() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val names = exts.map { it.name }.toTypedArray()
        val checked = BooleanArray(exts.size) { exts[it].enabled && prof.id in exts[it].profiles }
        MaterialAlertDialogBuilder(this)
            .setTitle("Extensions - ${prof.name}")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                exts.forEachIndexed { i, e ->
                    val has = prof.id in e.profiles
                    if (checked[i] && !(has && e.enabled)) {
                        ExtensionStore.setProfiles(this, e.id, e.profiles + prof.id)
                        if (!e.enabled) ExtensionStore.setEnabled(this, e.id, true)
                    } else if (!checked[i] && has) {
                        ExtensionStore.setProfiles(this, e.id, e.profiles - prof.id)
                    }
                }
                if (extVersionSeen != ExtensionStore.version) {
                    extVersionSeen = ExtensionStore.version
                    allTabs.forEach { t -> t.webView?.let { applyExtensions(t, it) } }
                    current?.webView?.reload()
                }
            }
            .setNeutralButton("Settings") { _, _ -> openSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Extensions (Settings > Extensions): content scripts of the extensions switched on for this tab's profile. */
    private fun applyExtensions(tab: Tab, wv: WebView) {
        extHandlers.remove(wv)?.forEach { runCatching { it.remove() } }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val handlers = ArrayList<ScriptHandler>()
        for (ext in ExtensionStore.forProfile(this, tab.profileId)) {
            for (i in ext.scripts.indices) {
                val js = ExtensionStore.buildScript(this, ext, i) ?: continue
                val rules = ExtensionStore.originRules(ext.scripts[i])
                if (rules.isEmpty()) continue
                runCatching { handlers.add(WebViewCompat.addDocumentStartJavaScript(wv, js, rules)) }
            }
        }
        if (handlers.isNotEmpty()) extHandlers[wv] = handlers
    }

    private fun applyWebPrivacy(wv: WebView) {
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !PrivacyPrefs.blockThirdPartyCookies(this)) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            runCatching { WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, PrivacyPrefs.safeBrowsing(this)) }
        }
    }

    /** Blank picture in the recent-apps list and no screenshots: when asked for, when the app is locked, or in private / locked profiles. */
    private fun applySecureFlag() {
        val p = currentProfile
        val secure = PrivacyPrefs.secureScreen(this) || PrivacyPrefs.appLock(this) ||
            (p != null && (PrivacyPrefs.isPrivate(this, p.id) || PrivacyPrefs.isLocked(this, p.id)))
        if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ── App lock and profile lock ───────────────────────────────────────────────

    private fun enforceLock() {
        val appNeeded = LockState.appLockNeeded(this)
        val prof = currentProfile
        val profNeeded = !appNeeded && prof != null && LockState.profileLockNeeded(this, prof.id)
        when {
            appNeeded -> showLock("NeZu Space is locked", "Unlock with your fingerprint, face or phone lock.", null)
            profNeeded -> showLock("${prof!!.name} is locked", "This profile needs your fingerprint, face or phone lock.") {
                switchToOpenProfile()
            }
            lockScreen.isShowing -> {
                lockScreen.hide()
                lockBack.isEnabled = false
                current?.webView?.onResume()
            }
        }
    }

    private fun showLock(title: String, message: String, onSwitch: (() -> Unit)?) {
        if (!lockBackAdded) { onBackPressedDispatcher.addCallback(this, lockBack); lockBackAdded = true }
        lockBack.isEnabled = true
        current?.webView?.onPause()
        lockScreen.show(title, message, onSwitch)
    }

    private fun onUnlockSuccess() {
        if (LockState.appLockNeeded(this)) LockState.unlocked = true
        else currentProfile?.let { LockState.unlockedProfiles.add(it.id) }
        enforceLock()
    }

    private fun switchToOpenProfile() {
        val other = allProfiles.firstOrNull { it !== currentProfile && !LockState.profileLockNeeded(this, it.id) }
        if (other == null) { toast("Every other profile is locked too"); return }
        applyProfile(other)
    }

    // ── Private profiles and "clear when closed" ─────────────────────────────────

    /**
     * Private profiles forget everything, and "clear when closed" removes the chosen kinds of data from every profile.
     * [live] = the app is closing and its WebViews still exist; otherwise this is the next start of the app
     * (closing the app from the recent-apps list does not tell the app, so the clean-up is repeated at start).
     */
    private fun wipeProfiles(live: Boolean, profiles: List<AppProfile> = allProfiles) {
        val priv = PrivacyPrefs.privateProfiles(this)
        val auto = PrivacyPrefs.autoClear(this)
        if (priv.isEmpty() && !auto) return
        val autoMask = PrivacyPrefs.autoClearMask(this)
        profiles.forEach { p ->
            var m = 0
            if (p.id in priv) m = m or DataWiper.PRIVATE_MASK
            if (auto) m = m or (autoMask and DataWiper.APP_CACHE.inv())
            if (m != 0) {
                val wvs = if (live) allTabs.filter { it.profileId == p.id }.mapNotNull { it.webView } else emptyList()
                DataWiper.wipe(this, if (profilesSupported) p.webkitProfileName else null, wvs, m, p.id)
            }
        }
        if (auto && autoMask and DataWiper.APP_CACHE != 0) DataWiper.wipe(this, null, emptyList(), DataWiper.APP_CACHE)
    }

    // ── Links ───────────────────────────────────────────────────────────────────

    /** Tracking parameters removed, then the https upgrade (both are Settings switches). */
    private fun prepareUrl(tab: Tab, url: String): String = secureUrl(tab, PrivacyPrefs.cleanUrl(this, url))

    private fun secureUrl(tab: Tab, url: String): String {
        if (!PrivacyPrefs.httpsOnly(this)) return url
        val u = Uri.parse(url)
        if (u.scheme != "http") return url
        val host = u.host ?: return url
        if (u.port != -1 || PrivacyPrefs.isLocalHost(host) || host in httpAllowed || tab.upgradeCount >= 3) return url
        tab.upgradedHost = host
        tab.upgradeCount++
        return u.buildUpon().scheme("https").build().toString()
    }

    /** A page wants to open another app (mailto:, tel:, market:, intent: ...). */
    private fun openExternal(uri: Uri) {
        val go = {
            try {
                val i = if (uri.scheme == "intent") {
                    // A link must not choose the component or a hidden second intent.
                    Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                    }
                } else Intent(Intent.ACTION_VIEW, uri)
                startActivity(i)
            } catch (_: Exception) { toast("No app can open this link") }
        }
        if (!PrivacyPrefs.confirmExternal(this)) { go(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Open in another app?")
            .setMessage("This page wants to open a link in another app:\n\n" + uri.toString().take(140))
            .setPositiveButton("Open") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Downloads ───────────────────────────────────────────────────────────────

    /** Program files, and downloads that no tap started, are confirmed first (Settings: "Warn before risky downloads"). */
    private fun guardDownload(tab: Tab, name: String, mime: String?, proceed: () -> Unit) {
        val risky = PrivacyPrefs.isRiskyFile(name, mime)
        val byPage = System.currentTimeMillis() - tab.lastTouchAt > 15_000
        if (!PrivacyPrefs.downloadWarn(this) || (!risky && !byPage)) { proceed(); return }
        val host = runCatching { Uri.parse(tab.url).host }.getOrNull().orEmpty()
        val what = if (name.isNotBlank()) name else "a file"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (risky) "Download a program file?" else "Allow this download?")
            .setMessage(
                (if (byPage) "$host started a download by itself: $what.\n\n" else "$what\n\n") +
                (if (risky) "This kind of file can install software or harm your phone. Only download it from a site you trust." else "")
            )
            .setPositiveButton("Download") { _, _ -> proceed() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Copied cookies give access to accounts, so the clipboard is emptied after a minute. */
    private fun scheduleClipboardClear(label: String) {
        if (!PrivacyPrefs.clipboardClear(this)) return
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val seen = cm.primaryClipDescription?.label?.toString()
                // Android 10+ hides the clipboard from an app that is not on screen: then it is simply emptied.
                if (seen == null || seen == label) {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                    else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, 60_000)
    }

    // ── Site permissions (camera, microphone, location) ──────────────────────────

    /** Asks once per site (the answer is remembered and can be reset in Settings or Menu > Site info). */
    private fun askSitePermission(host: String, types: List<String>, onResult: (Boolean) -> Unit) {
        val saved = types.map { PrivacyPrefs.sitePermission(this, host, it) }
        if (saved.any { it == false }) { onResult(false); return }
        if (saved.all { it == true }) { requestAndroidPermissions(types, onResult); return }
        val names = types.joinToString(" and ")
        var answered = false
        val answer = { ok: Boolean -> if (!answered) { answered = true; onResult(ok) } }
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow $host to use your $names?")
            .setPositiveButton("Allow") { _, _ ->
                types.forEach { PrivacyPrefs.setSitePermission(this, host, it, true) }
                requestAndroidPermissions(types) { ok -> answer(ok) }
            }
            .setNegativeButton("Block") { _, _ ->
                types.forEach { PrivacyPrefs.setSitePermission(this, host, it, false) }
                answer(false)
            }
            .setNeutralButton("Only this time") { _, _ -> requestAndroidPermissions(types) { ok -> answer(ok) } }
            .setOnCancelListener { answer(false) }
            .show()
    }

    /** The site may use it; now the phone itself has to allow the app. */
    private fun requestAndroidPermissions(types: List<String>, onResult: (Boolean) -> Unit) {
        val needed = types.flatMap {
            when (it) {
                PrivacyPrefs.PERM_CAMERA -> listOf(Manifest.permission.CAMERA)
                PrivacyPrefs.PERM_MIC -> listOf(Manifest.permission.RECORD_AUDIO)
                else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.filter { androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        val locationOnly = types == listOf(PrivacyPrefs.PERM_LOCATION)
        val alreadyOk = needed.isEmpty() ||
            (locationOnly && needed.size < 2)   // fine or coarse is already allowed
        if (alreadyOk) { onResult(true); return }
        permCallback?.invoke(false)
        permCallback = onResult
        permTypes = types
        sitePermLauncher.launch(needed.toTypedArray())
    }

    /** Menu > Site info: address, connection, certificate and what the site may use. */
    @Suppress("DEPRECATION")
    private fun showSiteInfo(tab: Tab) {
        val uri = Uri.parse(tab.url)
        val host = uri.host
        if (host == null) { toast("No web page is open"); return }
        val sb = StringBuilder()
        sb.append(host).append("\n\n")
        sb.append(if (uri.scheme == "https") "Connection is secure (HTTPS)." else "Connection is NOT secure (HTTP). Do not enter passwords here.")
        tab.webView?.certificate?.let { c ->
            sb.append("\n\nCertificate\nIssued to: ").append(c.issuedTo?.cName.orEmpty())
            sb.append("\nIssued by: ").append(c.issuedBy?.oName.orEmpty().ifBlank { c.issuedBy?.cName.orEmpty() })
            sb.append("\nValid until: ").append(c.validNotAfter.orEmpty())
        }
        val saved = PrivacyPrefs.siteDecisions(this).firstOrNull { it.first == host.lowercase() }?.second.orEmpty()
        sb.append("\n\nPermissions\n")
        if (saved.isEmpty()) sb.append("No saved choices for this site.")
        else saved.forEach { (t, ok) -> sb.append(t.replaceFirstChar { it.uppercase() }).append(": ").append(if (ok) "allowed" else "blocked").append('\n') }
        MaterialAlertDialogBuilder(this)
            .setTitle("Site info")
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("Close", null)
            .apply {
                if (saved.isNotEmpty()) setNeutralButton("Reset permissions") { _, _ ->
                    PrivacyPrefs.clearSite(this@MainActivity, host); toast("Permissions reset")
                }
            }
            .show()
    }

    private fun copyLink(tab: Tab) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("url", PrivacyPrefs.cleanUrl(this, tab.url)))
        toast("Link copied")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Switcher
    // ══════════════════════════════════════════════════════════════════════════

    private fun isSwitcherOpen() = b.switcher.visibility == View.VISIBLE && !switcherClosing

    private fun toggleSwitcher() {
        if (isSwitcherOpen()) hideSwitcher() else showSwitcher()
    }

    /** Panel slides up from the bottom bar, the page behind it dims. */
    private fun showSwitcher() {
        if (isSwitcherOpen()) return
        hideKeyboard(); b.urlInput.clearFocus()
        // Re-opened while it was still closing: stop that animation.
        b.switcherPanel.animate().cancel(); b.switcherScrim.animate().cancel()
        switcherClosing = false; closeCallbacks.clear()

        refreshSwitcher()
        updateListHeight(animate = false)
        val panelLp = b.switcherPanel.layoutParams
        panelLp.width = if (resources.configuration.screenWidthDp >= 600) dp(560) else ViewGroup.LayoutParams.MATCH_PARENT
        b.switcherPanel.layoutParams = panelLp
        (b.tabList.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(maxOf(tabAdapter.positionOf(current?.id), 0), dp(24))

        b.switcher.visibility = View.VISIBLE
        if (!animOn()) {
            b.switcherScrim.alpha = 1f; b.switcherPanel.translationY = 0f; return
        }
        b.switcherScrim.alpha = 0f
        b.switcherPanel.doOnPreDraw {
            b.switcherPanel.translationY = b.switcherPanel.height.toFloat()
            b.switcherScrim.animate().alpha(1f).setDuration(220).start()
            b.switcherPanel.animate().translationY(0f).setDuration(300)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    /** Panel slides back down. [onDone] runs when it is gone (immediately if it is not shown). */
    private fun hideSwitcher(animate: Boolean = true, onDone: (() -> Unit)? = null) {
        if (b.switcher.visibility != View.VISIBLE) { onDone?.invoke(); return }
        if (onDone != null) closeCallbacks.add(onDone)
        fun finishClose() {
            b.switcher.visibility = View.GONE
            b.switcherScrim.alpha = 1f; b.switcherPanel.translationY = 0f
            switcherClosing = false
            val cbs = closeCallbacks.toList(); closeCallbacks.clear()
            cbs.forEach { it() }
        }
        if (!animate || !animOn()) {
            b.switcherPanel.animate().cancel(); b.switcherScrim.animate().cancel()
            finishClose(); return
        }
        if (switcherClosing) return
        switcherClosing = true
        b.switcherScrim.animate().alpha(0f).setDuration(200).start()
        b.switcherPanel.animate().translationY(b.switcherPanel.height.toFloat()).setDuration(240)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction { finishClose() }.start()
    }

    /** Panel height follows the number of tabs (up to 65 % of the page area, then it scrolls). */
    private fun updateListHeight(animate: Boolean) {
        val rv = b.tabList
        val maxH = (b.contentRoot.height * 0.65f).toInt().coerceAtLeast(dp(132))
        val target = (profileTabs.size * dp(70) + dp(20)).coerceAtMost(maxH)
        val lp = rv.layoutParams
        heightAnimator?.cancel()
        val from = if (lp.height > 0) lp.height else target
        if (!animate || !animOn() || from == target) {
            lp.height = target; rv.layoutParams = lp; return
        }
        heightAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = 220
            addUpdateListener { lp.height = it.animatedValue as Int; rv.layoutParams = lp }
            start()
        }
    }

    private var countAnim: ValueAnimator? = null

    /** Sets the number at once (it can never be stale) and plays a small pop that always ends at normal size. */
    private fun setTabCount(n: Int) {
        val text = n.toString()
        val old = b.tabCount.text.toString()
        if (old == text) return
        b.tabCount.text = text
        countAnim?.cancel()
        b.tabCount.scaleX = 1f; b.tabCount.scaleY = 1f
        if (old.isEmpty() || !animOn()) return
        countAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener {
                val k = 1f + 0.45f * kotlin.math.sin(Math.PI * (it.animatedValue as Float)).toFloat()
                b.tabCount.scaleX = k; b.tabCount.scaleY = k
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    b.tabCount.scaleX = 1f; b.tabCount.scaleY = 1f
                }
            })
            start()
        }
    }

    private fun refreshSwitcher() {
        val pts = profileTabs
        tabAdapter.submit(pts, current?.id)
        setTabCount(pts.size)   // all tabs of this profile
        b.switcherSubtitle.text = "${pts.size} ${if (pts.size == 1) "tab" else "tabs"} · ${currentProfile?.name.orEmpty()}"
        if (b.switcher.visibility == View.VISIBLE) updateListHeight(animate = true)
    }

    /** Tap on a row of the panel: panel slides away, then the page turns to that tab. */
    private fun selectTab(tab: Tab) {
        if (tab === current) { hideSwitcher(); return }
        if (busy) return
        val list = profileTabs
        val fwd = list.indexOf(tab) > list.indexOf(current)
        hideSwitcher {
            if (allTabs.contains(tab)) flipPage(fwd) { attachTab(tab); persist() }
        }
    }

    private fun showTabOptions(tab: Tab) {
        val items = arrayOf("Edit name and colour", if (tab.pinned) "Unpin tab" else "Pin tab", "Set tab avatar")
        MaterialAlertDialogBuilder(this)
            .setTitle(tab.name)
            .setItems(items) { _, i ->
                when (i) { 0 -> editTab(tab); 1 -> togglePin(tab); 2 -> pickAvatarForTab(tab) }
            }.show()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.urlInput.windowToken, 0)
    }

    private fun showUrlInBar(t: Tab) {
        if (!b.urlInput.hasFocus()) b.urlInput.setText(if (t.homeShown) "" else t.url)
    }

    private fun applyChrome() {
        val t = current ?: return
        b.tabDot.backgroundTintList = ColorStateList.valueOf(currentProfile?.color ?: t.color)
        b.progress.setIndicatorColor(t.color)
        val engine = currentProfile?.searchEngine ?: SearchEngine.GOOGLE
        b.urlInput.hint = "Search ${engine.label} or type URL"
        showUrlInBar(t)
        updateNav()
    }

    private fun updateNav() {
        val wv = current?.webView
        b.btnBack.isEnabled    = wv?.canGoBack() == true
        b.btnForward.isEnabled = wv?.canGoForward() == true
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Tab management
    // ══════════════════════════════════════════════════════════════════════════

    private fun addTab(url: String = ""): Tab {
        val pid       = currentProfile?.id ?: allProfiles.first().id
        val used      = allTabs.filter { it.profileId == pid }.map { it.color }
        val color     = PALETTE.firstOrNull { it !in used } ?: PALETTE[allTabs.size % PALETTE.size]
        val tab       = Tab(profileId = pid, name = "Tab ${profileTabs.size + 1}",
                            color = color, url = url)
        allTabs.add(tab)
        return tab
    }

    /** New tab = NeZu Space home. If the panel is open, the new row appears in it first, then the panel closes. */
    private fun newTab() {
        if (newTabPending) return
        if (b.switcher.visibility == View.VISIBLE && animOn()) {
            newTabPending = true
            val t = addTab()
            refreshSwitcher()
            b.tabList.smoothScrollToPosition(maxOf(tabAdapter.positionOf(t.id), 0))
            b.tabList.postDelayed({
                newTabPending = false
                hideSwitcher()
                dustInPage { attachTab(t); persist() }
            }, 280)
        } else {
            hideSwitcher(animate = false)
            dustInPage { attachTab(addTab()); persist() }
        }
    }

    private fun showTab(tab: Tab) {
        attachTab(tab)
        hideSwitcher(animate = false)
        persist()
    }

    private fun attachTab(tab: Tab) {
        current?.webView?.onPause()
        current = tab
        lastTabOfProfile[tab.profileId] = tab.id
        if (tab.url.isBlank()) tab.homeShown = true    // blank tab = NeZu Space home, nothing to load
        val wv = tab.webView ?: createWebView(tab).also { tab.webView = it }
        if (!tab.loaded) { tab.loaded = true; if (tab.url.isNotBlank()) wv.loadUrl(prepareUrl(tab, tab.url)) }
        b.webContainer.removeAllViews()
        (wv.parent as? ViewGroup)?.removeView(wv)
        b.webContainer.addView(wv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.onResume()
        b.urlInput.clearFocus()
        hideKeyboard()
        applyHomeState()
        applyChrome()
        refreshSwitcher()
    }

    /** Swipe on the address bar: next (+1) or previous (-1) tab. */
    private fun switchTabBy(d: Int) {
        if (busy) return
        val list = profileTabs
        if (list.size < 2) return
        // Wraps around: swiping "next" on the last tab goes to the first, "previous" on the first goes to the last.
        val target = list[((list.indexOf(current) + d) % list.size + list.size) % list.size]
        if (b.switcher.visibility == View.VISIBLE) {      // panel is open: just move the highlight
            SoundFx.play(this, SoundFx.Event.CHANGE)
            attachTab(target); persist(); return
        }
        flipPage(d > 0) { attachTab(target); persist() }
    }

    /**
     * Closes a tab. [row] is its card in the tab list (it turns to dust). Without a card, the
     * current page itself turns to dust.
     */
    private fun closeTab(tab: Tab, row: View? = null, restore: () -> Unit = {}) {
        if (tab.pinned) {
            toast("Pinned tab - unpin first to close"); restore(); return
        }
        when {
            // The page on screen belongs to this tab: the whole page turns to dust.
            tab === current -> {
                restore()
                if (b.switcher.visibility == View.VISIBLE) hideSwitcher { if (allTabs.contains(tab)) dustOutPage { removeTabData(tab) } }
                else dustOutPage { removeTabData(tab) }
            }
            row != null -> {
                val panel = b.switcherPanel.cardBackgroundColor.defaultColor
                val ink = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
                val slab = androidx.core.graphics.ColorUtils.setAlphaComponent(
                    androidx.core.graphics.ColorUtils.compositeColors(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 0x22),
                        androidx.core.graphics.ColorUtils.setAlphaComponent(panel, 0xFF)), 0xFF)
                dustOutView(row, atStart = { removeTabData(tab) }, backdrop = slab)
            }
            else -> { SoundFx.play(this, SoundFx.Event.DELETE); removeTabData(tab) }
        }
    }

    private fun removeTabData(tab: Tab) {
        val idx = profileTabs.indexOf(tab)
        val wasCurrent = tab === current
        destroyWebView(tab)
        deleteImageFile(tab.avatarUri)
        allTabs.remove(tab)
        if (profilesSupported) b.root.postDelayed({
            if (allTabs.none { it.webkitProfileName == tab.webkitProfileName })
                runCatching { ProfileStore.getInstance().deleteProfile(tab.webkitProfileName) }
        }, 800)
        if (profileTabs.isEmpty()) addTab()
        if (wasCurrent) attachTab(profileTabs[minOf(idx, profileTabs.lastIndex)])
        refreshSwitcher(); persist()
    }

    private fun sortPinnedFirst() {
        val pid      = currentProfile?.id ?: return
        val profile  = allTabs.filter { it.profileId == pid }.sortedWith(compareBy { !it.pinned })
        val rest     = allTabs.filter { it.profileId != pid }
        allTabs.clear()
        allTabs.addAll(rest)
        allTabs.addAll(profile)
    }

    private fun togglePin(tab: Tab) {
        tab.pinned = !tab.pinned
        sortPinnedFirst(); refreshSwitcher(); persist()
        toast(if (tab.pinned) "Tab pinned permanently" else "Tab unpinned")
    }

    private fun destroyWebView(tab: Tab) {
        tab.webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        tab.webView = null
        tab.loaded = false
    }

    private fun cleanupWebKitProfiles() {
        if (!profilesSupported) return
        try {
            val keep = allProfiles.map { it.webkitProfileName }.toSet()
            ProfileStore.getInstance().allProfileNames
                .filter { it.startsWith("profile_") && it !in keep }
                .forEach { runCatching { ProfileStore.getInstance().deleteProfile(it) } }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Navigation
    // ══════════════════════════════════════════════════════════════════════════

    private fun navigate(input: String) {
        val q   = input.trim(); val tab = current ?: return
        if (q.isEmpty()) return
        val engine = currentProfile?.searchEngine ?: SearchEngine.GOOGLE
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains(" ") || !q.contains(".")                 -> engine.buildUrl(q)
            else                                                  -> "https://$q"
        }
        b.urlInput.clearFocus(); hideKeyboard()
        openInCurrent(url)
        tab.url = url
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Edit tab dialog
    // ══════════════════════════════════════════════════════════════════════════

    private fun editTab(tab: Tab) {
        val view      = layoutInflater.inflate(R.layout.dialog_tab_edit, null)
        val nameInput = view.findViewById<TextInputEditText>(R.id.nameInput)
        val colorGrid = view.findViewById<android.widget.GridLayout>(R.id.colorGrid)
        nameInput.setText(tab.name)
        var chosen = tab.color
        val dots   = mutableListOf<View>()
        val ring   = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)

        fun refresh() {
            dots.forEachIndexed { i, d ->
                d.background = swatch(PALETTE[i], PALETTE[i] == chosen, ring)
            }
        }
        PALETTE.forEach { c ->
            val d = View(this)
            d.layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = dp(44); height = dp(44); setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            d.setOnClickListener { chosen = c; refresh() }
            colorGrid.addView(d); dots.add(d)
        }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit tab")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                tab.name  = nameInput.text?.toString()?.trim().orEmpty().ifBlank { tab.name }
                tab.color = chosen
                applyChrome(); refreshSwitcher(); persist()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WebView factory
    // ══════════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════════
    //  Fullscreen video (rotates to landscape)
    // ══════════════════════════════════════════════════════════════════════════

    private fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) { cb.onCustomViewHidden(); return }
        customView = view
        customViewCallback = cb
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val host = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(view, FrameLayout.LayoutParams(full, full))
        }
        fullscreenHost = host
        (window.decorView as ViewGroup).addView(host, FrameLayout.LayoutParams(full, full))

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun exitFullscreen() {
        if (customView == null) return
        val host = fullscreenHost
        val cb = customViewCallback
        customView = null; customViewCallback = null; fullscreenHost = null
        host?.let { (it.parent as? ViewGroup)?.removeView(it); it.removeAllViews() }
        cb?.onCustomViewHidden()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        applyKeepAwake()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): WebView {
        val wv = WebView(this)
        applyWebBackground(wv)

        if (profilesSupported) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(tab.webkitProfileName)
                WebViewCompat.setProfile(wv, tab.webkitProfileName)
            } catch (_: Exception) {}
        }

        with(wv.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            builtInZoomControls  = true
            displayZoomControls  = false
            useWideViewPort      = true
            loadWithOverviewMode = true
            // A page must not read the phone's files or other apps' content through the browser.
            allowFileAccess      = false
            allowContentAccess   = false
            // Sign-in pop-ups (GitHub, Google ...) are opened by the page with window.open; they get their own window (onCreateWindow).
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
        applyWebPrivacy(wv)

        wv.addJavascriptInterface(blobBridge, "NezuBlob")
        wv.addJavascriptInterface(extBridge, "NezuExt")
        wv.addJavascriptInterface(srcBridge, "NezuSrc")
        applyExtensions(tab, wv)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, DOWNLOAD_NAME_HOOK, setOf("*")) }
        }

        wv.setDownloadListener(DownloadListener { url, ua, cd, mime, len ->
            when {
                url.startsWith("blob:") -> guardDownload(tab, "", mime) { saveBlob(tab, wv, url, mime) }
                url.startsWith("data:") -> guardDownload(tab, "", mime) { saveDataUrl(tab, url) }
                // No file name from the page: the server's own answer is used when the download starts.
                else -> guardDownload(tab, DownloadEngine.fileNameFor(url, cd, mime), mime) {
                    startDownload(tab, url, ua, cd.orEmpty(), mime.orEmpty(), len, rename = cd.isNullOrBlank())
                }
            }
        })

        // Long press on a picture or link opens a menu (like other mobile browsers).
        wv.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { touchX = e.rawX; touchY = e.rawY }
            tab.lastTouchAt = System.currentTimeMillis()
            false
        }
        wv.setOnLongClickListener { handleWebLongPress(tab, wv) }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, fav: Bitmap?) {
                url?.let { tab.url = it }
                if (tab === current) {
                    showUrlInBar(tab)
                    b.progress.visibility = View.VISIBLE
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                tab.upgradeCount = 0
                if (url != null) HistoryStore.add(this@MainActivity, tab.profileId, url, view.title)
                view.evaluateJavascript(DOWNLOAD_NAME_HOOK, null)
                AdBlocker.hideAdBoxes(view)
                if (tab === current) { b.progress.visibility = View.INVISIBLE; updateNav() }
                persist()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                url?.let { tab.url = it }
                if (tab === current) {
                    showUrlInBar(tab)
                    updateNav()
                }
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest) =
                ExtensionStore.serve(this@MainActivity, req.url)
                    ?: AdBlocker.intercept(req.url.host, req.isForMainFrame, req.url.toString())

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Redirects / pop-ups to ad servers that the user did not start are cancelled.
                if (AdBlocker.enabled && !req.hasGesture() && AdBlocker.isBlockedHost(req.url.host)) return true
                val scheme = req.url.scheme ?: return false
                if (scheme == "http" || scheme == "https") {
                    // Tracking parameters off and http -> https (Settings > Privacy & Security).
                    if (req.isForMainFrame && req.method == "GET") {
                        val before = req.url.toString()
                        val after = prepareUrl(tab, before)
                        if (after != before) { view.loadUrl(after); return true }
                    }
                    return false
                }
                if (scheme in listOf("about", "data", "blob", "javascript")) return false
                openExternal(req.url)
                return true
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                val errHost = runCatching { Uri.parse(error.url).host }.getOrNull()
                val pageHost = runCatching { Uri.parse(view.url).host }.getOrNull()
                if (errHost == null || errHost != pageHost) { handler.cancel(); return }   // pictures / scripts of the page: refuse quietly
                val reason = when (error.primaryError) {
                    android.net.http.SslError.SSL_UNTRUSTED -> "The certificate was not issued by an authority this phone trusts."
                    android.net.http.SslError.SSL_EXPIRED -> "The certificate has expired."
                    android.net.http.SslError.SSL_IDMISMATCH -> "The certificate belongs to a different website."
                    android.net.http.SslError.SSL_NOTYETVALID -> "The certificate is not valid yet."
                    android.net.http.SslError.SSL_DATE_INVALID -> "The date of the certificate is wrong."
                    else -> "The certificate is not valid."
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Connection is not private")
                    .setMessage("$errHost\n\n$reason\n\nPasswords, messages or card numbers you enter here could be stolen.")
                    .setPositiveButton("Go back") { _, _ -> handler.cancel(); if (view.canGoBack()) view.goBack() }
                    .setNegativeButton("Continue anyway") { _, _ -> handler.proceed() }
                    .setOnCancelListener { handler.cancel() }
                    .show()
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                if (!req.isForMainFrame) return
                val upgraded = tab.upgradedHost
                if (upgraded != null && req.url.host == upgraded && req.url.scheme == "https") {
                    tab.upgradedHost = null
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("No secure connection")
                        .setMessage("$upgraded does not work over HTTPS. With HTTP, others on the network can read and change what you send and receive.")
                        .setPositiveButton("Cancel", null)
                        .setNegativeButton("Open with HTTP") { _, _ ->
                            httpAllowed.add(upgraded)
                            view.loadUrl(req.url.buildUpon().scheme("http").build().toString())
                        }
                        .show()
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                // A tapped link with target=_blank just opens in this tab, like before.
                val hit = view.hitTestResult
                if ((hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                    hit.extra?.startsWith("http") == true) {
                    view.loadUrl(hit.extra!!)
                    return false
                }
                // Pop-ups nobody asked for stay blocked: they need a tap now or a few seconds ago.
                val touched = System.currentTimeMillis() - tab.lastTouchAt < 10_000
                if (!isUserGesture && !touched) return false
                return openPopupWindow(tab, resultMsg)
            }

            override fun onCloseWindow(window: WebView) {
                window.post { popupClosers[window]?.invoke() }
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val host = request.origin?.host
                val types = ArrayList<String>()
                val res = ArrayList<String>()
                request.resources.forEach { r ->
                    when (r) {
                        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { types.add(PrivacyPrefs.PERM_CAMERA); res.add(r) }
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { types.add(PrivacyPrefs.PERM_MIC); res.add(r) }
                    }
                }
                if (host == null || res.isEmpty()) { request.deny(); return }
                askSitePermission(host, types) { ok -> if (ok) request.grant(res.toTypedArray()) else request.deny() }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                val host = runCatching { Uri.parse(origin).host }.getOrNull()
                if (host == null) { callback.invoke(origin, false, false); return }
                askSitePermission(host, listOf(PrivacyPrefs.PERM_LOCATION)) { ok -> callback.invoke(origin, ok, false) }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) =
                enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                           params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    toast("No app found to pick files")
                    true
                }
            }
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            override fun onProgressChanged(view: WebView, p: Int) {
                if (tab === current) {
                    b.progress.setProgressCompat(p, true)
                    b.progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE
                }
            }
            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title.orEmpty()
            }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                val host = siteHost(view.url) ?: return
                if (icon == null) return
                val old = FaviconStore.get(this@MainActivity, host)
                if (old == null || icon.width > old.width) {
                    FaviconStore.save(this@MainActivity, host, icon)
                    if (b.homeView.visibility == View.VISIBLE) refreshHome()
                }
            }
        }

        return wv
    }
}
