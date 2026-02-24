package com.kevin.multijugador.server

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

        val json = encode(db)
        saveRawJson(json)
    }


    data class PlayerStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var draws: Int = 0,
        var streak: Int = 0,
        var bestStreak: Int = 0
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

            result[name] = PlayerStats(
                wins = getInt("wins"),
                losses = getInt("losses"),
                draws = getInt("draws"),
                streak = getInt("streak"),
                bestStreak = getInt("bestStreak")
            )
        }

        return result
    }

    private fun encode(db: Map<String, PlayerStats>): String {
        val playersJson = db.entries.joinToString(",") { (name, s) ->
            """"$name":{"wins":${s.wins},"losses":${s.losses},"draws":${s.draws},"streak":${s.streak},"bestStreak":${s.bestStreak}}"""
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