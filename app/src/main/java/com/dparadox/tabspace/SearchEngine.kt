package com.dparadox.tabspace

import android.net.Uri

enum class SearchEngine(val label: String, val icon: String, val queryUrl: String) {
    GOOGLE    ("Google",     "G",  "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "DD", "https://duckduckgo.com/?q="),
    BING      ("Bing",       "B",  "https://www.bing.com/search?q="),
    STARTPAGE ("StartPage",  "SP", "https://www.startpage.com/search?q=");

    fun buildUrl(query: String): String = queryUrl + Uri.encode(query)
}
