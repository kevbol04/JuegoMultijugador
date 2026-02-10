package com.kevin.multijugador.server

import com.kevin.multijugador.util.ServerConfig
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class TcpServer(
    private val config: ServerConfig,
    private val recordsStore: RecordsStore
) {
    private val clientCount = AtomicInteger(0)

    suspend fun start() = coroutineScope {
        val serverSocket = ServerSocket(config.port)
        println("Servidor iniciado en ${config.host}:${config.port} (maxClients=${config.maxClients})")

        while (true) {
            val socket = withContext(Dispatchers.IO) { serverSocket.accept() }

            val current = clientCount.incrementAndGet()
            if (current > config.maxClients) {
                clientCount.decrementAndGet()
                socket.close()
                println("Cliente rechazado (max.clients alcanzado)")
                continue
            }

            launch(Dispatchers.IO) {
                try {
                    ClientHandler(socket, recordsStore).run()
                } finally {
                    clientCount.decrementAndGet()
                }
            }
        }
    }
}