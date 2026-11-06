# NezU Space

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
Multi-tab browser where every tab is an isolated browser (own cookies, storage, cache, history).

1. Android Studio (Koala+) -> File > Open -> this folder, wait for Gradle sync.
2. Build > Build Bundle(s) / APK(s) > Build APK(s)
   - debug APK:   app/build/outputs/apk/debug/app-debug.apk
   - release APK: ./gradlew assembleRelease  (signed with the debug key so it installs directly)
3. Change the home page in MainActivity.kt -> HOME_URL.

Isolation uses the WebView multi-profile API (needs a recent Android System WebView, v110+).

## v3.0 — Appearance & Help
- **Settings → Appearance**: System / Light / Dark theme and **Material You** (wallpaper colours, Android 12+) switch. UI is Material 3.
- **Settings → Background picture**: choose any image, adjust its opacity with a slider (live preview), remove it any time. The picture is copied into app storage, so it survives the original being deleted. Optional "Show behind web pages" for pages with no background of their own.
- **Settings → How to use NeZu Space**: expandable guide (tabs, profiles, bookmarks, downloads, cookies, theme, troubleshooting). Edit the text in `GuideActivity.kt` (`topics` list).
- Code: `ThemePrefs.kt` (all appearance logic), `GuideActivity.kt`, `SettingsActivity.kt`.
- `buildConfig = true` added to `app/build.gradle.kts` (AGP 8 disables BuildConfig by default; Settings reads the version from it).

## v3.1 — Home, swipe and animations
- **Home screen**: Home button in the bottom bar opens the NeZu Space home. The + tile adds a shortcut (name + address); Menu → Add to Home saves the current page. Tiles show the site logo (cached in app storage) and name. Long-press to edit or remove. Shortcuts belong to a profile; the background picture shows behind the home screen. Old per-tab bookmarks are merged into their profile on first start.
- **Search engine is per profile** (Menu → Search engine), not per tab. Old per-tab choices are migrated.
- **Downloads**: tap an entry for Open, Copy download link, Download again, Delete file, Remove from list.
- **Gestures**: swipe the address bar to change tab, swipe the bottom bar to change profile, swipe a tab card or profile row to close/delete it.
- **Animations** (Settings → Appearance → Animations, on by default): book page turn for tab/profile change, dust for close/delete, reverse dust for new tab/profile. Code: `DustView.kt`, `flipPage / dustOutPage / dustInPage / dustOutView` in `MainActivity.kt`.
- **Profile switch lag**: WebViews of other profiles are pre-created while the app is idle, saving is debounced, and heavy work runs under the animation.
- All emoji removed from the UI.

## v3.5 - Shortcut pictures, crop, long-press menus, Don't sleep

### Home shortcut pictures
- Adding a shortcut now takes the **picture of the website**: the page is read and the best picture is used (touch icon / large logo, then the biggest icon, `/favicon.ico`, the social-share picture, Google's icon service). The picture fills the round tile; tiny favicons sit in the middle. Old tiles that still hold a small icon look once for a better one.
- **Own picture**: Add / Edit shortcut has *Choose own image* and *Use website image*. Code: `FaviconStore.kt`, `Net.kt`, `HomeAdapter.kt`, `showBookmarkDialog` in `MainActivity.kt`; `Bookmark.iconUri` is saved by `TabStore.kt`.

### Crop screen (profile picture, tab avatar, shortcut picture)
- Every picture you choose goes through `CropActivity.kt`: move and zoom the picture under a round window to select any part, rotate 90 degrees, flip, then tap the check button. The result is saved as a **round** 512 x 512 PNG in app storage (`files/avatars`), so it stays round and survives the original being deleted. Replaced pictures are deleted.

### Long-press menus
- **Home shortcut**: Open in background, Open in new tab, Sort (name A-Z / Z-A, oldest / newest), Edit, Delete, Copy link, Share.
- **Picture in a web page** (for example image search results): View image, Download image, Share image (the picture itself), Search by image (Google Lens), Open link in new tab (when the picture is a link), Copy image link, Page info, Mark as ad (hides the picture and blocks that picture from now on).
- **Link in a web page**: Open in new tab, Open in background, Copy link, Share link.
- Not included: Picture mode, Scan QR code, Customize menu.
- Code: `showTileMenu`, `handleWebLongPress`, `showImageMenu`, `showLinkMenu`, `downloadImage`, `shareImage`, `markAsAd` in `MainActivity.kt`; `AdBlocker.markUrl`. `file_paths.xml` has a cache path for sharing.

### Don't sleep (Settings)
- Off, Always on (while the app is open), or 5 / 15 / 30 minutes. For minutes, the screen stays on until that time has passed **without a touch**; every touch restarts the timer. It only works while NeZu Space is in the foreground. Fullscreen video keeps the screen on as before. Code: `applyKeepAwake`, `onUserInteraction` in `MainActivity.kt`, `ThemePrefs.keepAwake`.

## v3.8 - Sounds
- **Settings -> Sounds**: master switch plus four sounds (tab/profile change, tab/profile delete, new tab/profile, app open). Each has its own on/off switch, Preview, Choose sound (your own audio, up to 15 s / 5 MB) and Reset. Defaults are in `app/src/main/res/raw`.
- Code: `SoundFx.kt` (playback, settings, custom files), hooks in `flipPage / dustOutPage / dustInPage / dustOutView` in `MainActivity.kt`, UI in `SettingsActivity.setupSounds` and `item_sound.xml`.

## License
Source code: MIT, see [LICENSE](LICENSE). Libraries (AndroidX, Material Components, WebKit) are Apache 2.0.
The default sound files in `res/raw` are separate assets: they are only covered by the MIT license if their author is the copyright holder. Replace them with sounds you own or with CC0 / permissively licensed ones before publishing (for example to F-Droid).

Store text and the icon for F-Droid are in `fastlane/metadata/android/en-US`. See also [CHANGELOG](CHANGELOG.md), [PRIVACY](PRIVACY.md) and [CONTRIBUTING](CONTRIBUTING.md).
