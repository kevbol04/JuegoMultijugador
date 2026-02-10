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
        if (!Files.exists(filePath)) {
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, """{"players":{}}""")
        }
        return Files.readString(filePath)
    }

    fun saveRawJson(json: String) = lock.withLock {
        Files.writeString(filePath, json)
    }
}
