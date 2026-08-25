package com.morainet.mcos.runtime.core.workflow

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

/**
 * Tests for [CronExpression] — the 5-field cron dialect and minute-scanner
 * backing schedule triggers (05-workflow.md §9.3). All [CronExpression.nextFire]
 * calls are pure: fixed instants in fixed zones, no clock, no waits.
 */
class CronExpressionTest {

    private val SH = ZoneId.of("Asia/Shanghai")
    private val NY = ZoneId.of("America/New_York")

    private fun at(
        y: Int, mo: Int, d: Int, h: Int, mi: Int,
        s: Int = 0, nanos: Int = 0, zone: ZoneId = SH,
    ): Long = ZonedDateTime.of(y, mo, d, h, mi, s, nanos, zone).toInstant().toEpochMilli()

    private fun parse(expr: String): CronExpression =
        assertNotNull(CronExpression.parse(expr), "expected parse success: '$expr'")

    private fun next(expr: String, from: Long, zone: ZoneId = SH): Long? =
        parse(expr).nextFire(from, zone)

    // ─── Field dialects ─────────────────────────────────────────────────

    @Test
    fun `CR1-star matches every minute, seconds truncated`() {
        // 10:04:30.500 → the candidate boundary is the next full minute,
        // never the in-progress one.
        assertEquals(
            at(2026, 8, 24, 10, 5),
            next("* * * * *", at(2026, 8, 24, 10, 4, s = 30, nanos = 500_000_000)),
        )
    }

    @Test
    fun `CR2-fixed daily time fires same day or rolls to next day`() {
        assertEquals(at(2026, 8, 24, 4, 30), next("30 4 * * *", at(2026, 8, 24, 3, 0)))
        assertEquals(at(2026, 8, 25, 4, 30), next("30 4 * * *", at(2026, 8, 24, 10, 0)))
        // Exactly on the boundary → strictly-after means tomorrow.
        assertEquals(at(2026, 8, 25, 4, 30), next("30 4 * * *", at(2026, 8, 24, 4, 30)))
    }

    @Test
    fun `CR3-comma list matches each member and rolls over the hour`() {
        assertEquals(at(2026, 8, 24, 10, 30), next("0,30 * * * *", at(2026, 8, 24, 10, 10)))
        assertEquals(at(2026, 8, 24, 11, 0), next("0,30 * * * *", at(2026, 8, 24, 10, 31)))
    }

    @Test
    fun `CR4-ranges in minute and hour fields`() {
        assertEquals(at(2026, 8, 24, 10, 9), next("9-17 * * * *", at(2026, 8, 24, 10, 0)))
        assertEquals(at(2026, 8, 24, 11, 9), next("9-17 * * * *", at(2026, 8, 24, 10, 18)))
        // Hourly 09:00-17:00; from 18:00 the next is tomorrow 09:00.
        assertEquals(at(2026, 8, 25, 9, 0), next("0 9-17 * * *", at(2026, 8, 24, 18, 0)))
    }

    @Test
    fun `CR5-step is strictly-after and truncates sub-minute precision`() {
        assertEquals(at(2026, 8, 24, 10, 5), next("*/5 * * * *", at(2026, 8, 24, 10, 3, s = 30)))
        // Sitting exactly on a fire boundary must yield the NEXT one — the
        // dedup guarantee tick() relies on.
        assertEquals(at(2026, 8, 24, 10, 10), next("*/5 * * * *", at(2026, 8, 24, 10, 5)))
    }

    @Test
    fun `CR6-range with step only steps within the range`() {
        // minutes 10, 20, 30, 40
        assertEquals(at(2026, 8, 24, 10, 40), next("10-40/10 * * * *", at(2026, 8, 24, 10, 31)))
        assertEquals(at(2026, 8, 24, 11, 10), next("10-40/10 * * * *", at(2026, 8, 24, 10, 41)))
    }

    @Test
    fun `CR7-month names parse and scan across the year boundary`() {
        assertEquals(
            at(2027, 1, 1, 0, 0),
            next("0 0 1 JAN *", at(2026, 8, 24, 10, 0)),
        )
        assertEquals(
            at(2027, 3, 1, 0, 0),
            next("0 0 1 MAR-JUN *", at(2026, 8, 24, 10, 0)),
        )
    }

    @Test
    fun `CR8-weekday names bound ranges and steps`() {
        // 2026-08-24 is a Monday → same-day noon.
        assertEquals(
            at(2026, 8, 24, 12, 0),
            next("0 12 * * MON-FRI", at(2026, 8, 24, 10, 0)),
        )
        // 2026-08-29 is a Saturday → Monday 2026-08-31 noon.
        assertEquals(
            at(2026, 8, 31, 12, 0),
            next("0 12 * * MON-FRI", at(2026, 8, 29, 10, 0)),
        )
        val monWedFri = parse("0 0 * * MON-FRI/2")
        assertTrue(monWedFri.matches(java.time.LocalDateTime.of(2026, 8, 24, 0, 0)))  // Mon
        assertFalse(monWedFri.matches(java.time.LocalDateTime.of(2026, 8, 25, 0, 0))) // Tue
        assertTrue(monWedFri.matches(java.time.LocalDateTime.of(2026, 8, 26, 0, 0)))  // Wed
    }

    @Test
    fun `CR9-weekday 7 folds onto Sunday`() {
        val satMorning = at(2026, 8, 29, 10, 0)
        assertEquals(
            next("0 12 * * 0", satMorning),
            next("0 12 * * 7", satMorning),
        )
        assertEquals(at(2026, 8, 30, 12, 0), next("0 12 * * 7", satMorning))
    }

    // ─── vixie dom/dow semantics ────────────────────────────────────────

