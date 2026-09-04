package com.morainet.mcos.android

import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.runtime.core.plugin.McosPackage
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.SideEffectClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dynamic `.mcos` loading — the pure-JVM parts. [McosPackage] manifest parsing
 * and [MarketplacePluginFactory] dispatch are fully covered here; the Android
 * [DexPluginLoader] (DexClassLoader) is a device-only concern, stubbed by a
 * fake [DynamicPluginLoader].
 */
class DynamicPluginLoadingTest {

    // ── McosPackage manifest parsing ────────────────────────────────────

    @Test
    fun readManifestParsesIdEntryVersion() {
        val info = McosPackage.readManifest(mcosZip("acme.thing", "com.acme.Thing", "2.1.0"))
        assertEquals("acme.thing", info.id)
        assertEquals("com.acme.Thing", info.entry)
        assertEquals("2.1.0", info.version)
    }

    @Test
    fun readManifestRejectsMissingManifest() {
        val noManifest = mcosZip("x", "y", includeManifest = false)
        val e = assertThrows(McosPackage.FormatException::class.java) {
            McosPackage.readManifest(noManifest)
        }
        assertTrue(e.message!!.contains("plugin.json missing"))
    }

    @Test
    fun readManifestRejectsMalformedJson() {
        assertThrows(McosPackage.FormatException::class.java) {
            McosPackage.readManifest(mcosZip(id = "x", entry = "y", manifestJson = "{not json"))
        }
    }

    @Test
    fun readManifestRejectsMissingEntry() {
        val e = assertThrows(McosPackage.FormatException::class.java) {
            McosPackage.readManifest(mcosZip(id = "x", entry = "y", manifestJson = """{"id":"x","version":"1.0.0"}"""))
        }
        assertTrue(e.message!!.contains("'entry'"))
    }

    @Test
    fun readManifestRejectsNonZip() {
        assertThrows(McosPackage.FormatException::class.java) {
            McosPackage.readManifest("not a zip at all".toByteArray())
        }
    }

    // ── factory dispatch ────────────────────────────────────────────────

    @Test
    fun curatedIdResolvesLocallyIgnoringBytes() {
        val factory = MarketplacePluginFactory()
        val f = factory.factoryFor("example.hello")
        assertNotNull(f)
        assertTrue(f!!(ByteArray(0)) is HelloPlugin)
    }

    @Test
    fun unknownIdWithoutDynamicLoaderFailsFast() {
        val factory = MarketplacePluginFactory()
        assertFalse(factory.supports("acme.thing"))
        assertNull(factory.factoryFor("acme.thing"))
    }

    @Test
    fun dynamicLoaderInstantiatesFromManifestEntry() {
        val fake = FakeDynamicLoader()
        val factory = MarketplacePluginFactory(dynamicLoader = fake)
        assertTrue(factory.supports("acme.thing"))

        val bytes = mcosZip("acme.thing", "com.acme.Thing", "1.0.0")
        val plugin = factory.factoryFor("acme.thing")!!(bytes)

        assertTrue(plugin is HelloPlugin) // the fake returns HelloPlugin
        assertEquals("acme.thing", fake.lastPackageId)
        assertEquals("com.acme.Thing", fake.lastEntry)
        assertArrayEquals(bytes, fake.lastArtifact)
    }

