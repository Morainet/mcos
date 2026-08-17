package com.morainet.mcos.server

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

internal data class Config(
    val port: Int = DEFAULT_PORT,
    val dataDir: String = DEFAULT_DATA_DIR,
    val token: String? = null,
    val help: Boolean = false,
)

private const val DEFAULT_PORT = 8787
private const val DEFAULT_DATA_DIR = "data/blobs"
private const val TOKEN_ENV = "MCOS_SERVER_TOKEN"

private val USAGE = """
    mcos-server — self-hosted blob sync endpoint for MCOS memories.

    Implements the SyncBlobTransport REST contract (PUT/GET/DELETE /blobs/{id})
    with mandatory Bearer-token authentication. Blobs are stored as opaque,
    already-encrypted bytes and are never inspected server-side.

    Usage: mcos-server [options]

      --port <n>      HTTP port to bind (default: $DEFAULT_PORT)
      --data-dir <p>  blob storage directory (default: $DEFAULT_DATA_DIR)
      --token <t>     API token; clients must send Authorization: Bearer <t>
                      (fallback: $TOKEN_ENV environment variable)
      --help          show this help and exit

    The server refuses to start without a token — anonymous access is never
    enabled. Terminate with Ctrl-C / SIGTERM (graceful shutdown hook).
""".trimIndent()

fun main(args: Array<String>) {
    val config = parseArgs(args)
    if (config.help) {
        println(USAGE)
        return
    }
    val token = config.token ?: System.getenv(TOKEN_ENV)
    if (token.isNullOrBlank()) {
        System.err.println("mcos-server: no API token configured. Pass --token or set $TOKEN_ENV.")
        exitProcess(2)
    }

    val store = BlobStore(Path.of(config.dataDir))
    BlobServer(store, token, port = config.port).use { server ->
        println("mcos-server listening on ${server.url} (data-dir: ${config.dataDir}, auth: Bearer token)")
        val latch = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread(latch::countDown))
        latch.await()
        println("mcos-server shutting down")
    }
}

internal fun parseArgs(args: Array<String>): Config {
    var config = Config()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--help", "-h" -> config = config.copy(help = true)
            "--port" -> {
                val value = args.getOrNull(++i) ?: throw IllegalArgumentException("--port requires a value")
                config = config.copy(port = value.toIntOrNull() ?: throw IllegalArgumentException("--port must be a number"))
            }
            "--data-dir" -> {
                val value = args.getOrNull(++i) ?: throw IllegalArgumentException("--data-dir requires a value")
                config = config.copy(dataDir = value)
            }
            "--token" -> {
                val value = args.getOrNull(++i) ?: throw IllegalArgumentException("--token requires a value")
                config = config.copy(token = value)
            }
            else -> throw IllegalArgumentException("unknown option: ${args[i]}")
        }
        i++
    }
    return config
}
