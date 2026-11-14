package com.dparadox.tabspace

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/** "How to use" screen — expandable Material cards. Tap a title to open or close it. */
class GuideActivity : AppCompatActivity() {

    private data class Topic(val title: String, val body: String)

    private val topics = listOf(
        Topic("Quick start",
            "NeZu Space is a multi-tab browser. Every profile has its own cookies and logins, so accounts never mix.\n\n" +
            "• Type a web address or search words in the bar at the top, then press Go.\n" +
            "• The bottom bar has: Back, Forward, Home, New tab, Tabs, Profile and Menu.\n" +
            "• Tabs, profiles, home shortcuts and settings are saved automatically."),

        Topic("Home screen",
            "Tap the Home button in the bottom bar (or Menu → Home) to open the NeZu Space home screen.\n\n" +
            "• Tap the + tile to add a shortcut: type a name and an address.\n" +
            "• Menu → Add to Home saves the page you are on.\n" +
            "• Every shortcut shows the site's logo and name. Tap it to open the site in the current tab.\n" +
            "• Long-press a shortcut to edit or remove it.\n" +
            "• Home screens belong to a profile, so each profile keeps its own shortcuts.\n" +
            "• If you set a background picture, it also shows on the home screen."),

        Topic("Address bar & search",
            "• A full address like example.com opens that site.\n" +
            "• Words with a space (or without a dot) are searched with the profile's search engine.\n" +
            "• The reload button sits inside the bar, on the right.\n" +
            "• The coloured dot on the left shows your current profile's colour."),

        Topic("Tabs",
            "• New tab: tap + in the bottom bar, or \"New tab\" in the tab list.\n" +
            "• Tab list: tap the tabs icon (the number is how many tabs this profile has), then tap a card to open it.\n" +
            "• Swipe a card sideways to close it, or use its Close button.\n" +
            "• Swipe the address bar left or right to go to the next or previous tab.\n" +
            "• Each card has Pin, Edit (name and colour) and Close.\n" +
            "• Pinned tabs stay at the front and cannot be closed until you unpin them.\n" +
            "• Back button order: close the tab list → close the home screen → go back in the page → leave the app."),

        Topic("Profiles",
            "A profile is a separate browser identity with its own cookies, logins, storage, cache, search engine and home shortcuts. Use one for work, one for personal, or to stay signed in to two accounts on the same site.\n\n" +
            "• Tap the profile icon in the bottom bar to switch profile or create a new one.\n" +
            "• Swipe the bottom bar left or right to go to the next or previous profile.\n" +
            "• Long-press a profile to rename or recolour it, change its avatar, or delete it.\n" +
            "• Swipe a profile sideways in the list to delete it.\n" +
            "• Deleting a profile deletes all of its tabs and data.\n" +
            "• Profile isolation needs a recent Android System WebView (v110+). Update it from the Play Store if the app warns you."),

        Topic("Search engine",
            "Menu → Search engine. Choose Google, DuckDuckGo, Bing or StartPage. The choice applies to every tab of the current profile."),

        Topic("Downloads",
            "• Tap any download link. A window shows live progress and speed; use Pause / Resume there.\n" +
            "• Menu → Downloads lists the downloads of the current profile (from all its tabs). Empty? It says so.\n" +
            "• \"Add download\": paste a link (the Paste button fills it from the clipboard) and it downloads in this profile.\n" +
            "• Every running download has Pause / Resume (Retry if it failed). A download that was cut off, or stopped when the app closed, can be resumed.\n" +
            "• Tap a download for its options: Open, Pause / Resume, Copy download link, Download again, Delete file, Remove from list, Cancel download.\n" +
            "• Delete file removes the file from your phone. Remove from list only clears the entry.\n" +
            "• \"Clear history\" removes finished entries only; the files stay on your phone.\n" +
            "• Finished files are saved in your phone's Downloads folder (Android 10 and newer).\n" +
            "• Downloads keep running in the background with a notification; allow notifications when asked."),

        Topic("Shortcuts on the phone home screen",
            "• Long-press a tile on the NeZu Space home → Add to phone home screen.\n" +
            "• Menu → Add to phone home screen puts the current page on your phone's home screen.\n" +
            "• Long-press a profile → Add to phone home screen: opens that profile.\n" +
            "• Tapping a shortcut opens NeZu Space in the right profile.\n" +
            "• Your launcher must support pinned shortcuts."),

        Topic("History",
            "Menu → History.\n\n" +
            "Shows the pages you opened in the current profile, newest first (up to 500).\n" +
            "• Type in the search box to find a page.\n" +
            "• Tap a page to open it in the current tab.\n" +
            "• Long-press a page to remove it.\n" +
            "• \"Clear all\" empties the list.\n\n" +
            "Private profiles do not record history. You can also clear it with Menu → Clear data → History."),

        Topic("Privacy and security",
            "Settings → Privacy & Security.\n\n" +
            "Lock and screen\n" +
            "• App lock: fingerprint, face or your phone's PIN / pattern when the app opens. \"Lock again\" sets how long the app may be out of sight.\n" +
            "• Locked profiles: only these profiles ask for the unlock.\n" +
            "• Hide screen in recent apps: no screenshots and a blank preview. Always on with the app lock, a locked profile or a private profile.\n\n" +
            "Private browsing\n" +
            "• Private profiles forget cookies, storage, cache and history when the app is closed (or the next time it starts).\n" +
            "• Clear data when the app closes: choose what to remove from every profile.\n\n" +
            "Web\n" +
            "• Remove tracking from links (utm_, fbclid, gclid ...), Block trackers, Block third-party cookies.\n" +
            "• HTTPS only: http links open as https, and you are asked before http is used.\n" +
            "• Safe Browsing warns about dangerous pages. A certificate error shows a warning page you can go back from.\n" +
            "• Ask before opening other apps.\n" +
            "• Site permissions: camera, microphone and location are asked once per site. Reset them here or in Menu → Site info.\n\n" +
            "Downloads and data\n" +
            "• Warn before risky downloads: program files (apk, exe ...) and downloads that no tap started.\n" +
            "• The clipboard is emptied one minute after Copy cookies.\n" +
            "• Encrypt saved data: tabs, profiles, shortcuts and the download list use a key of the phone's Keystore. Pictures and downloaded files are not encrypted.\n\n" +
            "Menu → Site info shows the address, whether the connection is secure and the certificate."),

        Topic("Clear data",
            "Menu → Clear data.\n\n" +
            "Choose what to clear for the current profile only:\n" +
            "• Cache: saved page files.\n" +
            "• Form data: saved form entries.\n" +
            "• History: the saved history list and the back/forward list of this profile's tabs.\n" +
            "• Web storage: data that sites keep (local storage, IndexedDB).\n" +
            "• Cookies (login status): you are signed out of every site in this profile.\n" +
            "• App cache: temporary files of the whole app.\n\n" +
            "\"Select all\" ticks everything."),

        Topic("Cookies",
            "Menu → Cookies\n\n" +
            "• Copy cookies: copies the current page's cookies to the clipboard.\n" +
            "• Clear cookies: wipes every cookie in the current profile - you will be signed out of all its sites.\n\n" +
            "Never share copied cookies with anyone; they can give access to your accounts."),

        Topic("Avatars & colours",
            "• Tab: Menu → Set tab avatar, or Edit tab to rename it and pick a colour.\n" +
            "• Profile: long-press it in the profile list → Change avatar, or Rename or recolor."),

        Topic("Theme & Material You",
            "Settings → Appearance.\n\n" +
            "• Theme: System follows your phone; Light and Dark force a mode. Open tabs reload when the theme changes.\n" +
            "• Material You colours: the app takes its colours from your wallpaper (Android 12 or newer). Turn it off to use the app's own blue palette.\n" +
            "• Animations: page turn when you change tab or profile, dust when you close something and when a new tab or profile appears. Turn it off for instant changes."),

        Topic("Sounds",
            "Settings → Sounds.\n\n" +
            "• Sound effects: master switch for every sound.\n" +
            "• Four sounds: tab or profile change, tab or profile delete, new tab or profile, and app open. Each has its own on/off switch.\n" +
            "• Only the app open sound comes with a default. For the others, tap Choose sound and pick your own audio file (up to 15 seconds); until you do, they stay silent.\n" +
            "• Preview plays the sound so you can hear it.\n" +
            "• Reset (app open) goes back to the default; Remove deletes your sound."),

        Topic("Background (picture, GIF or video)",
            "Settings → Background.\n\n" +
            "1. Tap \"Choose picture, GIF or video\" and pick a picture, an animated GIF (up to 30 MB, animates on Android 9 and newer) or a video (up to 150 MB, plays in a loop).\n" +
            "2. Drag \"Picture opacity\": low is faint, high is vivid. You see the change live.\n" +
            "3. For a video, \"Video sound\" turns its sound on or off (off by default).\n" +
            "4. \"Remove\" goes back to plain colours.\n\n" +
            "A video plays behind the browser screen; Settings and this guide show its first frame. A moving background uses more battery.\n\n" +
            "The picture shows behind the top and bottom bars, the home screen, the tab list and Settings. Websites paint their own background, so the picture does not show through them - unless you turn on \"Show behind web pages\", which only affects pages that have no background of their own."),

        Topic("Make it your default browser",
            "Android Settings → Apps → Default apps → Browser app → NeZu Space. Links you tap in other apps will then open in a new tab here."),

        Topic("Troubleshooting",
            "• Profiles share logins → update Android System WebView.\n" +
            "• Picture not visible → check that opacity is not 0%.\n" +
            "• Can't close a tab → it is pinned; unpin it first.\n" +
            "• Shortcut shows a letter instead of a logo → it appears once the site logo has been downloaded; check your internet connection.\n" +
            "• Download did not start → check free storage and your internet connection.")
    )

    override fun onStart() {
        super.onStart()
        LockState.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (LockState.appLockNeeded(this)) { finish(); return }
        if (PrivacyPrefs.secureScreen(this) || PrivacyPrefs.appLock(this))
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        LockState.onStop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
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

        val list = findViewById<LinearLayout>(R.id.guideList)
        topics.forEachIndexed { i, t ->
            val card = layoutInflater.inflate(R.layout.item_guide, list, false)
            val body = card.findViewById<TextView>(R.id.guideBody)
            val arrow = card.findViewById<ImageView>(R.id.guideArrow)
            card.findViewById<TextView>(R.id.guideTitle).text = t.title
            card.findViewById<TextView>(R.id.guideNumber).text = (i + 1).toString()
            body.text = t.body
            card.findViewById<View>(R.id.guideHeader).setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.animate().rotation(if (open) 180f else 0f).setDuration(150).start()
            }
            if (i == 0) { // first topic starts open
                body.visibility = View.VISIBLE
                arrow.rotation = 180f
            }
            list.addView(card)
        }
    }
}