    @Test
    fun dynamicLoaderRejectsManifestIdMismatch() {
        val fake = FakeDynamicLoader()
        val factory = MarketplacePluginFactory(dynamicLoader = fake)
        // Manifest declares a different id than the requested package id.
        val bytes = mcosZip("evil.other", "com.acme.Thing")
        val e = assertThrows(IllegalArgumentException::class.java) {
            factory.factoryFor("acme.thing")!!(bytes)
        }
        assertTrue(e.message!!.contains("does not match"))
        assertNull("loader must not run on an id mismatch", fake.lastEntry)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private class FakeDynamicLoader : DynamicPluginLoader {
        var lastPackageId: String? = null
        var lastEntry: String? = null
        var lastArtifact: ByteArray? = null
        override fun load(packageId: String, artifact: ByteArray, entryClass: String): McosPlugin {
            lastPackageId = packageId
            lastEntry = entryClass
            lastArtifact = artifact
            return HelloPlugin()
        }
    }

    // ── readPluginManifest — the full-schema decode (item 45) ──────────

    private val fullManifestJson = """
        {
          "id": "acme.wire",
          "entry": "com.acme.Wire",
          "version": "2.3.0",
          "name": "Acme Wire",
          "minRuntimeVersion": "0.9.0",
          "description": "wire things",
          "provider": {"name": "Acme", "url": "https://acme.example"},
          "permissions": [{"type": "mcos", "name": "sandbox", "reason": "cache"}],
          "namespaces": ["acme"],
          "eventsEmitted": ["acme.wire.*"],
          "tags": ["network"],
          "unknownTopLevel": {"ignored": true},
          "commands": [
            {
              "id": "acme.wire.fetch",
              "version": "1.2.0",
              "title": "Fetch",
              "description": "fetch a resource",
              "sideEffectClass": "network",
              "idempotent": true,
              "timeoutMs": 45000,
              "permissions": [{"type": "mcos", "name": "network.api.acme.example"}],
              "aliases": ["acme.wire.get"],
              "examples": ["acme.wire.fetch url=https://acme.example"],
              "inputSchema": {"type": "object", "required": ["url"]},
              "outputSchema": {"type": "string"},
              "unknownCommandField": 7
            },
            {
              "id": "acme.wire.purge",
              "title": "Purge",
              "sideEffectClass": "DESTRUCTIVE"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readPluginManifestDecodesTheFullSchema() {
        val m = McosPackage.readPluginManifest(mcosZip("acme.wire", "com.acme.Wire", manifestJson = fullManifestJson))

        assertEquals("acme.wire", m.id)
        assertEquals("com.acme.Wire", m.entry)
        assertEquals("2.3.0", m.version)
        assertEquals("Acme Wire", m.name)
        assertEquals("0.9.0", m.minRuntimeVersion)
        assertEquals("wire things", m.description)
        assertEquals("Acme", m.provider.name)
        assertEquals("https://acme.example", m.provider.url)
        assertEquals(listOf("sandbox"), m.permissions.map { it.name })
        assertEquals(listOf("acme"), m.namespaces)
        assertEquals(listOf("acme.wire.*"), m.eventsEmitted)
        assertEquals(listOf("network"), m.tags)
        assertEquals(2, m.commands.size)

        val fetch = m.commands[0]
        assertEquals("acme.wire.fetch", fetch.id)
        assertEquals("1.2.0", fetch.version)
        assertEquals("Fetch", fetch.title)
        assertEquals(SideEffectClass.network, fetch.sideEffectClass)
        assertTrue(fetch.idempotent)
        assertEquals(45_000L, fetch.timeoutMs)
        assertEquals(listOf("network.api.acme.example"), fetch.permissions.map { it.name })
        assertEquals(listOf("acme.wire.get"), fetch.aliases)
        assertEquals(listOf("acme.wire.fetch url=https://acme.example"), fetch.examples)
        assertEquals(setOf("type", "required"), fetch.inputSchema.keys)
        assertEquals(setOf("type"), fetch.outputSchema!!.keys)

        // side-effect names are case-insensitive
        assertEquals(SideEffectClass.destructive, m.commands[1].sideEffectClass)
    }

    @Test
    fun legacyManifestDecodesWithEmptyCommands() {
        val m = McosPackage.readPluginManifest(mcosZip("acme.thing", "com.acme.Thing", "2.1.0"))

        assertEquals("acme.thing", m.id)
        assertEquals("com.acme.Thing", m.entry)
        assertEquals("2.1.0", m.version)
        assertEquals("name defaults to the id", "acme.thing", m.name)
        assertTrue("pre-schema packages cannot register manifest-only", m.commands.isEmpty())
    }

    @Test
    fun readPluginManifestRejectsUnknownSideEffectClass() {
        val e = assertThrows(McosPackage.FormatException::class.java) {
            McosPackage.readPluginManifest(
                mcosZip(
                    "acme.wire", "com.acme.Wire",
                    manifestJson = """{"id":"acme.wire","entry":"com.acme.Wire","commands":[{"id":"x.y","sideEffectClass":"magnetic"}]}""",
                ),
            )
        }
        assertTrue(e.message!!.contains("sideEffectClass 'magnetic'"))
    }

    @Test
    fun readPluginManifestRejectsCommandWithoutId() {
        val e = assertThrows(McosPackage.FormatException::class.java) {
            McosPackage.readPluginManifest(
                mcosZip(
                    "acme.wire", "com.acme.Wire",
                    manifestJson = """{"id":"acme.wire","entry":"com.acme.Wire","commands":[{"title":"no id"}]}""",
                ),
            )
        }
        assertTrue(e.message!!.contains("missing required 'id'"))
    }

    @Test
    fun readManifestDelegatesToTheFullDecode() {
        val info = McosPackage.readManifest(mcosZip("acme.wire", "com.acme.Wire", manifestJson = fullManifestJson))

        assertEquals("acme.wire", info.id)
        assertEquals("com.acme.Wire", info.entry)
        assertEquals("2.3.0", info.version)
    }

    private fun mcosZip(
        id: String,
        entry: String,
        version: String = "1.0.0",
        includeManifest: Boolean = true,
        manifestJson: String? = null,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            if (includeManifest) {
                zos.putNextEntry(ZipEntry(McosPackage.MANIFEST_ENTRY))
                val body = manifestJson
                    ?: """{"id":"$id","entry":"$entry","version":"$version"}"""
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(byteArrayOf(0x64, 0x65, 0x78))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
