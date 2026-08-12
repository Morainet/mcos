package com.mcos.runtime.memory

import com.mcos.sdk.MemoryCategory
import com.mcos.sdk.ResolveResult
import com.mcos.sdk.WriteStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for MemoryStore v0.1.
 * Matches [01-architecture.md Memory], [03-runtime.md 12].
 */
class MemoryStoreTest {

    private lateinit var store: MemoryStore

    @BeforeTest
    fun setUp() {
        store = MemoryStore()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        store.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // M1-M3: Basic put/get
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M1-put and get string value`() = runBlocking {
        store.putString("user.name", "Alice")
        val value = store.get("user.name")
        assertNotNull(value)
        assertEquals("Alice", value.jsonPrimitive.content)
    }

    @Test
    fun `M2-get non-existent path returns null`() = runBlocking {
        val value = store.get("nonexistent.path")
        assertNull(value)
    }

    @Test
    fun `M3-put and get JSON object`() = runBlocking {
        store.putObject("user.address", mapOf(
            "city" to "Beijing",
            "street" to "Chang'an Ave"
        ))
        val value = store.get("user.address")
        assertNotNull(value)
        val obj = value.jsonObject
        assertEquals("Beijing", obj["city"]!!.jsonPrimitive.content)
        assertEquals("Chang'an Ave", obj["street"]!!.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // M4-M6: TTL / Expiry
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M4-TTL entry not expired when within TTL`() = runBlocking {
        store.putString("temp.token", "abc123", ttlMs = 5000)
        val value = store.get("temp.token")
        assertNotNull(value)
        assertEquals("abc123", value.jsonPrimitive.content)
    }

    @Test
    fun `M5-TTL entry expired after TTL elapses`() = runBlocking {
        store.putString("temp.token", "abc123", ttlMs = 50)
        delay(100) // exceed TTL
        val value = store.get("temp.token")
        assertNull(value)
    }

    @Test
    fun `M6-null TTL entry never expires`() = runBlocking {
        store.putString("perm.data", "forever")
        val value = store.get("perm.data")
        assertNotNull(value)
        assertEquals("forever", value!!.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // M7-M9: Overwrite and delete
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M7-overwrite existing path`() = runBlocking {
        store.putString("key", "old")
        store.putString("key", "new")
        assertEquals("new", store.get("key")!!.jsonPrimitive.content)
    }

    @Test
    fun `M8-delete removes entry`() = runBlocking {
        store.putString("key", "value")
        store.delete("key")
        assertNull(store.get("key"))
    }

    @Test
    fun `M9-delete non-existent path does not throw`() = runBlocking {
        store.delete("nonexistent")
        // no exception
    }

    // ═══════════════════════════════════════════════════════════════
    // M10-M12: Semantic reference resolution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M10-resolveRef resolves tagged entry`() = runBlocking {
        store.putString("facts.home_address", "123 Main St", tags = setOf("home"))
        val result = store.resolveRef("home")
        assertIs<ResolveResult.Resolved>(result)
        assertEquals("facts.home_address", result.id)
    }

    @Test
    fun `M11-resolveRef not found for unknown tag`() = runBlocking {
        store.putString("facts.home_address", "123 Main St", tags = setOf("home"))
        val result = store.resolveRef("office")
        assertTrue(result is ResolveResult.NotFound)
    }

    @Test
    fun `M12-resolveRef ambiguous when multiple entries match`() = runBlocking {
        store.putString("a.home", "A", tags = setOf("home"))
        store.putString("b.home", "B", tags = setOf("home"))
        val result = store.resolveRef("home")
        assertIs<ResolveResult.Ambiguous>(result)
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.contains("a.home"))
        assertTrue(result.candidates.contains("b.home"))
    }

    // ═══════════════════════════════════════════════════════════════
    // M13-M15: Semantic type filtering
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M13-resolveRef with semanticType filter`() = runBlocking {
        store.putString("user.home_addr", "123 Main St", tags = setOf("home", "address"))
        store.putString("user.home_phone", "555-0100", tags = setOf("home", "contact"))
        val result = store.resolveRef("home", semanticType = "address")
        assertIs<ResolveResult.Resolved>(result)
        assertEquals("user.home_addr", result.id)
    }

    @Test
    fun `M14-resolveRef with mismatched semanticType`() = runBlocking {
        store.putString("user.home_addr", "123 Main St", tags = setOf("home", "address"))
        val result = store.resolveRef("home", semanticType = "contact")
        assertTrue(result is ResolveResult.NotFound)
    }

    @Test
    fun `M15-resolveRef expired entry does not match`() = runBlocking {
        store.putString("old.home", "expired", ttlMs = 50, tags = setOf("home"))
        store.putString("new.home", "current", tags = setOf("home"))
        delay(100)
        val result = store.resolveRef("home")
        assertIs<ResolveResult.Resolved>(result)
        assertEquals("new.home", result.id)
    }

    // ═══════════════════════════════════════════════════════════════
    // M16-M18: List and count
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M16-list entries by prefix`() = runBlocking {
        store.putString("user.name", "Alice")
        store.putString("user.email", "alice@example.com")
        store.putString("settings.theme", "dark")
        val paths = store.list("user")
        assertEquals(2, paths.size)
        assertTrue(paths.contains("user.name"))
        assertTrue(paths.contains("user.email"))
        assertFalse(paths.contains("settings.theme"))
    }

    @Test
    fun `M17-list all entries with empty prefix`() = runBlocking {
        store.putString("a", "1")
        store.putString("b", "2")
        store.putString("c", "3")
        val paths = store.list()
        assertEquals(3, paths.size)
    }

    @Test
    fun `M18-count returns non-expired entries`() = runBlocking {
        store.putString("keep", "ok")
        store.putString("drop", "expired", ttlMs = 50)
        assertEquals(2, store.count())
        delay(100)
        assertEquals(1, store.count())
    }

    // ═══════════════════════════════════════════════════════════════
    // M19-M21: Management
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M19-clear removes all entries`() = runBlocking {
        store.putString("a", "1")
        store.putString("b", "2")
        store.clear()
        assertEquals(0, store.count())
        assertNull(store.get("a"))
    }

    @Test
    fun `M20-evictExpired removes expired entries`() = runBlocking {
        store.putString("keep", "ok", ttlMs = 5000)
        store.putString("drop", "old", ttlMs = 50)
        delay(100)
        store.evictExpired()
        assertEquals(1, store.count())
        assertNotNull(store.get("keep"))
        assertNull(store.get("drop"))
    }

    @Test
    fun `M21-has checks entry existence`() = runBlocking {
        assertFalse(store.has("key"))
        store.putString("key", "value")
        assertTrue(store.has("key"))
    }

    // ═══════════════════════════════════════════════════════════════
    // M22-M24: Tags and export
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M22-tags returns all semantic tags`() = runBlocking {
        store.putString("a", "1", tags = setOf("home", "address"))
        store.putString("b", "2", tags = setOf("office"))
        assertEquals(setOf("home", "address", "office"), store.tags())
    }

    @Test
    fun `M23-export produces valid JSON`() = runBlocking {
        store.putString("user.name", "Alice", tags = setOf("personal"))
        store.putString("user.age", "30")
        val exported = store.export()
        assertNotNull(exported["user.name"])
        assertNotNull(exported["user.age"])
        val nameEntry = exported["user.name"]!!.jsonObject
        assertNotNull(nameEntry["value"])
        assertTrue(nameEntry["createdAt"] != null)
    }

    @Test
    fun `M24-delete removes from semantic index`() = runBlocking {
        store.putString("facts.home", "123", tags = setOf("home"))
        store.delete("facts.home")
        val result = store.resolveRef("home")
        assertTrue(result is ResolveResult.NotFound)
    }

    // ═══════════════════════════════════════════════════════════════
    // M25-M26: Real-world scenarios
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M25-real-world user preferences and facts`() = runBlocking {
        // Store user preferences
        store.putString("prefs.theme", "dark", tags = setOf("preference"))
        store.putString("prefs.language", "zh-CN", tags = setOf("preference"))

        // Store user facts for semantic recall
        store.putObject("facts.home", mapOf(
            "address" to "北京市朝阳区",
            "type" to "apartment"
        ), tags = setOf("home", "address"))

        store.putObject("facts.office", mapOf(
            "address" to "北京市海淀区",
            "type" to "office"
        ), tags = setOf("office", "address"))

        store.putString("facts.favorite_coffee", "拿铁", tags = setOf("preference", "coffee"))

        // Verify direct access
        assertEquals("dark", store.get("prefs.theme")!!.jsonPrimitive.content)

        // Verify semantic resolution
        val homeResult = store.resolveRef("home")
        assertIs<ResolveResult.Resolved>(homeResult)
        assertEquals("facts.home", homeResult.id)

        val addressResult = store.resolveRef("home", semanticType = "address")
        assertIs<ResolveResult.Resolved>(addressResult)

        // Verify listing
        val prefs = store.list("prefs")
        assertEquals(2, prefs.size)

        // Verify export
        val exported = store.export()
        assertEquals(5, exported.size)
    }

    @Test
    fun `M26-semantic resolution for user context`() = runBlocking {
        // Simulate: user says "导航回公司", AI resolves "公司" -> office address
        store.putObject("user.locations.home", mapOf(
            "address" to "Home Address",
            "lat" to "39.9", "lng" to "116.4"
        ), tags = setOf("home", "location"))

        store.putObject("user.locations.office", mapOf(
            "address" to "Office Address",
            "lat" to "39.9", "lng" to "116.5"
        ), tags = setOf("office", "location"))

        // Resolve "office" -> get the office location fact
        val result = store.resolveRef("office")
        assertIs<ResolveResult.Resolved>(result)
        assertEquals("user.locations.office", result.id)

        // Read the resolved fact
        val office = store.get(result.id)
        assertNotNull(office)
        val obj = office.jsonObject
        assertEquals("Office Address", obj["address"]!!.jsonPrimitive.content)

        // "home" resolves correctly too
        val homeResult = store.resolveRef("home")
        assertIs<ResolveResult.Resolved>(homeResult)
        assertEquals("user.locations.home", homeResult.id)
    }

    // ═══════════════════════════════════════════════════════════════
    // M27-M29: Fuzzy reference resolution (07-memory.md 6.0)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M27-fuzzy ref matches alias or abbreviation`() = runBlocking {
        store.putString("devices.living_room_lamp", "on", tags = setOf("客厅灯"))

        // User says "客厅灯的开关" — the ref contains the tag verbatim,
        // so the alias containment scores 0.85.
        val result = store.resolveRef("客厅灯的开关")
        assertIs<ResolveResult.Resolved>(result)
        assertEquals("devices.living_room_lamp", result.id)
        assertEquals(0.85f, result.confidence)
    }

    @Test
    fun `M28-unresolvable ref returns NotFound with reason`() = runBlocking {
        store.putString("facts.home", "123", tags = setOf("home"))

        val result = store.resolveRef("xyzzy")
        assertIs<ResolveResult.NotFound>(result)
        assertEquals("ref_unresolvable", result.reason)
    }

    @Test
    fun `M29-exact ref resolves with full confidence`() = runBlocking {
        store.putString("facts.home", "123", tags = setOf("home"))

        val result = store.resolveRef("home")
        assertIs<ResolveResult.Resolved>(result)
        assertEquals(1.0f, result.confidence)
    }

    // ═══════════════════════════════════════════════════════════════
    // M30-M33: Write semantics (07-memory.md 5.1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M30-new path write returns CREATED`() = runBlocking {
        val result = store.putString("prefs.theme", "dark")
        assertEquals(WriteStatus.CREATED, result.status)
        assertNull(result.supersededPath)
        assertNull(result.conflict)
        assertEquals("dark", store.get("prefs.theme")!!.jsonPrimitive.content)
    }

    @Test
    fun `M31-same path write returns UPDATED and soft-deletes old value`() = runBlocking {
        store.putString("facts.theme", "light", tags = setOf("preference"))
        val result = store.putString("facts.theme", "dark", tags = setOf("preference"))

        assertEquals(WriteStatus.UPDATED, result.status)
        assertNotNull(result.supersededPath)
        assertTrue(result.supersededPath!!.startsWith("facts.theme@"))

        // Old value kept in history, new value is current.
        val history = store.history("facts.theme")
        assertEquals(1, history.size)
        assertEquals("light", history[0].second.value.jsonPrimitive.content)
        assertEquals("dark", store.get("facts.theme")!!.jsonPrimitive.content)
    }

    @Test
    fun `M32-cross path semantic duplicate returns CONFLICT and withholds write`() = runBlocking {
        store.putString(
            "places.office", "北京市朝阳区望京SOHO",
            category = MemoryCategory.PLACE,
        )
        val result = store.putString(
            "places.company", "北京市朝阳区望京SOHO大厦",
            category = MemoryCategory.PLACE,
        )

        assertEquals(WriteStatus.CONFLICT, result.status)
        assertNull(store.get("places.company"), "conflicting write must be withheld")
        assertEquals("places.office", result.conflict!!.existingPath)
        assertEquals(MemoryCategory.PLACE, result.conflict!!.category)
        assertTrue(result.conflict!!.similarity >= MemoryStore.CONFLICT_THRESHOLD)
    }

    @Test
    fun `M33-checkConflict false skips dedup check`() = runBlocking {
        store.putString(
            "places.office", "北京市朝阳区望京SOHO",
            category = MemoryCategory.PLACE,
        )
        val result = store.putString(
            "places.company", "北京市朝阳区望京SOHO大厦",
            category = MemoryCategory.PLACE,
            checkConflict = false,
        )

        assertEquals(WriteStatus.CREATED, result.status)
        assertEquals("北京市朝阳区望京SOHO大厦", store.get("places.company")!!.jsonPrimitive.content)
    }
}
