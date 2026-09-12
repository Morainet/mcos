package com.morainet.mcos.plugin.intent

/**
 * App Function command-id encoding (02-command-protocol.md §12.5).
 *
 * The command-id grammar uses `.` as the namespace separator, but Java/Kotlin
 * package names also use `.` — the adapter therefore swaps each `.` in the
 * package for `_` when forming the id:
 *
 * ```text
 * Package:   com.example.notes
 * Function:  createNote
 * Command:   sys.appfn.com_example_notes.createNote
 * ```
 *
 * **[decode] follows the spec's algorithm verbatim** — split on `.`, drop the
 * `sys.appfn` prefix and the function segment, then map every `_` back to `.`.
 *
 * **Where the spec's reasoning does not hold — and where we fail closed:**
 * §12.5 calls the reverse lookup "unambiguous because step 1 is a 1:1 char
 * swap". That is true only for packages that contain no `_`:
 * `com.my_app` (a literal `_`) and `com.my.app` (a `.`) both encode to
 * `com_my_app`, so a decoded id cannot tell which app it named — and running
 * foreign code with the caller's arguments in the **wrong** app is a real
 * defect, not a cosmetic one. Since the ambiguity is in the encoding (not in
 * the decode pass), [encode] is the side that refuses: a package containing
 * `_` has no unambiguous id, so it throws and callers address such an app
 * through the authoritative `appfn.invoke(package, function, args)` argument
 * form, which never round-trips through an id.
 *
 * Consequently every id [decode] can encounter in practice was produced by
 * [encode] from an underscore-free package, and its `_ → .` mapping is exact.
 */
object AppFunctionIds {

    /** Prefix every generated App Function command id carries (§12.5 rule 4). */
    const val PREFIX = "sys.appfn"

    /**
     * `com.example.notes` + `createNote` -> `sys.appfn.com_example_notes.createNote`.
     *
     * @throws IllegalArgumentException when any argument is blank, the
     *   function is not a single segment, or the package contains `_` (which
     *   has no unambiguous id — see the class KDoc).
     */
    fun encode(packageName: String, function: String): String {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(function.isNotBlank()) { "function must not be blank" }
        require('.' !in function) { "function must be a single segment, got '$function'" }
        require('_' !in packageName) {
            "package name containing '_' cannot be encoded unambiguously (02 §12.5): $packageName"
        }
        return "$PREFIX.${packageName.replace('.', '_')}.$function"
    }

    /**
     * Reverse lookup for an id of the `sys.appfn.<encodedPackage>.<function>`
     * shape, per §12.5.
     *
     * @return the decoded pair, or null when [commandId] is not of that shape.
     */
    fun decode(commandId: String): Decoded? {
        val parts = commandId.split('.')
        // Exactly four segments: `sys`, `appfn`, the encoded package (a `.`
        // inside it would be an encoded package that skipped the swap), and
        // the function.
        if (parts.size != 4) return null
        if (parts[0] != "sys" || parts[1] != "appfn") return null
        val encodedPackage = parts[2]
        val function = parts[3]
        if (encodedPackage.isBlank() || function.isBlank()) return null
        return Decoded(packageName = encodedPackage.replace('_', '.'), function = function)
    }

    /** A decoded App Function target. */
    data class Decoded(val packageName: String, val function: String)
}
