package net.kaneonexus.dartclicker

// ─────────────────────────────────────────────
//  Game Modes
// ─────────────────────────────────────────────

enum class GameMode(val label: String, val startScore: Int = 0) {
    CRICKET("Cricket"),
    GAME_301("301", 301),
    GAME_501("501", 501),
    GAME_701("701", 701),
    GAME_1001("1001", 1001)
}

// ─────────────────────────────────────────────
//  Target — what segment/multiplier to aim at
// ─────────────────────────────────────────────

data class DartTarget(
    val segment: Int,      // 1–20, 25=bull, 50=bullseye
    val multiplier: Int,   // 1=single, 2=double, 3=treble
    val label: String
)

// ─────────────────────────────────────────────
//  Cricket State
// ─────────────────────────────────────────────

val CRICKET_SEGMENTS = listOf(15, 16, 17, 18, 19, 20, 25)

data class CricketState(
    val marks: MutableMap<Int, Int> = CRICKET_SEGMENTS.associateWith { 0 }.toMutableMap(),
    var score: Int = 0
) {
    fun markSegment(segment: Int, hits: Int) {
        if (segment !in marks) return
        val current = marks[segment] ?: 0
        val newTotal = current + hits
        // Once closed (3+), extra hits score points
        if (current < 3 && newTotal > 3) {
            score += segment * (newTotal - 3)
        } else if (current >= 3) {
            score += segment * hits
        }
        marks[segment] = newTotal.coerceAtMost(6) // cap display at 6
    }

    fun isClosed(segment: Int) = (marks[segment] ?: 0) >= 3
    fun allClosed() = CRICKET_SEGMENTS.all { isClosed(it) }

    fun displayMarks(segment: Int): String {
        val m = (marks[segment] ?: 0).coerceAtMost(3)
        return when (m) {
            0 -> "   "
            1 -> " / "
            2 -> " X "
            else -> "[X]"
        }
    }
}

// ─────────────────────────────────────────────
//  Game Engine
// ─────────────────────────────────────────────

class GameEngine(val mode: GameMode) {

    private var cricket = CricketState()
    private var ohOneRemaining = mode.startScore
    private var dartsThrown = 0
    private var totalScore = 0

    var onScoreUpdate: (() -> Unit)? = null
    var onGameOver: ((String) -> Unit)? = null

    // Returns the strategically best next target
    fun getNextTarget(): DartTarget {
        return when (mode) {
            GameMode.CRICKET -> nextCricketTarget()
            else -> nextOhOneTarget()
        }
    }

    // ── Cricket target logic ──────────────────
    private fun nextCricketTarget(): DartTarget {
        // Priority order: 20, 19, 18, 17, 16, 15, Bull — close highest first
        val openSegs = CRICKET_SEGMENTS
            .filter { !cricket.isClosed(it) }
            .sortedDescending()

        return if (openSegs.isNotEmpty()) {
            val seg = openSegs.first()
            val marksNeeded = 3 - (cricket.marks[seg] ?: 0)
            val mult = when {
                marksNeeded >= 3 -> 3
                marksNeeded == 2 -> 2
                else -> 1
            }
            val segLabel = if (seg == 25) "Bull" else "$seg"
            val multLabel = when (mult) { 3 -> "T"; 2 -> "D"; else -> "" }
            DartTarget(seg, mult, "$multLabel$segLabel")
        } else {
            // All closed — aim for bull to score extra
            DartTarget(25, 2, "Bull")
        }
    }

    // ── 01 target logic ───────────────────────
    private fun nextOhOneTarget(): DartTarget {
        val r = ohOneRemaining
        return when {
            r > 62   -> DartTarget(20, 3, "T20")   // 60 pts
            r == 62  -> DartTarget(10, 2, "D10")   // leaves D16
            r == 50  -> DartTarget(25, 2, "Bull")  // bull checkout
            r == 40  -> DartTarget(20, 2, "D20")
            r == 32  -> DartTarget(16, 2, "D16")
            r == 24  -> DartTarget(12, 2, "D12")
            r == 16  -> DartTarget(8,  2, "D8")
            r == 8   -> DartTarget(4,  2, "D4")
            r == 2   -> DartTarget(1,  2, "D1")
            r in 41..62 -> {
                // Set up a double finish
                val leaveNeeded = if (r % 2 == 0) r else r - 1
                val single = r - leaveNeeded
                if (single in 1..20) DartTarget(single, 1, "$single")
                else DartTarget(20, 1, "20")
            }
            r <= 40 && r % 2 == 0 -> DartTarget(r / 2, 2, "D${r / 2}")
            r <= 40 && r % 2 != 0 -> DartTarget(1, 1, "1") // make even
            else -> DartTarget(20, 3, "T20")
        }
    }

    // Record a dart hit (segment + multiplier)
    fun recordHit(segment: Int, multiplier: Int) {
        dartsThrown++
        val points = segment * multiplier

        when (mode) {
            GameMode.CRICKET -> {
                cricket.markSegment(segment, multiplier)
                totalScore = cricket.score
                onScoreUpdate?.invoke()
                if (cricket.allClosed()) {
                    onGameOver?.invoke("Cricket closed in $dartsThrown darts!")
                }
            }
            else -> {
                val newRemaining = ohOneRemaining - points
                when {
                    newRemaining == 0 && multiplier == 2 -> {
                        // Valid double-out checkout!
                        ohOneRemaining = 0
                        totalScore += points
                        onScoreUpdate?.invoke()
                        onGameOver?.invoke("CHECKOUT! $dartsThrown darts — ${mode.label} complete!")
                    }
                    newRemaining > 1 -> {
                        ohOneRemaining = newRemaining
                        totalScore += points
                        onScoreUpdate?.invoke()
                    }
                    else -> {
                        // Bust — no change
                        onScoreUpdate?.invoke() // still refresh display
                    }
                }
            }
        }
    }

    // ── Display strings ───────────────────────

    fun getScoreDisplay(): String = when (mode) {
        GameMode.CRICKET -> buildString {
            appendLine("── Cricket ──")
            CRICKET_SEGMENTS.reversed().forEach { seg ->
                val label = if (seg == 25) "Bull" else " $seg "
                val m = cricket.displayMarks(seg)
                appendLine("$label  $m")
            }
            append("Score: ${cricket.score}")
        }
        else -> "Remaining: $ohOneRemaining\nDarts: $dartsThrown"
    }

    fun getNextTargetLabel(): String = getNextTarget().label

    fun getDartsThrown() = dartsThrown

    fun reset() {
        cricket = CricketState()
        ohOneRemaining = mode.startScore
        dartsThrown = 0
        totalScore = 0
    }
}
