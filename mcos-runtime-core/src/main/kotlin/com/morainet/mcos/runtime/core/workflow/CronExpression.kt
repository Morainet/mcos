package com.morainet.mcos.runtime.core.workflow

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A parsed 5-field cron expression (05-workflow.md §9.3).
 *
 * Dialect (deliberately the portable vixie-cron subset — no `L`/`#`/`@`
 * extensions, no seconds field):
 *
 * | field | allowed values |
 * |-------|----------------|
 * | minute | 0-59 |
 * | hour   | 0-23 |
 * | day of month | 1-31 |
 * | month  | 1-12 or JAN-DEC |
 * | day of week | 0-7 (0 and 7 both Sunday) or SUN-SAT |
 *
 * Each field accepts a bare star, a plain number, a name (month / weekday), a
 * comma-separated list, a range `a-b` (names allowed as bounds), and a step
 * suffix `x/n` (so `10-40/5`, `mon-fri/2`, and a bare star with a step gives
 * every-n). Whitespace between fields is
 * flexible; anything else is a parse failure → [parse] returns null.
 *
 * Standard cron union semantics apply: when **both** day-of-month and
 * day-of-week are restricted (neither is a bare `*`), a minute matches when
 * **either** field matches; when one of them is `*`, the other governs alone.
 *
 * Matching is minute-granular and computed in the caller's timezone
 * (`ZoneId`), so DST transitions are handled by `java.time`. This class is a
 * pure function — no clock, no I/O — which is what keeps [nextFire] and the
 * schedule-trigger tests deterministic.
 */
class CronExpression private constructor(
    private val original: String,
    private val minutes: IntArray,
    private val hours: IntArray,
    private val daysOfMonth: IntArray,
    private val months: IntArray,
    private val daysOfWeek: IntArray,
    /** `true` when the DOM field is a bare `*` (governs the union rule). */
    private val domStar: Boolean,
    /** `true` when the DOW field is a bare `*` (governs the union rule). */
    private val dowStar: Boolean,
) {

    /**
     * Epoch millis of the first matching minute **strictly after**
     * [afterEpochMs], in [zone]. Scans forward minute-by-minute capped at a
     * ~4-year horizon (enough to reach any reachable Feb 29); returns null
     * when the expression can never match in that horizon (e.g. day-of-month
     * 31 in February) — treat as unsatisfiable.
     */
    fun nextFire(afterEpochMs: Long, zone: ZoneId): Long? {
        // Truncate to the minute and step to the next candidate minute —
        // strictly-after so a fire boundary is never returned twice for the
        // same "now".
        val start: ZonedDateTime = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(afterEpochMs), zone
        ).withNano(0).withSecond(0).plusMinutes(1)
        var t = start
        val limit = start.plusMinutes(SEARCH_HORIZON_MINUTES)
        while (t.isBefore(limit)) {
            if (matches(t.toLocalDateTime())) return t.toInstant().toEpochMilli()
            t = t.plusMinutes(1)
        }
        return null
    }

    /** Whether the given minute (in any zone) matches every field. */
    fun matches(t: LocalDateTime): Boolean {
        if (!minutes.contains(t.minute)) return false
        if (!hours.contains(t.hour)) return false
        if (!months.contains(t.monthValue)) return false
        // Vixie union rule: both DOM and DOW restricted → either matches.
        val domMatch = daysOfMonth.contains(t.dayOfMonth)
        val dowMatch = daysOfWeek.contains(t.dayOfWeek.value % 7)
        return if (domStar || dowStar) domMatch && dowMatch else domMatch || dowMatch
    }

    override fun toString(): String = original

    companion object {
        /** ~4 years of minutes — the worst case (Feb 29) any 5-field
         *  expression needs to prove (un)satisfiability. */
        private const val SEARCH_HORIZON_MINUTES = 4L * 366 * 24 * 60

        private val MONTH_NAMES = mapOf(
            "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
            "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12,
        )
        private val DAY_NAMES = mapOf(
            "SUN" to 0, "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6,
        )

        /**
         * Parse a 5-field cron expression; null on any syntax violation
         * (wrong field count, out-of-range value, malformed range/step,
         * unknown name). Day-of-week 7 normalizes onto 0 (Sunday).
         */
        fun parse(expression: String): CronExpression? {
            val trimmed = expression.trim()
            // Split on any whitespace run (tabs included) — cron's own
            // tokenizer behavior.
            val fields = trimmed.split(Regex("\\s+"))
            if (fields.size != 5) return null

            val domStar = fields[2] == "*"
            val dowStar = fields[4] == "*"

            val minutes = parseField(fields[0], 0, 59, null) ?: return null
            val hours = parseField(fields[1], 0, 23, null) ?: return null
            val dom = parseField(fields[2], 1, 31, null) ?: return null
            val months = parseField(fields[3], 1, 12, MONTH_NAMES) ?: return null
            // Accept 0-7 in the weekday field; 7 folds onto 0 (Sunday).
            val dow = parseField(fields[4], 0, 7, DAY_NAMES, foldSeven = true) ?: return null

            return CronExpression(
                original = trimmed,
                minutes = minutes, hours = hours, daysOfMonth = dom,
                months = months, daysOfWeek = dow,
                domStar = domStar, dowStar = dowStar,
            )
        }

        /**
         * Parse one field into a sorted distinct value list.
         *
         * @param names optional name→value map (JAN..DEC / SUN..SAT),
         *        case-insensitive; numeric-only fields pass null
         * @param foldSeven weekday-only: fold value 7 onto 0
         */
        private fun parseField(
            field: String,
            min: Int,
            max: Int,
            names: Map<String, Int>?,
            foldSeven: Boolean = false,
        ): IntArray? {
            val values = sortedSetOf<Int>()
            for (part in field.split(',')) {
                if (part.isEmpty()) return null
                // Split off the step suffix first: `base/step`.
                val step: Int
                val base: String
                val slash = part.indexOf('/')
                if (slash >= 0) {
                    base = part.substring(0, slash)
                    step = part.substring(slash + 1).toIntOrNull() ?: return null
                    if (step <= 0) return null
                } else {
                    base = part
                    step = 1
                }

                // Range bounds — names allowed where the field has names.
                val lo: Int
                val hi: Int
                if (base == "*") {
                    lo = min; hi = max
                } else if (base.contains('-')) {
                    val bounds = base.split('-')
                    if (bounds.size != 2) return null
                    lo = resolveValue(bounds[0], names) ?: return null
                    hi = resolveValue(bounds[1], names) ?: return null
                } else {
                    lo = resolveValue(base, names) ?: return null
                    hi = lo
                }
                if (lo < min || hi > max || lo > hi) return null

                var v = lo
                while (v <= hi) {
                    values.add(if (foldSeven && v == 7) 0 else v)
                    v += step
                }
            }
            if (values.isEmpty()) return null
            return values.toIntArray()
        }

        /** A single bound value: numeric literal or a 3-letter name. */
        private fun resolveValue(token: String, names: Map<String, Int>?): Int? {
            names?.get(token.uppercase())?.let { return it }
            return token.toIntOrNull()
        }
    }
}
