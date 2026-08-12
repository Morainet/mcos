package com.mcos.runtime.security

/**
 * Resolves `{{secret.<key>}}` templates in outbound HTTP request values.
 *
 * Implements MCOS security spec [08-security.md §9.2]: the Planner only ever
 * sees the template string; at invoke time the Runtime reads the value from
 * the executing plugin's scoped SecureStore and injects it into the outbound
 * request. The resolved value is never written back into
 * ExecutionContext.args, and the audit trail keeps the template form.
 */
object SecretResolver {

    /** Matches `{{secret.<key>}}` where key is [A-Za-z0-9_.-]. */
    private val TEMPLATE = Regex("""\{\{\s*secret\.([A-Za-z0-9_.-]+)\s*\}\}""")

    /** True if [value] contains at least one `{{secret.*}}` template. */
    fun containsTemplate(value: String): Boolean = TEMPLATE.containsMatchIn(value)

    /** Keys referenced in [value], in order of appearance. */
    fun referencedKeys(value: String): List<String> =
        TEMPLATE.findAll(value).map { it.groupValues[1] }.toList()

    /**
     * Replace every `{{secret.<key>}}` in [value] with [lookup]'s result.
     *
     * Keys that resolve to null are left as the inert template — a value is
     * never substituted and no secret ever leaks into the plan/audit trail.
     * [lookup] is suspend because the backing SecureStore read is suspend.
     */
    suspend fun resolve(value: String, lookup: suspend (String) -> String?): String {
        val keys = referencedKeys(value)
        if (keys.isEmpty()) return value
        // Resolve first (suspend), then apply with a plain map.
        val resolved = HashMap<String, String?>()
        for (key in keys) resolved[key] = lookup(key)
        return TEMPLATE.replace(value) { match ->
            resolved[match.groupValues[1]] ?: match.value
        }
    }
}
