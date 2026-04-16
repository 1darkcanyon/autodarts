package net.kaneonexus.dartclicker

import android.content.Context

/**
 * Persists screen coordinates for each dartboard segment.
 * Key format: "seg_{segment}_x{multiplier}"  e.g. "seg_20_x3" = Treble 20
 * Special keys: "board_center", "throw_origin"
 */
data class ScreenTarget(val x: Float, val y: Float, val radius: Float = 18f)

class TargetConfig(context: Context) {

    private val prefs = context.getSharedPreferences("nexus_dart_targets", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, ScreenTarget>()

    init { loadAll() }

    // ── Save / Load ───────────────────────────

    private fun key(segment: Int, multiplier: Int) = "seg_${segment}_x${multiplier}"

    fun setTarget(segment: Int, multiplier: Int, x: Float, y: Float, radius: Float = 18f) {
        val k = key(segment, multiplier)
        cache[k] = ScreenTarget(x, y, radius)
        prefs.edit()
            .putFloat("${k}_x", x)
            .putFloat("${k}_y", y)
            .putFloat("${k}_r", radius)
            .apply()
    }

    fun getTarget(segment: Int, multiplier: Int): ScreenTarget? =
        cache[key(segment, multiplier)]

    fun getBoardCenter(): ScreenTarget? = cache["board_center"]

    fun setBoardCenter(x: Float, y: Float) {
        cache["board_center"] = ScreenTarget(x, y, 5f)
        prefs.edit()
            .putFloat("board_center_x", x)
            .putFloat("board_center_y", y)
            .apply()
    }

    /** The point from which swipe gestures originate (below the board) */
    fun getThrowOrigin(): ScreenTarget? = cache["throw_origin"]

    fun setThrowOrigin(x: Float, y: Float) {
        cache["throw_origin"] = ScreenTarget(x, y, 5f)
        prefs.edit()
            .putFloat("throw_origin_x", x)
            .putFloat("throw_origin_y", y)
            .apply()
    }

    private fun loadAll() {
        // Load board center
        val bcx = prefs.getFloat("board_center_x", 0f)
        val bcy = prefs.getFloat("board_center_y", 0f)
        if (bcx > 0f) cache["board_center"] = ScreenTarget(bcx, bcy, 5f)

        // Load throw origin
        val tox = prefs.getFloat("throw_origin_x", 0f)
        val toy = prefs.getFloat("throw_origin_y", 0f)
        if (tox > 0f) cache["throw_origin"] = ScreenTarget(tox, toy, 5f)

        // Load all segment targets
        prefs.all.keys
            .filter { it.endsWith("_x") && it.startsWith("seg_") }
            .map { it.removeSuffix("_x") }
            .forEach { k ->
                val x = prefs.getFloat("${k}_x", 0f)
                val y = prefs.getFloat("${k}_y", 0f)
                val r = prefs.getFloat("${k}_r", 18f)
                if (x > 0f) cache[k] = ScreenTarget(x, y, r)
            }
    }

    fun isCalibrated(): Boolean = getBoardCenter() != null

    fun hasThrowOrigin(): Boolean = getThrowOrigin() != null

    /**
     * Auto-generate segment targets from board center + radius.
     * Call this after setting board center if you don't want to
     * manually calibrate every segment.
     *
     * @param boardRadiusPx pixel radius of the full board on screen
     */
    fun autoGenerateFromCenter(boardRadiusPx: Float) {
        val center = getBoardCenter() ?: return
        val segments = listOf(20,1,18,4,13,6,10,15,2,17,3,19,7,16,8,11,14,9,12,5)

        segments.forEachIndexed { i, seg ->
            val angleDeg = i * 18.0 - 90.0 // 20 is at top
            val angleRad = Math.toRadians(angleDeg)

            // Single
            val singleR = boardRadiusPx * 0.55f
            setTarget(seg, 1,
                (center.x + singleR * Math.cos(angleRad)).toFloat(),
                (center.y + singleR * Math.sin(angleRad)).toFloat(), 18f)

            // Double (outer ring)
            val doubleR = boardRadiusPx * 0.90f
            setTarget(seg, 2,
                (center.x + doubleR * Math.cos(angleRad)).toFloat(),
                (center.y + doubleR * Math.sin(angleRad)).toFloat(), 8f)

            // Treble (inner narrow ring)
            val trebleR = boardRadiusPx * 0.55f
            setTarget(seg, 3,
                (center.x + trebleR * Math.cos(angleRad)).toFloat(),
                (center.y + trebleR * Math.sin(angleRad)).toFloat(), 8f)
        }

        // Bull (25)
        setTarget(25, 1, center.x, center.y, 22f)
        setTarget(25, 2, center.x, center.y, 12f)
    }

    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }
}
