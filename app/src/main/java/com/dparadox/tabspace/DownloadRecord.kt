package com.dparadox.tabspace

import android.app.DownloadManager
import org.json.JSONObject
import java.util.UUID

/**
 * One download of a profile.
 * dmId >= 0  : old record made by Android's DownloadManager (no pause/resume).
 * dmId == -1 : made by [DownloadEngine] (pause / resume supported).
 */
class DownloadRecord(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    val dmId: Long = -1L,
    @Volatile var fileName: String,
    val url: String,
    @Volatile var totalBytes: Long = -1L,
    val addedAt: Long = System.currentTimeMillis(),
    @Volatile var status: Int = DownloadManager.STATUS_RUNNING,
    @Volatile var filePath: String? = null,
    @Volatile var mimeType: String = "",
    @Volatile var downloadedBytes: Long = 0L,
    var userAgent: String = "",
) {
    /** Bytes per second, only while running. Not saved. */
    @Volatile var speed: Long = 0L

    val isEngine: Boolean get() = dmId < 0

    val isActive: Boolean
        get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("dmId", dmId).put("fileName", fileName)
        .put("url", url).put("totalBytes", totalBytes)
        .put("addedAt", addedAt).put("status", status)
        .put("filePath", filePath ?: "").put("mimeType", mimeType)
        .put("downloaded", downloadedBytes).put("ua", userAgent)

    companion object {
        /** The download finished earlier but the file is gone from the phone. */
        const val STATUS_MISSING = -1

        fun fromJson(d: JSONObject): DownloadRecord = DownloadRecord(
            id = d.optString("id").ifBlank { UUID.randomUUID().toString().replace("-", "").take(12) },
            dmId = d.optLong("dmId", -1L),
            fileName = d.getString("fileName"),
            url = d.getString("url"),
            totalBytes = d.optLong("totalBytes", -1L),
            addedAt = d.optLong("addedAt", 0),
            status = d.optInt("status", DownloadManager.STATUS_SUCCESSFUL),
            filePath = d.optString("filePath").ifBlank { null },
            mimeType = d.optString("mimeType", ""),
            downloadedBytes = d.optLong("downloaded", 0),
            userAgent = d.optString("ua", ""),
        )
    }
}
