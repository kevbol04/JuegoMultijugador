package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.Difficulty
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RecordsStore(
    private val filePath: Path = Path.of("src/main/resources/records.json")
) {
    private val lock = ReentrantLock()

    fun loadRawJson(): String = lock.withLock {
        ensureFile()
        return Files.readString(filePath)
            .replace("\r", "")
            .replace("\n", "")
            .trim()
    }

    fun saveRawJson(json: String) = lock.withLock {
        val pretty = prettyPrintJson(json)
        Files.writeString(filePath, pretty)
    }

    private fun prettyPrintJson(json: String): String {
        val indent = "  "
        val sb = StringBuilder()
        var level = 0
        var inQuotes = false

        for (char in json) {
            when (char) {
                '"' -> {
                    sb.append(char)
                    inQuotes = !inQuotes
                }
                '{', '[' -> {
                    sb.append(char)
                    if (!inQuotes) {
                        sb.append("\n")
                        level++
                        sb.append(indent.repeat(level))
                    }
                }
                '}', ']' -> {
                    if (!inQuotes) {
                        sb.append("\n")
                        level--
                        sb.append(indent.repeat(level))
                    }
                    sb.append(char)
                }
                ',' -> {
                    sb.append(char)
                    if (!inQuotes) {
                        sb.append("\n")
                        sb.append(indent.repeat(level))
                    }
                }
                else -> sb.append(char)
            }
        }

        return sb.toString()
    }

    enum class Outcome { WIN, LOSS, DRAW }

    fun updateResult(username: String, outcome: Outcome) = lock.withLock {
        ensureFile()
        val raw = Files.readString(filePath)
        val db = parse(raw)
        val stats = db.getOrPut(username) { PlayerStats() }

        applyOutcomeOverall(stats, outcome)

        val json = encode(db)
        saveRawJson(json)
    }

    fun updateSeries(
        username: String,
        mode: GameMode,
        outcome: Outcome,
        boardSize: Int,
        difficulty: Difficulty?,
        moveTimeMs: Long,
        moveCount: Int,
        cellCounts: IntArray
    ) = lock.withLock {
        ensureFile()
        val raw = Files.readString(filePath)
        val db = parse(raw)
        val stats = db.getOrPut(username) { PlayerStats() }

        applyOutcomeOverall(stats, outcome)

        when (mode) {
            GameMode.PVP -> applyOutcomePvp(stats, outcome)
            GameMode.PVE -> applyOutcomePve(stats, outcome)
        }

        if (outcome == Outcome.WIN) {
            when (boardSize.coerceIn(3, 5)) {
                3 -> if (mode == GameMode.PVP) stats.pvpWins3++ else stats.pveWins3++
                4 -> if (mode == GameMode.PVP) stats.pvpWins4++ else stats.pveWins4++
                5 -> if (mode == GameMode.PVP) stats.pvpWins5++ else stats.pveWins5++
            }
        }

        if (mode == GameMode.PVE) {
            when (difficulty ?: Difficulty.EASY) {
                Difficulty.EASY -> {
                    stats.pveEasyPlayed++
                    if (outcome == Outcome.WIN) stats.pveEasyWins++
                }
                Difficulty.MEDIUM -> {
                    stats.pveMediumPlayed++
                    if (outcome == Outcome.WIN) stats.pveMediumWins++
                }
                Difficulty.HARD -> {
                    stats.pveHardPlayed++
                    if (outcome == Outcome.WIN) stats.pveHardWins++
                }
            }
        }

        if (moveCount > 0 && moveTimeMs > 0) {
            stats.totalMoveTimeMs += moveTimeMs
            stats.totalMoves += moveCount
        }

        addCellCounts(stats, cellCounts)

        val json = encode(db)
        saveRawJson(json)
    }

    private fun applyOutcomeOverall(stats: PlayerStats, outcome: Outcome) {
        when (outcome) {
            Outcome.WIN -> {
                stats.wins += 1
                stats.streak += 1
                if (stats.streak > stats.bestStreak) stats.bestStreak = stats.streak
            }
            Outcome.LOSS -> {
                stats.losses += 1
                stats.streak = 0
            }
            Outcome.DRAW -> {
                stats.draws += 1
                stats.streak = 0
            }
        }
    }

    private fun applyOutcomePvp(stats: PlayerStats, outcome: Outcome) {
        when (outcome) {
            Outcome.WIN -> {
                stats.pvpWins += 1
                stats.pvpStreak += 1
                if (stats.pvpStreak > stats.pvpBestStreak) stats.pvpBestStreak = stats.pvpStreak
            }
            Outcome.LOSS -> {
                stats.pvpLosses += 1
                stats.pvpStreak = 0
            }
            Outcome.DRAW -> {
                stats.pvpDraws += 1
                stats.pvpStreak = 0
            }
        }
    }

    private fun applyOutcomePve(stats: PlayerStats, outcome: Outcome) {
        when (outcome) {
            Outcome.WIN -> {
                stats.pveWins += 1
                stats.pveStreak += 1
                if (stats.pveStreak > stats.pveBestStreak) stats.pveBestStreak = stats.pveStreak
            }
            Outcome.LOSS -> {
                stats.pveLosses += 1
                stats.pveStreak = 0
            }
            Outcome.DRAW -> {
                stats.pveDraws += 1
                stats.pveStreak = 0
            }
        }
    }

    private fun addCellCounts(stats: PlayerStats, cellCounts: IntArray) {
        fun safe(i: Int): Int = if (i in cellCounts.indices) cellCounts[i] else 0

        stats.m00 += safe(0);  stats.m01 += safe(1);  stats.m02 += safe(2);  stats.m03 += safe(3);  stats.m04 += safe(4)
        stats.m10 += safe(5);  stats.m11 += safe(6);  stats.m12 += safe(7);  stats.m13 += safe(8);  stats.m14 += safe(9)
        stats.m20 += safe(10); stats.m21 += safe(11); stats.m22 += safe(12); stats.m23 += safe(13); stats.m24 += safe(14)
        stats.m30 += safe(15); stats.m31 += safe(16); stats.m32 += safe(17); stats.m33 += safe(18); stats.m34 += safe(19)
        stats.m40 += safe(20); stats.m41 += safe(21); stats.m42 += safe(22); stats.m43 += safe(23); stats.m44 += safe(24)
    }

    data class PlayerStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var draws: Int = 0,
        var streak: Int = 0,
        var bestStreak: Int = 0,

        var pvpWins: Int = 0,
        var pvpLosses: Int = 0,
        var pvpDraws: Int = 0,
        var pvpStreak: Int = 0,
        var pvpBestStreak: Int = 0,

        var pveWins: Int = 0,
        var pveLosses: Int = 0,
        var pveDraws: Int = 0,
        var pveStreak: Int = 0,
        var pveBestStreak: Int = 0,

        var pvpWins3: Int = 0,
        var pvpWins4: Int = 0,
        var pvpWins5: Int = 0,
        var pveWins3: Int = 0,
        var pveWins4: Int = 0,
        var pveWins5: Int = 0,

        var pveEasyPlayed: Int = 0,
        var pveEasyWins: Int = 0,
        var pveMediumPlayed: Int = 0,
        var pveMediumWins: Int = 0,
        var pveHardPlayed: Int = 0,
        var pveHardWins: Int = 0,

        var totalMoveTimeMs: Long = 0L,
        var totalMoves: Int = 0,

        var m00: Int = 0, var m01: Int = 0, var m02: Int = 0, var m03: Int = 0, var m04: Int = 0,
        var m10: Int = 0, var m11: Int = 0, var m12: Int = 0, var m13: Int = 0, var m14: Int = 0,
        var m20: Int = 0, var m21: Int = 0, var m22: Int = 0, var m23: Int = 0, var m24: Int = 0,
        var m30: Int = 0, var m31: Int = 0, var m32: Int = 0, var m33: Int = 0, var m34: Int = 0,
        var m40: Int = 0, var m41: Int = 0, var m42: Int = 0, var m43: Int = 0, var m44: Int = 0
    )

    private fun ensureFile() {
        if (!Files.exists(filePath)) {
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, """{"players":{}}""")
        }
    }

    private fun parse(json: String): MutableMap<String, PlayerStats> {
        val playersStart = json.indexOf("\"players\"")
        if (playersStart == -1) return mutableMapOf()

        val braceStart = json.indexOf('{', playersStart)
        if (braceStart == -1) return mutableMapOf()

        val braceEnd = findMatchingBrace(json, braceStart)
        if (braceEnd == -1) return mutableMapOf()

        val playersObj = json.substring(braceStart + 1, braceEnd).trim()
        if (playersObj.isBlank()) return mutableMapOf()

        val result = mutableMapOf<String, PlayerStats>()

        val entryRegex = """"([^"]+)"\s*:\s*\{([^}]*)}""".toRegex()
        for (m in entryRegex.findAll(playersObj)) {
            val name = m.groupValues[1]
            val body = m.groupValues[2]

            fun getInt(field: String): Int {
                val r = """"$field"\s*:\s*(\d+)""".toRegex()
                return r.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }

            fun getLong(field: String): Long {
                val r = """"$field"\s*:\s*(\d+)""".toRegex()
                return r.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            }

            result[name] = PlayerStats(
                wins = getInt("wins"),
                losses = getInt("losses"),
                draws = getInt("draws"),
                streak = getInt("streak"),
                bestStreak = getInt("bestStreak"),

                pvpWins = getInt("pvpWins"),
                pvpLosses = getInt("pvpLosses"),
                pvpDraws = getInt("pvpDraws"),
                pvpStreak = getInt("pvpStreak"),
                pvpBestStreak = getInt("pvpBestStreak"),

                pveWins = getInt("pveWins"),
                pveLosses = getInt("pveLosses"),
                pveDraws = getInt("pveDraws"),
                pveStreak = getInt("pveStreak"),
                pveBestStreak = getInt("pveBestStreak"),

                pvpWins3 = getInt("pvpWins3"),
                pvpWins4 = getInt("pvpWins4"),
                pvpWins5 = getInt("pvpWins5"),
                pveWins3 = getInt("pveWins3"),
                pveWins4 = getInt("pveWins4"),
                pveWins5 = getInt("pveWins5"),

                pveEasyPlayed = getInt("pveEasyPlayed"),
                pveEasyWins = getInt("pveEasyWins"),
                pveMediumPlayed = getInt("pveMediumPlayed"),
                pveMediumWins = getInt("pveMediumWins"),
                pveHardPlayed = getInt("pveHardPlayed"),
                pveHardWins = getInt("pveHardWins"),

                totalMoveTimeMs = getLong("totalMoveTimeMs"),
                totalMoves = getInt("totalMoves"),

                m00 = getInt("m00"), m01 = getInt("m01"), m02 = getInt("m02"), m03 = getInt("m03"), m04 = getInt("m04"),
                m10 = getInt("m10"), m11 = getInt("m11"), m12 = getInt("m12"), m13 = getInt("m13"), m14 = getInt("m14"),
                m20 = getInt("m20"), m21 = getInt("m21"), m22 = getInt("m22"), m23 = getInt("m23"), m24 = getInt("m24"),
                m30 = getInt("m30"), m31 = getInt("m31"), m32 = getInt("m32"), m33 = getInt("m33"), m34 = getInt("m34"),
                m40 = getInt("m40"), m41 = getInt("m41"), m42 = getInt("m42"), m43 = getInt("m43"), m44 = getInt("m44")
            )
        }

        return result
    }

    private fun encode(db: Map<String, PlayerStats>): String {
        val playersJson = db.entries.joinToString(",") { (name, s) ->
            """"$name":{""" +
                    """"wins":${s.wins},"losses":${s.losses},"draws":${s.draws},"streak":${s.streak},"bestStreak":${s.bestStreak},""" +
                    """"pvpWins":${s.pvpWins},"pvpLosses":${s.pvpLosses},"pvpDraws":${s.pvpDraws},"pvpStreak":${s.pvpStreak},"pvpBestStreak":${s.pvpBestStreak},""" +
                    """"pveWins":${s.pveWins},"pveLosses":${s.pveLosses},"pveDraws":${s.pveDraws},"pveStreak":${s.pveStreak},"pveBestStreak":${s.pveBestStreak},""" +
                    """"pvpWins3":${s.pvpWins3},"pvpWins4":${s.pvpWins4},"pvpWins5":${s.pvpWins5},"pveWins3":${s.pveWins3},"pveWins4":${s.pveWins4},"pveWins5":${s.pveWins5},""" +
                    """"pveEasyPlayed":${s.pveEasyPlayed},"pveEasyWins":${s.pveEasyWins},"pveMediumPlayed":${s.pveMediumPlayed},"pveMediumWins":${s.pveMediumWins},"pveHardPlayed":${s.pveHardPlayed},"pveHardWins":${s.pveHardWins},""" +
                    """"totalMoveTimeMs":${s.totalMoveTimeMs},"totalMoves":${s.totalMoves},""" +
                    """"m00":${s.m00},"m01":${s.m01},"m02":${s.m02},"m03":${s.m03},"m04":${s.m04},""" +
                    """"m10":${s.m10},"m11":${s.m11},"m12":${s.m12},"m13":${s.m13},"m14":${s.m14},""" +
                    """"m20":${s.m20},"m21":${s.m21},"m22":${s.m22},"m23":${s.m23},"m24":${s.m24},""" +
                    """"m30":${s.m30},"m31":${s.m31},"m32":${s.m32},"m33":${s.m33},"m34":${s.m34},""" +
                    """"m40":${s.m40},"m41":${s.m41},"m42":${s.m42},"m43":${s.m43},"m44":${s.m44}""" +
                    """}"""
        }
        return """{"players":{$playersJson}}"""
    }

    private fun findMatchingBrace(s: String, openIdx: Int): Int {
        var depth = 0
        for (i in openIdx until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}