    @Test
    fun `CR10-both dom and dow restricted means union`() {
        // "0 0 13 * FRI" — 13th of month OR any Friday (vixie union rule).
        val union = parse("0 0 13 * FRI")
        assertTrue(union.matches(java.time.LocalDateTime.of(2026, 11, 13, 0, 0)))  // Friday the 13th
        assertTrue(union.matches(java.time.LocalDateTime.of(2026, 12, 13, 0, 0)))  // Sunday the 13th → dom
        assertTrue(union.matches(java.time.LocalDateTime.of(2026, 11, 20, 0, 0)))  // Friday the 20th → dow
        assertFalse(union.matches(java.time.LocalDateTime.of(2026, 11, 14, 0, 0))) // Saturday the 14th
        // Non-matching hour/minute must still fail even on a unioned day.
        assertFalse(union.matches(java.time.LocalDateTime.of(2026, 11, 13, 10, 30)))
    }

    @Test
    fun `CR11-bare star on one side defers to the other field`() {
        val domOnly = parse("0 0 13 * *")
        assertTrue(domOnly.matches(java.time.LocalDateTime.of(2026, 12, 13, 0, 0)))   // Sunday 13th
        assertFalse(domOnly.matches(java.time.LocalDateTime.of(2026, 11, 20, 0, 0))) // Friday the 20th
        val dowOnly = parse("0 0 * * FRI")
        assertTrue(dowOnly.matches(java.time.LocalDateTime.of(2026, 11, 20, 0, 0)))
        assertFalse(dowOnly.matches(java.time.LocalDateTime.of(2026, 12, 13, 0, 0))) // Sunday
    }

    // ─── Horizon / satisfiability ───────────────────────────────────────

    @Test
    fun `CR12-Feb 29 is reachable within the 4-year horizon`() {
        assertEquals(
            at(2028, 2, 29, 0, 0),
            next("0 0 29 2 *", at(2026, 8, 24, 10, 0)),
        )
    }

    @Test
    fun `CR13-unsatisfiable expressions return null`() {
        assertNull(next("0 0 31 2 *", at(2026, 8, 24, 10, 0)))  // Feb 31 never exists
        assertNull(next("0 0 30 2 *", at(2026, 8, 24, 10, 0)))   // Feb 30 neither
    }

    @Test
    fun `CR14-invalid syntax is rejected`() {
        val bad = listOf(
            "",            "   ",
            "* * * *",     "* * * * * *",     // wrong field count
            "60 * * * *",  "* 24 * * *",  "0 0 32 * *",  "0 0 31 13 *", "0 0 * * 8",
            "*/0 * * * *",                   // step must be positive
            "5- * * * *",  "*-5 * * * *",     // malformed ranges
            "1,,2 * * * *",                   // empty list member
            "1-2-3 * * * *",                  // too many range bounds
            "5/1/2 * * * *",                  // second slash not numeric
            "abc * * * *",                    // unknown token
            "JAN * * * *",                    // name in a numeric-only field
            "* * * JAN-13 *",                 // range bound out of month range
        )
        for (expr in bad) {
            assertNull(CronExpression.parse(expr), "expected rejection: '$expr'")
        }
    }

    @Test
    fun `CR15-name lists in the weekday field`() {
        val weekend = parse("0 0 * * SUN,SAT")
        assertTrue(weekend.matches(java.time.LocalDateTime.of(2026, 8, 29, 0, 0)))  // Sat
        assertTrue(weekend.matches(java.time.LocalDateTime.of(2026, 8, 30, 0, 0)))  // Sun
        assertFalse(weekend.matches(java.time.LocalDateTime.of(2026, 8, 24, 0, 0))) // Mon
        // Lowercase names are accepted (case-insensitive normalization).
        assertNotNull(CronExpression.parse("0 0 * * sun,sat"))
    }

    // ─── Zones & DST ────────────────────────────────────────────────────

    @Test
    fun `CR16-nextFire is computed in the trigger's timezone`() {
        // Same wall-clock noon, two zones → epochs 12h apart in August
        // (Asia/Shanghai UTC+8 vs America/New_York EDT UTC-4).
        assertEquals(at(2026, 8, 24, 12, 0, zone = SH), next("0 12 * * *", at(2026, 8, 24, 0, 0), SH))
        assertEquals(at(2026, 8, 24, 12, 0, zone = NY), next("0 12 * * *", at(2026, 8, 24, 0, 0), NY))
    }

    @Test
    fun `CR17-nonexistent local time in the DST gap is skipped`() {
        // US spring-forward: 2027-03-14 02:00-02:59 does not exist in
        // America/New_York, so a 02:30 schedule cannot fire that day — the
        // scanner lands on the next day's 02:30.
        val fired = Instant.ofEpochMilli(
            next("30 2 * * *", at(2027, 3, 14, 0, 0, zone = NY), NY)!!
        ).atZone(NY)
        assertEquals(LocalDate.of(2027, 3, 15), fired.toLocalDate())
        assertEquals(2, fired.hour)
        assertEquals(30, fired.minute)
        // Control: the day before the transition behaves normally.
        val control = Instant.ofEpochMilli(
            next("30 2 * * *", at(2027, 3, 13, 0, 0, zone = NY), NY)!!
        ).atZone(NY)
        assertEquals(LocalDate.of(2027, 3, 13), control.toLocalDate())
        assertEquals(2, control.hour)
    }

    // ─── Misc ───────────────────────────────────────────────────────────

    @Test
    fun `CR18-toString preserves the trimmed original`() {
        assertEquals("*/5 4 * * mon-fri", parse("  */5 4 * * mon-fri  ").toString())
    }
}
