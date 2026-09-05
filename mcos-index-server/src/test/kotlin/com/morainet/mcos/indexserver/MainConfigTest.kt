package com.morainet.mcos.indexserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CLI argument parsing for the index-server entry point (12-index-server.md §8.1).
 * The token-refuse posture itself calls `exitProcess`, so it is verified by the
 * ops manual + `mcos-server` parity rather than a JVM assertion here.
 */
class MainConfigTest {

    @Test
    fun `defaults match the documented ops values`() {
        val config = parseArgs(emptyArray())
        assertEquals(8877, config.port)
        assertEquals("127.0.0.1", config.bindHost)
        assertEquals("data/index", config.dataDir)
        assertEquals(null, config.keysDir)
        assertEquals(null, config.adminToken)
        assertTrue(!config.help)
    }

    @Test
    fun `all flags parse`() {
        val config = parseArgs(
            arrayOf(
                "--port", "9001",
                "--bind-host", "0.0.0.0",
                "--data-dir", "/var/lib/mcos-index",
                "--keys-dir", "/var/lib/mcos-index/keys",
                "--admin-token", "ops-secret",
            ),
        )
        assertEquals(9001, config.port)
        assertEquals("0.0.0.0", config.bindHost)
        assertEquals("/var/lib/mcos-index", config.dataDir)
        assertEquals("/var/lib/mcos-index/keys", config.keysDir)
        assertEquals("ops-secret", config.adminToken)
    }

    @Test
    fun `help short-circuits`() {
        assertTrue(parseArgs(arrayOf("--help")).help)
        assertTrue(parseArgs(arrayOf("-h")).help)
    }

    @Test
    fun `unknown option is rejected`() {
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--nope")) }
    }

    @Test
    fun `non-numeric port is rejected`() {
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--port", "abc")) }
    }

    @Test
    fun `flag missing its value is rejected`() {
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--admin-token")) }
    }
}
