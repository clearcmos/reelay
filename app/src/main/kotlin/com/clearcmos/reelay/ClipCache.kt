package com.clearcmos.reelay

import android.content.Context
import java.io.File

/**
 * App-private store for downloaded and re-encoded clips, pruned by age.
 *
 * Nothing Reelay produces is written to the Gallery: TikTok reads the finished clip
 * through a FileProvider URI into this directory, and [CleanupJobService] plus the next
 * run delete anything older than [RETENTION_MS].
 */
class ClipCache(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun file(name: String): File = File(dir, name)

    /** Deletes files last modified more than [maxAgeMs] before [now]; returns how many went. */
    fun prune(now: Long = System.currentTimeMillis(), maxAgeMs: Long = RETENTION_MS): Int {
        val stale = dir.listFiles()?.filter { now - it.lastModified() > maxAgeMs } ?: return 0
        return stale.count { it.delete() }
    }

    companion object {
        /** Long enough for TikTok to finish posting if it re-reads the file, short enough to leave no clutter. */
        const val RETENTION_MS = 60 * 60 * 1000L

        fun of(context: Context): ClipCache = ClipCache(File(context.cacheDir, "reelay"))
    }
}
