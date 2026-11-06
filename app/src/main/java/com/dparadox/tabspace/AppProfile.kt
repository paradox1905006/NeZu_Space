package com.dparadox.tabspace

import java.util.UUID

class AppProfile(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    var name: String,
    var color: Int,
    var avatarUri: String? = null,
    var searchEngine: SearchEngine = SearchEngine.GOOGLE
) {
    /** Home-screen shortcuts of this profile. */
    val bookmarks: MutableList<Bookmark> = mutableListOf()

    /** Downloads of this profile (shown in Menu -> Downloads, whichever tab started them). */
    val downloads: MutableList<DownloadRecord> = mutableListOf()

    /** One WebKit profile shared by all tabs inside this AppProfile. */
    val webkitProfileName: String get() = "profile_$id"
}
