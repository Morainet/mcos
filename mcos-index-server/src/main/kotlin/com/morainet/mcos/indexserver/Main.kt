package com.morainet.mcos.indexserver

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * CLI entry point for the public index server (12-index-server.md §8.1).
 *
 * Reuses the `mcos-server` operational posture: a blank admin token refuses to
 * start (anonymous management is never enabled), and the process runs until
 * SIGTERM/Ctrl-C via a shutdown hook. TLS is expected to terminate at a reverse
 * proxy — the Bearer admin token protects the API, the proxy protects the token
 * in transit (§8.1).
 */
internal data class Config(
    val port: Int = DEFAULT_PORT,
    val bindHost: String = DEFAULT_BIND_HOST,
    val dataDir: String = DEFAULT_DATA_DIR,
    val keysDir: String? = null,
    val adminToken: String? = null,
    val help: Boolean = false,
)

private const val DEFAULT_PORT = 8877
private const val DEFAULT_BIND_HOST = "127.0.0.1"
private const val DEFAULT_DATA_DIR = "data/index"
private const val ADMIN_TOKEN_ENV = "MCOS_INDEX_ADMIN_TOKEN"
private const val AV_SCANNER_ENV = "MCOS_AV_SCANNER_CMD"

private val USAGE = """
    mcos-index-server — public marketplace index + publisher review pipeline.

    Serves the discovery index, publisher submission/review flow, and the
    signed blocklist that every device polls (12-index-server.md §5). Zero
    third-party runtime dependencies (com.sun.net.httpserver + kotlinx.serialization).

    Usage: mcos-index-server [options]

      --port <n>          HTTP port to bind (default: $DEFAULT_PORT)
      --bind-host <h>     bind address (default: $DEFAULT_BIND_HOST;
                          use 0.0.0.0 only behind a reverse proxy)
      --data-dir <p>      registry persistence directory (default: $DEFAULT_DATA_DIR)
      --keys-dir <p>      seed dir for operator PEMs (default: <data-dir>/keys)
      --admin-token <t>   operator token (fallback: $ADMIN_TOKEN_ENV)
      --help              show this help and exit

    The server refuses to start without an admin token — anonymous management
    is never enabled. TLS terminates at a reverse proxy (§8.1).

    AV scanning: set $AV_SCANNER_ENV to a command that reads the artifact path
    on stdin and prints CLEAN/MALICIOUS (§8.3). With no scanner and no
    <data-dir>/av-denylist.txt hits, gate 9 reports UNSCANNED → human review.

    Terminate with Ctrl-C / SIGTERM (graceful shutdown hook).
""".trimIndent()

fun main(args: Array<String>) {
    val config = parseArgs(args)
    if (config.help) {
        println(USAGE)
        return
    }

    val adminToken = config.adminToken ?: System.getenv(ADMIN_TOKEN_ENV)
    if (adminToken.isNullOrBlank()) {
        System.err.println(
            "mcos-index-server: no admin token configured. Pass --admin-token or set $ADMIN_TOKEN_ENV.",
        )
        exitProcess(2)
    }

    val dataDir = Path.of(config.dataDir)
    val keysDir = config.keysDir?.let { Path.of(it) } ?: dataDir.resolve("keys")
    val (operatorPrivatePem, operatorPublicPem) = resolveOperatorKeys(keysDir)
    val avDenylistFile = dataDir.resolve("av-denylist.txt")
    val avScannerCommand = System.getenv(AV_SCANNER_ENV)?.takeIf { it.isNotBlank() }

    val server = IndexServer(
        dataDir = dataDir,
        adminToken = adminToken,
        operatorPrivatePem = operatorPrivatePem,
        operatorPublicPem = operatorPublicPem,
        port = config.port,
        bindHost = config.bindHost,
        avDenylistFile = avDenylistFile,
        avScannerCommand = avScannerCommand,
    )
    val boundPort = server.start()

    val signing = if (operatorPrivatePem == null) {
        "no operator key (keys-dir: $keysDir) — /v1/blocklist signing disabled until a key is seeded"
    } else {
        "operator key loaded from $keysDir"
    }
    val av = when {
        avScannerCommand != null -> "external scanner ($AV_SCANNER_ENV)"
        Files.exists(avDenylistFile) -> "sha256 denylist ($avDenylistFile)"
        else -> "none — gate 9 reports UNSCANNED → human review"
    }
    println("mcos-index-server listening on http://${config.bindHost}:$boundPort")
    println("  data-dir: $dataDir")
    println("  auth: Bearer admin token")
    println("  $signing")
    println("  av: $av")

    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            latch.countDown()
        },
    )
    latch.await()
    println("mcos-index-server shutting down")
}

/**
 * Locates the operator PEMs under [keysDir]. The private half is optional: a
 * read-only mirror or a fresh deployment awaiting key seeding starts without
 * one (blocklist signing then fails closed rather than silently). Returns
 * (private, public) — either may be null.
 */
private fun resolveOperatorKeys(keysDir: Path): Pair<Path?, Path?> {
    if (!Files.isDirectory(keysDir)) return null to null
    val privatePem = firstExisting(keysDir, "operator-private.pem", "operator.pem")
    val publicPem = firstExisting(keysDir, "operator-public.pem", "operator.pub.pem")
    return privatePem to publicPem
}

private fun firstExisting(dir: Path, vararg names: String): Path? =
    names.map { dir.resolve(it) }.firstOrNull { Files.isRegularFile(it) }

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
            "--bind-host" -> {
                val value = args.getOrNull(++i) ?: throw IllegalArgumentException("--bind-host requires a value")
                config = config.copy(bindHost = value)
            }
            "--data-dir" -> {
                val value = args.getOrNull(++i) ?: throw IllegalArgumentException("--data-dir requires a value")
                config = config.copy(dataDir = value)
            }
            "--keys-dir" -> {
                val value = args.getOrNull(++i) ?: throw IllegalArgumentException("--keys-dir requires a value")
                config = config.copy(keysDir = value)
            }
            "--admin-token" -> {
                val value = args.getOrNull(++i) ?: throw IllegalArgumentException("--admin-token requires a value")
                config = config.copy(adminToken = value)
            }
            else -> throw IllegalArgumentException("unknown option: ${args[i]}")
        }
        i++
    }
    return config
}
