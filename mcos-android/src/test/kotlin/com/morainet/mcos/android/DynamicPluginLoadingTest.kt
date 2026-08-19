package com.morainet.mcos.android

import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.sdk.McosPlugin
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
