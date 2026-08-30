package com.clearcmos.reelay

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Builds the intent that opens TikTok's editor with a video loaded.
 *
 * TikTok's `SystemShareActivity` accepts `ACTION_SEND` with a video MIME type and lands on the editor
 * with a "Your Story" button directly on screen (verified on TikTok 46.6.3, 2026-08-29).
 * There is no public entry point that posts a story without that tap.
 */
object TikTokHandoff {
    private val PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    private const val SHARE_ACTIVITY = "com.ss.android.ugc.aweme.share.SystemShareActivity"

    /** Returns a resolvable TikTok share intent for [video], or null when TikTok is not installed. */
    fun buildIntent(packageManager: PackageManager, video: Uri): Intent? {
        val base =
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, video)
                clipData = ClipData.newRawUri("video", video)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        for (pkg in PACKAGES) {
            val explicit = Intent(base).setClassName(pkg, SHARE_ACTIVITY)
            if (packageManager.resolveActivity(explicit, 0) != null) return explicit
            val byPackage = Intent(base).setPackage(pkg)
            if (packageManager.resolveActivity(byPackage, 0) != null) return byPackage
        }
        return null
    }
}
