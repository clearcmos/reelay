package com.clearcmos.reelay

/** Picks the H.264 bitrate for the re-encode: generous enough that TikTok gets no visible second-generation loss. */
object EncodingBudget {
    const val MIN_BITRATE = 2_000_000
    const val MAX_BITRATE = 8_000_000
    const val REFERENCE_FPS = 30.0

    /** 4 bits per pixel per second at 30 fps, scaled with the frame rate. */
    private const val BITS_PER_PIXEL_PER_SECOND_AT_30 = 4.0

    /**
     * Returns max(twice the source bitrate, a per-pixel floor scaled by frame rate), clamped
     * to [MIN_BITRATE]..[MAX_BITRATE]. A null or zero [sourceBps] means the source rate is
     * unknown; a null [fps] is treated as 30.
     */
    fun bitrateFor(sourceBps: Int?, width: Int, height: Int, fps: Double? = null): Int {
        val fpsScale = ((fps ?: REFERENCE_FPS) / REFERENCE_FPS).coerceAtLeast(0.5)
        val floor = width.toLong() * height.toLong() * BITS_PER_PIXEL_PER_SECOND_AT_30 * fpsScale
        val doubledSource = (sourceBps ?: 0).toDouble() * 2
        return maxOf(floor, doubledSource).coerceIn(MIN_BITRATE.toDouble(), MAX_BITRATE.toDouble()).toInt()
    }
}
