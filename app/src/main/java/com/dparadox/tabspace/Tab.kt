package com.dparadox.tabspace

import android.webkit.WebView
import java.util.UUID

class Tab(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    val profileId: String,
    var name: String,
    var color: Int,
    var url: String,
    var pinned: Boolean = false,
    var avatarUri: String? = null,
) {
    var title: String = ""
    var webView: WebView? = null

    /** True once the url was handed to the WebView (a pre-created WebView stays empty until first shown). */
    var loaded: Boolean = false

    /** True while the NeZu Space home screen is shown on top of this tab. Not saved. */
    var homeShown: Boolean = false

    /** All tabs in the same AppProfile share one WebKit profile → shared cookies/session. */
    val webkitProfileName: String get() = "profile_$profileId"
}
