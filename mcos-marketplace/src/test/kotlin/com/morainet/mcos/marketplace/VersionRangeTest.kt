package com.morainet.mcos.marketplace

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [VersionRange] — blocklist SemVer range matching (§14.0).
 */
class VersionRangeTest {

    @Test
    fun `star matches any version`() {
        val range = VersionRange("*")
        assertTrue(range.matches("0.0.1"))
        assertTrue(range.matches("1.0.0"))
        assertTrue(range.matches("12.34.56"))
    }

    @Test
    fun `blank spec behaves like star`() {
        assertTrue(VersionRange("  ").matches("1.2.3"))
    }

    @Test
    fun `exact version matches only that version`() {
        val range = VersionRange("1.2.3")
        assertTrue(range.matches("1.2.3"))
        assertFalse(range.matches("1.2.4"))
        assertFalse(range.matches("1.2"))
        assertFalse(range.matches("2.2.3"))
    }

    @Test
    fun `two-part version is normalized for comparison`() {
        // "1.2" parses as 1.2.0 and compares equal to "1.2.0".
        assertTrue(VersionRange("1.2").matches("1.2.0"))
        assertTrue(VersionRange("1.2.0").matches("1.2"))
    }

    @Test
    fun `lower bound is inclusive`() {
        val range = VersionRange(">=1.0.0")
        assertTrue(range.matches("1.0.0"))
        assertTrue(range.matches("1.5.0"))
        assertFalse(range.matches("0.9.9"))
    }

    @Test
    fun `strictly greater bound excludes the bound itself`() {
        val range = VersionRange(">1.0.0")
        assertFalse(range.matches("1.0.0"))
        assertTrue(range.matches("1.0.1"))
    }

    @Test
    fun `upper bound is inclusive`() {
        val range = VersionRange("<=2.0.0")
        assertTrue(range.matches("2.0.0"))
        assertTrue(range.matches("1.9.0"))
        assertFalse(range.matches("2.0.1"))
    }

    @Test
    fun `strictly less bound excludes the bound itself`() {
        val range = VersionRange("<2.0.0")
        assertFalse(range.matches("2.0.0"))
        assertTrue(range.matches("1.9.9"))
    }

    @Test
    fun `whitespace separated bounds act as a conjunction`() {
        val range = VersionRange(">=1.0.0 <2.0.0")
        assertTrue(range.matches("1.0.0"))
        assertTrue(range.matches("1.99.0"))
        assertFalse(range.matches("0.9.0"))
        assertFalse(range.matches("2.0.0"))
        assertFalse(range.matches("2.5.0"))
    }

    @Test
    fun `equals operator matches exactly`() {
        val range = VersionRange("=1.0.0")
        assertTrue(range.matches("1.0.0"))
        assertFalse(range.matches("1.0.1"))
    }

    @Test
    fun `caret keeps the major line`() {
        val range = VersionRange("^1.2.3")
        assertTrue(range.matches("1.2.3"))
        assertTrue(range.matches("1.9.0"))
        assertFalse(range.matches("0.9.0"))
        assertFalse(range.matches("2.0.0"))
    }

    @Test
    fun `caret on zero minor keeps the minor line`() {
        val range = VersionRange("^0.2.3")
        assertTrue(range.matches("0.2.3"))
        assertTrue(range.matches("0.2.99"))
        assertFalse(range.matches("0.3.0"))
        assertFalse(range.matches("1.0.0"))
    }

    @Test
    fun `caret on zero zero keeps the patch line`() {
        val range = VersionRange("^0.0.3")
        assertTrue(range.matches("0.0.3"))
        assertFalse(range.matches("0.0.4"))
        assertFalse(range.matches("0.1.0"))
    }

    @Test
    fun `caret normalizes missing segments`() {
        assertTrue(VersionRange("^1").matches("1.5.0"))
        assertFalse(VersionRange("^1").matches("2.0.0"))
        assertTrue(VersionRange("^0.0").matches("0.0.9"))
        assertFalse(VersionRange("^0.0").matches("0.1.0"))
        assertTrue(VersionRange("^0").matches("0.9.9"))
        assertFalse(VersionRange("^0").matches("1.0.0"))
    }

    @Test
    fun `tilde keeps the minor line`() {
        val range = VersionRange("~1.2.3")
        assertTrue(range.matches("1.2.3"))
        assertTrue(range.matches("1.2.9"))
        assertFalse(range.matches("1.3.0"))
        assertFalse(range.matches("2.0.0"))
    }

    @Test
    fun `tilde on a single segment keeps the major line`() {
        val range = VersionRange("~1")
        assertTrue(range.matches("1.0.0"))
        assertTrue(range.matches("1.9.9"))
        assertFalse(range.matches("2.0.0"))
        assertTrue(VersionRange("~0").matches("0.5.0"))
        assertFalse(VersionRange("~0").matches("1.0.0"))
    }

    @Test
    fun `caret and tilde compose with plain bounds`() {
        val range = VersionRange(">=1.0.0 ^1.5.0")
        assertTrue(range.matches("1.5.0"))
        assertFalse(range.matches("2.0.0"))
        assertFalse(range.matches("1.4.0"))
    }

    @Test
    fun `invalid range never matches (fail-safe)`() {
        assertFalse(VersionRange("not-a-version").matches("1.0.0"))
        assertFalse(VersionRange("1.2.3.4").matches("1.2.3"))
        assertFalse(VersionRange(">= 1.0.0").matches("1.0.0"))
        assertFalse(VersionRange("abc >=1.0.0").matches("1.5.0"))
        assertFalse(VersionRange("^not-a-version").matches("1.0.0"))
        assertFalse(VersionRange("~1.2.3.4").matches("1.2.3"))
    }

    @Test
    fun `isValid distinguishes malformed ranges`() {
        assertTrue(VersionRange("*").isValid)
        assertTrue(VersionRange(">=1.0.0 <2.0.0").isValid)
        assertTrue(VersionRange("^1.2.3").isValid)
        assertFalse(VersionRange("garbage").isValid)
        assertFalse(VersionRange("^1.2.3.4").isValid)
    }

    @Test
    fun `invalid candidate version never matches`() {
        val range = VersionRange("*")
        assertFalse(range.matches("garbage"))
        assertFalse(range.matches("1"))
        assertFalse(range.matches(""))
    }
}
