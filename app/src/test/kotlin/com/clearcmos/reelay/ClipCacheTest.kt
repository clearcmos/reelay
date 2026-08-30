package com.clearcmos.reelay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `prune deletes only files older than the retention window`() {
        val cache = ClipCache(File(folder.root, "reelay"))
        val now = 10_000_000_000L
        val old = cache.file("old.mp4").apply {
            writeText("x")
            setLastModified(now - ClipCache.RETENTION_MS - 1)
        }
        val fresh = cache.file("fresh.mp4").apply {
            writeText("x")
            setLastModified(
                now - ClipCache.RETENTION_MS + 60_000
            )
        }
        assertEquals(1, cache.prune(now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `prune on an empty or missing directory is a no-op`() {
        val cache = ClipCache(File(folder.root, "reelay"))
        assertEquals(0, cache.prune())
        assertTrue(File(folder.root, "reelay").isDirectory)
    }
}
