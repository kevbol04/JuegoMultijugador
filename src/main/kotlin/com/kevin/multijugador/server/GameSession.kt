package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.Difficulty
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

enum class GameMode { PVP, PVE }

data class GameSession(
    val id: String,
    val mode: GameMode,
    val playerX: ClientConnection,
    val playerO: ClientConnection?,
    val difficulty: Difficulty? = null,

    val totalRounds: Int = 3,
    var round: Int = 1,
    var xWins: Int = 0,
    var oWins: Int = 0,

    var board: Array<CharArray>,
    var next: Char,

    val timeLimitSec: Int = 30,
    val turbo: Boolean = false,

    var turnTimer: ScheduledFuture<*>? = null,
    val turnToken: AtomicLong = AtomicLong(0),

    var turnStartedAtMs: Long = System.currentTimeMillis(),

    var xMoveTimeMs: Long = 0L,
    var oMoveTimeMs: Long = 0L,
    var xMoveCount: Int = 0,
    var oMoveCount: Int = 0,

    var xCellCounts: IntArray = IntArray(25),
    var oCellCounts: IntArray = IntArray(25)
) {
    fun winsNeeded(): Int = (totalRounds / 2) + 1

    fun resetRoundMetrics() {
        turnStartedAtMs = System.currentTimeMillis()
    }

    fun registerMove(symbol: Char, row: Int, col: Int, moveDurationMs: Long) {
        val idx = row * 5 + col
        if (idx !in 0..24) return

        if (symbol == 'X') {
            xMoveTimeMs += moveDurationMs.coerceAtLeast(0L)
            xMoveCount += 1
            xCellCounts[idx] += 1
        } else {
            oMoveTimeMs += moveDurationMs.coerceAtLeast(0L)
            oMoveCount += 1
            oCellCounts[idx] += 1
        }
    }
}