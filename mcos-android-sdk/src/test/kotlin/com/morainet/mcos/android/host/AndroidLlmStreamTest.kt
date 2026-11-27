package com.morainet.mcos.android.host

import com.morainet.mcos.llm.LlmStreamFrame
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLlmStreamTest {
    @Test
    fun receivesFlushedEventAndStopsWithoutWaitingForServerToFinish() = runBlocking {
        val releaseServer = CountDownLatch(1)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = launch(Dispatchers.IO) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) Unit
                val output = socket.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                        "Connection: close\r\n\r\n: ignored\r\ndata: first\r\ndata: second\r\n\r\n")
                        .toByteArray(),
                )
                output.flush()
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            val event = withTimeout(2_000) {
                AndroidLlmHttpTransport().postEvents(
                    "http://127.0.0.1:${server.localPort}/completions", "test", "{}",
                    connectTimeoutMs = 2_000, requestTimeoutMs = 5_000,
                ).first()
            }
            assertEquals(LlmStreamFrame.Data("first\nsecond"), event)
        } finally {
            releaseServer.countDown()
            server.close()
            responder.join()
        }
    }
}
