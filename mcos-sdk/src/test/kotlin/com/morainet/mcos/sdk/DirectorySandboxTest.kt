package com.morainet.mcos.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DirectorySandbox unit tests (04-plugin-sdk.md 6.1) — the SDK module's
 * first test class. One implementation serves the JVM runtime and the
 * Android host, so everything security-relevant is asserted here.
 */
class DirectorySandboxTest {

    private fun newRoot(): Path = Files.createTempDirectory("mcos-sandbox-test-")

    private fun invalidPath(): (String) -> Unit = { path ->
        val e = assertFailsWith<McosException> {
            runBlocking<Unit> { DirectorySandbox(newRoot()).write(path, byteArrayOf(1)) }
        }
        assertEquals("SCHEMA_VIOLATION", e.code)
        assertEquals("sandbox_path_invalid", e.details["reason"]?.let { (it as JsonPrimitive).content })
    }

    // ─── Round-trips ───────────────────────────────────────────────

    @Test
    fun `DS1-write then read round-trips bytes`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        sandbox.write("notes/data.bin", byteArrayOf(1, 2, 3, 4))

        assertContentEquals(byteArrayOf(1, 2, 3, 4), sandbox.read("notes/data.bin"))
    }

    @Test
    fun `DS2-write creates parent directories`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        sandbox.write("a/b/c.txt", "hi".toByteArray())

        assertNotNull(sandbox.stat("a"))
        assertNotNull(sandbox.stat("a/b"))
        assertNotNull(sandbox.stat("a/b/c.txt"))
    }

    @Test
    fun `DS3-append concatenates and overwrite replaces`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        sandbox.write("log.txt", "first\n".toByteArray())
        sandbox.write("log.txt", "second\n".toByteArray(), append = true)
        assertEquals("first\nsecond\n", sandbox.read("log.txt")!!.decodeToString())

        sandbox.write("log.txt", "replaced".toByteArray())
        assertEquals("replaced", sandbox.read("log.txt")!!.decodeToString())
    }

    @Test
    fun `DS4-read of an absent path returns null`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        assertNull(sandbox.read("missing.txt"))
        assertNull(sandbox.read("missing/dir/file.txt"))
    }

    // ─── stat / list / delete ──────────────────────────────────────

    @Test
    fun `DS5-stat reports file size and directory flag`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        sandbox.write("f.txt", byteArrayOf(1, 2, 3))
        sandbox.write("d/inner.txt", byteArrayOf(1))

        val file = sandbox.stat("f.txt")!!
        assertEquals("f.txt", file.path)
        assertFalse(file.isDir)
        assertEquals(3L, file.size)

        val dir = sandbox.stat("d")!!
        assertTrue(dir.isDir)
        assertNull(dir.size)

        assertNull(sandbox.stat("nope.txt"))
    }

    @Test
    fun `DS6-list is non-recursive and sorted, absent dir lists empty`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        sandbox.write("dir/b.txt", byteArrayOf(1))
        sandbox.write("dir/a.txt", byteArrayOf(2))
        sandbox.write("dir/sub/c.txt", byteArrayOf(3))

        val names = sandbox.list("dir").map { it.path }
        assertEquals(listOf("dir/a.txt", "dir/b.txt", "dir/sub"), names)

        assertTrue(sandbox.list("missing").isEmpty())
    }

    @Test
    fun `DS7-delete is idempotent and removes empty directories`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        sandbox.write("f.txt", byteArrayOf(1))
        sandbox.write("d/inner.txt", byteArrayOf(1))

        assertTrue(sandbox.delete("f.txt"))
        assertFalse(sandbox.delete("f.txt"))
        assertTrue(sandbox.delete("d/inner.txt"))
        assertTrue(sandbox.delete("d"))
        assertFalse(sandbox.delete("d"))
    }

    @Test
    fun `DS8-delete of a non-empty directory is CONFLICT directory_not_empty`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        sandbox.write("d/keep.txt", byteArrayOf(1))

        val e = assertFailsWith<McosException> { sandbox.delete("d") }
        assertEquals("CONFLICT", e.code)
        assertEquals("directory_not_empty", e.details["reason"]?.let { (it as JsonPrimitive).content })
        assertNotNull(sandbox.stat("d/keep.txt"))
    }

    @Test
    fun `DS9-tempFile is unique, inside the root, and honors prefix-suffix`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        val one = sandbox.tempFile(prefix = "shot", suffix = ".bin")
        val two = sandbox.tempFile(prefix = "shot", suffix = ".bin")

        assertTrue(one != two)
        assertTrue(one.startsWith("shot"), one)
        assertTrue(one.endsWith(".bin"), one)
        assertNotNull(sandbox.stat(one))
    }

    // ─── Path safety ───────────────────────────────────────────────

    @Test
    fun `DS10-traversal and malformed paths are SCHEMA_VIOLATION sandbox_path_invalid`() {
        listOf(
            "../escape.txt",
            "a/../../escape.txt",
            "/absolute.txt",
            "",
            " ",
            "a\\b.txt",
            "a\u0000b.txt",
            "a/./b.txt",
            ".",
            "..",
            "a//b.txt",
            "a/",
        ).forEach(invalidPath())
    }

    @Test
    fun `DS11-a symlink inside the sandbox is PERMISSION_DENIED sandbox_escape`() = runBlocking<Unit> {
        val outside = Files.createTempDirectory("mcos-outside-")
        val outsideFile = outside.resolve("secret.txt")
        Files.write(outsideFile, "outside".toByteArray())

        val root = newRoot()
        val link = root.resolve("pivot.txt")
        val created = runCatching {
            Files.createSymbolicLink(link, outsideFile)
            Files.isSymbolicLink(link)
        }.getOrDefault(false)
        if (!created) {
            // Platform without symlink creation support — nothing to defend
            // against here; the no-symlink walk is still covered by DS1-DS10.
            println("DS11 skipped: symlinks cannot be created on this platform")
            return@runBlocking
        }

        val sandbox = DirectorySandbox(root)
        // assertFailsWith is inline, so suspend calls are legal in its block
        // from this suspend context.
        suspend fun expectEscape(op: suspend () -> Unit) {
            val e = assertFailsWith<McosException> { op() }
            assertEquals("PERMISSION_DENIED", e.code)
            assertEquals("sandbox_escape", e.details["reason"]?.let { (it as JsonPrimitive).content })
        }
        expectEscape { sandbox.read("pivot.txt") }
        expectEscape { sandbox.write("pivot.txt", byteArrayOf(1)) }
        expectEscape { sandbox.stat("pivot.txt") }
        expectEscape { sandbox.delete("pivot.txt") }
        // The outside file is untouched.
        assertEquals("outside", outsideFile.toFile().readText())
    }

    @Test
    fun `DS12-reads on a never-written sandbox behave as empty`() = runBlocking<Unit> {
        val sandbox = DirectorySandbox(newRoot())
        assertNull(sandbox.read("anything.txt"))
        assertNull(sandbox.stat("anything.txt"))
        assertTrue(sandbox.list("").isEmpty())
        assertFalse(sandbox.delete("anything.txt"))
    }
}
