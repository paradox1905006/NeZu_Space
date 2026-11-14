# Changelog

## Unreleased - Material 3 redesign
- New look on every screen, built on Material 3 with Material You: a full rose colour scheme for light and dark (wallpaper colours still replace it on Android 12+), Google Sans Flex type and softer, rounder shapes.
- Browser: Material 3 search bar with the profile colour as a badge, floating bottom dock, profile button shows a coloured avatar with the initial.
- Home: larger app icon, profile chip, "Shortcuts" title and rounded launcher-style tiles.
- Tab panel: drag handle, title with tab count, "New tab" button and rounded tab rows with a tonal highlight for the open tab.
- Menu is now a bottom sheet with the current page on top, 8 quick actions and an icon list. Long-press menus have icons and rounded corners.
- Profiles open in a bottom sheet; the current profile is highlighted with a check mark.
- Settings and the guide: large collapsing title, grouped cards, an icon for every option, tap anywhere on a row to flip its switch, switches with check / cross icons.
- Dialogs, downloads, history, colour picker (ring + check mark) and the app-lock screen restyled to match.

## 4.1
- New Menu > History (per profile): search, open, remove one, clear all. Not recorded in private profiles; cleared by Clear data > History and by "clear data when the app closes".

## 4.0
- New Settings > Privacy & Security: app lock, locked profiles, hide screen in recent apps, private profiles, clear data when the app closes, remove tracking from links, block trackers, block third-party cookies, HTTPS only, Safe Browsing, ask before opening other apps, site permissions, risky-download warning, clipboard clearing, encrypted saved data.
- Certificate errors show a warning page; WebView file and content access are off.
- New Menu > Site info.

## 3.9
- Sounds: only the app open sound has a default; the others are silent until you choose your own audio.
- Downloads: real file names instead of random ids, downloads from sites that need a login (profile cookies and referer are sent), and `blob:` / `data:` downloads.
- New Menu > Clear data: choose Cache, Form data, History, Web storage, Cookies and App cache for the current profile.
- Background can now be an animated GIF or a looping video, with a switch for the video's sound.
- License changed to GPL-3.0-or-later.

## 3.8
- Sound effects: tab/profile change, delete, create and app open, each with its own switch, preview and custom sound (Settings > Sounds), plus a master switch.
- Added license, fastlane metadata, changelog and privacy notes.

## 3.5
- Home shortcuts take the picture of the website (touch icon / large logo, biggest icon, favicon, share picture, Google's icon service); tiny favicons sit in the middle of the round tile. Add / Edit shortcut has "Choose own image" and "Use website image".
- Crop screen for every picture you choose (profile picture, tab avatar, shortcut picture): move and zoom under a round window, rotate, flip. Saved as a round 512 x 512 PNG.
- Long-press menus: home shortcut (open in background / new tab, sort, edit, delete, copy, share), picture in a page (view, download, share, search by image, open link in new tab, copy image link, page info, mark as ad) and link in a page (open in new tab / background, copy, share).
- "Don't sleep" in Settings: off, always on, or 5 / 15 / 30 minutes without a touch.

## 3.1
- Home screen with shortcut tiles (+ tile, Menu > Add to Home); shortcuts belong to a profile and the background picture shows behind the home screen.
- Search engine is per profile instead of per tab.
- Downloads: tap an entry for Open, Copy download link, Download again, Delete file, Remove from list.
- Gestures: swipe the address bar for tab change, the bottom bar for profile change, a tab card or profile row to close or delete it.
- Animations (page turn, dust, reverse dust) with a switch in Settings; less lag when switching profile.
- All emoji removed from the UI.

## 3.0
- Settings > Appearance: system / light / dark theme and Material You colours; the UI is Material 3.
- Background picture with adjustable opacity and an optional "Show behind web pages".
- In-app guide (Settings > How to use NeZu Space).
