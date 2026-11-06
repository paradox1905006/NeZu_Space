package com.dparadox.tabspace

import java.util.UUID

data class Bookmark(
    val id: String = UUID.randomUUID().toString().take(8),
    var title: String,
    var url: String,
    /** Own picture chosen by the user (round PNG in app storage); null = picture of the website. */
    var iconUri: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)
