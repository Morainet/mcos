package com.morainet.mcos.android.demo

import com.morainet.mcos.android.demo.skills.SkillStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [com.morainet.mcos.android.demo.skills.SkillStore] persistence + [com.morainet.mcos.android.demo.skills.SkillParser] format handling. Uses the shared
 * [TestMarketplace.FakeSecureStore] as the JVM-side store.
 */
class SkillStoreTest {

    private fun store() = SkillStore(TestMarketplace.FakeSecureStore())

    @Test
    fun `parses SKILL_md frontmatter`() = runBlocking {
        val md = """
            ---
            name: Weather Helper
            description: How to answer weather questions
            ---
            Always confirm the city first.
        """.trimIndent()
        val s = store()
        val rec = s.import(md)
        assertNotNull(rec)
        assertEquals("Weather Helper", rec!!.skill.name)
        assertEquals("How to answer weather questions", rec.skill.description)
        assertTrue(rec.skill.instructions.contains("confirm the city"))
        assertEquals("weather-helper", rec.skill.id)
    }

    @Test
    fun `parses JSON skill`() = runBlocking {
        val json = """{"name":"Booking","description":"book things","instructions":"Prefer morning slots."}"""
        val rec = store().import(json)
        assertNotNull(rec)
        assertEquals("Booking", rec!!.skill.name)
        assertTrue(rec.skill.instructions.contains("morning"))
    }

    @Test
    fun `rejects text with no instructions`() = runBlocking {
        assertNull(store().import("   "))
        assertNull(store().import("""{"name":"Empty"}"""))
    }

    @Test
    fun `enabled filter and persistence round-trip`() = runBlocking {
        val secure = TestMarketplace.FakeSecureStore()
        val s1 = SkillStore(secure)
        s1.import("""{"name":"A","description":"","instructions":"do a"}""")
        s1.import("""{"name":"B","description":"","instructions":"do b"}""")
        s1.setEnabled("b", false)

        // A fresh store reads the same SecureStore — persistence survives.
        val s2 = SkillStore(secure)
        assertEquals(2, s2.list().size)
        val enabled = s2.enabled()
        assertEquals(1, enabled.size)
        assertEquals("A", enabled.first().name)
    }

    @Test
    fun `remove drops the skill`() = runBlocking {
        val s = store()
        s.import("""{"name":"Gone","description":"","instructions":"x"}""")
        s.remove("gone")
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `import replaces a duplicate id preserving enabled flag`() = runBlocking {
        val s = store()
        s.import("""{"name":"Dup","description":"","instructions":"v1"}""")
        s.setEnabled("dup", false)
        s.import("""{"name":"Dup","description":"","instructions":"v2"}""")
        val list = s.list()
        assertEquals(1, list.size)
        assertEquals("v2", list.first().skill.instructions)
        assertFalse("enabled flag should survive re-import", list.first().enabled)
    }

    @Test
    fun `enabledVersion changes when the enabled set changes`() = runBlocking {
        val s = store()
        s.import("""{"name":"V","description":"","instructions":"x"}""")
        val v1 = s.enabledVersion()
        s.setEnabled("v", false)
        assertTrue(v1 != s.enabledVersion())
    }
}
