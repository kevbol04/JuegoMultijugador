package com.kevin.multijugador.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ConsoleInput {

    private val q = LinkedBlockingQueue<String>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                val line = readLine() ?: break
                q.offer(line.trim())
            }
        }
    }

    fun flush() {
        while (q.poll() != null) { /* vaciar */ }
    }

    fun takeNonBlank(session: ClientSession): String {
        while (true) {
            val v = q.take()

            val pending = session.deferredTimeoutMsg.getAndSet(null)
            if (pending != null && session.clientState.get() == ClientSession.ClientState.IN_GAME) {
                println("\n$pending\n")
            }

            val s = v.trim()
            if (s.isNotBlank()) return s
        }
    }

    fun pollNonBlankWhile(cond: () -> Boolean, pollMs: Long = 150): String? {
        while (cond()) {
            val s = q.poll(pollMs, TimeUnit.MILLISECONDS)
            if (s != null) {
                val t = s.trim()
                if (t.isNotBlank()) return t
            }
        }
        return null
    }
}