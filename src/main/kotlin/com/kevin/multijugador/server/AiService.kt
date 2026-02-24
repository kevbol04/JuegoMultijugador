package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.Difficulty
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class AiService {

    fun chooseMove(board: Array<CharArray>, difficulty: Difficulty): Pair<Int, Int> {
        val moves = availableMoves(board)
        if (moves.isEmpty()) throw IllegalStateException("No hay movimientos")

        return when (difficulty) {
            Difficulty.EASY -> moves.random()
            Difficulty.MEDIUM -> if (Random.nextBoolean()) moves.random() else bestMove(board)
            Difficulty.HARD -> bestMove(board)
        }
    }

    private fun bestMove(board: Array<CharArray>): Pair<Int, Int> {
        var bestScore = Int.MIN_VALUE
        var best: Pair<Int, Int> = availableMoves(board).first()

        for ((r, c) in availableMoves(board)) {
            board[r][c] = 'O'
            val score = minimax(board, isMaximizing = false)
            board[r][c] = ' '
            if (score > bestScore) {
                bestScore = score
                best = r to c
            }
        }
        return best
    }

    private fun minimax(board: Array<CharArray>, isMaximizing: Boolean): Int {
        val w = checkWinner(board)
        if (w == 'O') return 10
        if (w == 'X') return -10
        if (isFull(board)) return 0

        if (isMaximizing) {
            var best = Int.MIN_VALUE
            for ((r, c) in availableMoves(board)) {
                board[r][c] = 'O'
                best = max(best, minimax(board, false))
                board[r][c] = ' '
            }
            return best
        } else {
            var best = Int.MAX_VALUE
            for ((r, c) in availableMoves(board)) {
                board[r][c] = 'X'
                best = min(best, minimax(board, true))
                board[r][c] = ' '
            }
            return best
        }
    }

    private fun availableMoves(board: Array<CharArray>): List<Pair<Int, Int>> =
        buildList {
            for (r in 0..2) for (c in 0..2) if (board[r][c] == ' ') add(r to c)
        }

    private fun isFull(board: Array<CharArray>): Boolean =
        board.all { row -> row.all { it != ' ' } }

    private fun checkWinner(b: Array<CharArray>): Char? {
        val lines = mutableListOf<List<Char>>()
        for (r in 0..2) lines.add(listOf(b[r][0], b[r][1], b[r][2]))
        for (c in 0..2) lines.add(listOf(b[0][c], b[1][c], b[2][c]))
        lines.add(listOf(b[0][0], b[1][1], b[2][2]))
        lines.add(listOf(b[0][2], b[1][1], b[2][0]))

        for (line in lines) {
            if (line.all { it == 'X' }) return 'X'
            if (line.all { it == 'O' }) return 'O'
        }
        return null
    }
}