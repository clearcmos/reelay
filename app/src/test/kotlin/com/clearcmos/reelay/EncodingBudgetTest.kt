package com.clearcmos.reelay

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodingBudgetTest {
    @Test
    fun `per pixel floor wins over a low source bitrate`() {
        // 720x1280 at 4 bits per pixel per second is 3.69 Mbps; twice the 1.68 Mbps source is less.
        assertEquals(3_686_400, EncodingBudget.bitrateFor(1_680_000, 720, 1280))
    }

    @Test
    fun `high source bitrate is doubled up to the cap`() {
        assertEquals(6_000_000, EncodingBudget.bitrateFor(3_000_000, 720, 1280))
        assertEquals(EncodingBudget.MAX_BITRATE, EncodingBudget.bitrateFor(6_000_000, 720, 1280))
    }

    @Test
    fun `unknown source bitrate falls back to the floor`() {
        assertEquals(EncodingBudget.MAX_BITRATE, EncodingBudget.bitrateFor(null, 1080, 1920))
        assertEquals(EncodingBudget.MIN_BITRATE, EncodingBudget.bitrateFor(0, 480, 854))
    }

    @Test
    fun `higher frame rate scales the floor`() {
        // 720x1280 at 60 fps doubles the 30 fps floor of 3.69 Mbps.
        assertEquals(7_372_800, EncodingBudget.bitrateFor(1_680_000, 720, 1280, fps = 60.0))
        assertEquals(EncodingBudget.MAX_BITRATE, EncodingBudget.bitrateFor(3_960_000, 1080, 1920, fps = 60.0))
    }
}
