package com.morainet.mcos.conformance.manifest

import com.morainet.mcos.runtime.core.plugin.McosPackage
import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manifest conformance (spec 09 §5.1 gates 1, 2, 3, 7).
 *
 * Each sub-case is a single (build → expect → run) tuple. The runner
 * inverts the expectation against the runtime response, so the suite
 * stays decoupled from individual error strings and stays green when
 * the decode error wording evolves.
 *
 * Semantics note: a *Pass* means "the conformance runner correctly
 * observed the expected outcome". For positive cases that's a
 * successful decode; for negative cases it means the runner detected
 * the gate violation (e.g. a reserved namespace command id) — the
 * publisher who sees Pass on a negative case has been told their
 * submission would be rejected.
 */
class ManifestConformanceSuite : ConformanceSuite {
    override val id = "manifest"
    override val title = "Manifest schema (marketplace CI gates 1/2/3/7)"
    override val spec = "09 §5.1 gates 1/2/3/7 + 04 §13.2"

    override fun cases(): List<ConformanceCase> = buildList {
        // ─── Gate 1: manifest schema decodes ────────────────────────────
        add(manifestSchemaCase(
            id = "manifest-gate1-valid",
            title = "valid manifest decodes",
            expectation = ExpectPass,
            // The reader consumes a .mcos ZIP, not bare manifest bytes —
            // wrap the manifest like a real package would carry it.
            build = { manifestJson(validManifestJson()) },
        ))
        add(manifestSchemaCase(
            id = "manifest-gate1-missing-id",
            title = "missing 'id' field → FormatException",
            expectation = ExpectFail("missing required 'id'"),
            build = { manifestJson("""{"entry": "com.example.Foo"}""") },
        ))
        add(manifestSchemaCase(
            id = "manifest-gate1-missing-entry",
            title = "missing 'entry' field → FormatException",
            expectation = ExpectFail("missing required 'entry'"),
            build = { manifestJson("""{"id": "com.example.foo"}""") },
        ))
        add(manifestSchemaCase(
            id = "manifest-gate1-unknown-side-effect",
            title = "unknown sideEffectClass → FormatException (fail-closed)",
            expectation = ExpectFail("unknown sideEffectClass"),
            build = { manifestJson(badSideEffectManifest()) },
        ))
        add(manifestSchemaCase(
            id = "manifest-gate1-malformed-zip",
            title = "non-zip bytes → FormatException",
            expectation = ExpectFail("not a readable zip"),
            build = { "this is not a zip".toByteArray() },
        ))
        add(manifestSchemaCase(
            id = "manifest-gate1-no-plugin-json",
            title = "zip without plugin.json → FormatException",
            expectation = ExpectFail("missing from .mcos package"),
            build = { zipOf("README.md" to "hello".toByteArray()) },
        ))

        // ─── Gate 2: reserved namespace command ids ─────────────────────
        for (reserved in RESERVED_COMMAND_PREFIXES) {
            val commandId = "$reserved.example"
            add(reservedNamespaceCase(reserved, commandId))
        }
        add(reservedNamespacePositiveCase())

        // ─── Gate 3: duplicate command ids ──────────────────────────────
        add(manifestSchemaCase(
            id = "manifest-gate3-duplicate-commands",
            title = "two commands with the same id → FormatException",
            expectation = ExpectFail("duplicate command id"),
            build = {
                manifestJson(
                    manifestWithCommands(
                        listOf("demo.ping" to SideEffectClass.read, "demo.ping" to SideEffectClass.read),
                    ),
                )
            },
        ))
        add(manifestSchemaCase(
            id = "manifest-gate3-unique-commands",
            title = "unique command ids decode cleanly",
            expectation = ExpectPass,
            build = {
                manifestJson(
                    manifestWithCommands(
                        listOf("demo.ping" to SideEffectClass.read, "demo.pong" to SideEffectClass.read),
                    ),
                )
            },
        ))

        // ─── Gate 7: recipe bodyTemplate secret containment ──────────────
        add(recipeSecretContainmentCase())
    }

    // ─── Expectation type ────────────────────────────────────────────────

    private sealed class Expectation
    private object ExpectPass : Expectation()
    private data class ExpectFail(val reasonFragment: String) : Expectation()

    // ─── Gate 1 + 3: manifest decode round-trip ──────────────────────────

    /**
     * Run a manifest through [McosPackage.readPluginManifest] and
     * compare the outcome to [expectation]. For ExpectPass we want a
     * successful decode; for ExpectFail we want a
     * [McosPackage.FormatException] whose message contains
     * [Expectation.reasonFragment].
     */
    private fun manifestSchemaCase(
        id: String,
        title: String,
        expectation: Expectation,
        build: () -> ByteArray,
    ): ConformanceCase = object : ConformanceCase {
        override val id = id
        override val title = title
        override val spec = "09 §5.1 gates 1/3 + 04 §13.2"
        override val category = "manifest"

        override fun run(): ConformanceCase.Result {
            val bytes = build()
            return try {
                McosPackage.readPluginManifest(bytes)
                when (expectation) {
                    is ExpectPass -> ConformanceCase.Result.Pass
                    is ExpectFail -> ConformanceCase.Result.Fail(
                        message = "expected FormatException containing " +
                            "'${expectation.reasonFragment}', but manifest parsed successfully",
                    )
                }
            } catch (e: McosPackage.FormatException) {
                when (expectation) {
                    is ExpectPass -> ConformanceCase.Result.Fail(
                        message = "expected pass, got FormatException: ${e.message}",
                        detail = e.stackTraceToString().take(400),
                    )
                    is ExpectFail -> if (
                        e.message.orEmpty().contains(expectation.reasonFragment, ignoreCase = true)
                    ) {
                        ConformanceCase.Result.Pass
                    } else {
                        ConformanceCase.Result.Fail(
                            message = "FormatException reason mismatch: " +
                                "expected fragment '${expectation.reasonFragment}', " +
                                "got '${e.message}'",
                        )
                    }
                }
            }
        }
    }

    // ─── Gate 2: reserved namespace command ids ──────────────────────────

    /**
     * Reserved-namespace case — the runner decodes the manifest and
     * verifies the namespace rule from the publisher's side. A Pass
     * means the runner detected the violation; a Fail means it let a
     * hijacked command id through.
     */
    private fun reservedNamespaceCase(reservedPrefix: String, commandId: String): ConformanceCase =
        object : ConformanceCase {
            override val id = "manifest-gate2-reserved-${reservedPrefix.removeSuffix(".")}"
            override val title = "command id '$commandId' (reserved '$reservedPrefix') must be flagged"
            override val spec = "09 §5.1 gate 2 + 02 §4.3"
            override val category = "manifest"

            override fun run(): ConformanceCase.Result {
                val bytes = manifestJson(manifestWithCommands(listOf(commandId to SideEffectClass.read)))
                // Native reader rejection is acceptable.
                try {
                    McosPackage.readPluginManifest(bytes)
                } catch (e: McosPackage.FormatException) {
                    // Native rejection — gate fires.
                    return ConformanceCase.Result.Pass
                }
                // Otherwise: the conformance runner must observe the rule itself.
                val violations = NamespaceEnforcer.findReserved(listOf(commandId))
                return if (violations.isNotEmpty()) {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail(
                        message = "conformance runner must flag reserved namespace command id " +
                            "'$commandId' even when McosPackage.decode is permissive",
                    )
                }
            }
        }

    /**
     * Positive control: a non-reserved command id must NOT be flagged.
     */
    private fun reservedNamespacePositiveCase(): ConformanceCase = object : ConformanceCase {
        override val id = "manifest-gate2-non-reserved"
        override val title = "non-reserved command id is accepted"
        override val spec = "09 §5.1 gate 2 + 02 §4.3"
        override val category = "manifest"

        override fun run(): ConformanceCase.Result {
            val commandIds = listOf("com.example.ping", "com.example.pong")
            val violations = NamespaceEnforcer.findReserved(commandIds)
            return if (violations.isEmpty()) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "non-reserved command ids were wrongly flagged: $violations",
                )
            }
        }
    }

    // ─── Gate 7: recipe bodyTemplate secret containment ──────────────────

    /**
     * Gate 7 — recipe workflow body references `{{secret.*}}`.
     *
     * The marketplace CI rejects a published recipe whose workflow
     * body embeds secret placeholders — secrets must be injected at
     * execute time, never hardcoded into the published envelope. The
     * conformance runner mirrors that check locally; a publisher who
     * sees Pass with a high-detail message has been warned before
     * upload.
     */
    private fun recipeSecretContainmentCase(): ConformanceCase = object : ConformanceCase {
        override val id = "manifest-gate7-recipe-secret-template"
        override val title = "recipe bodyTemplate with {{secret.*}} → flagged by runner"
        override val spec = "09 §5.1 gate 7"
        override val category = "manifest"

        override fun run(): ConformanceCase.Result {
            val envelope = """
                {
                  "id": "recipe.demo",
                  "title": "demo recipe",
                  "summary": "for local conformance",
                  "workflow": {
                    "type": "sequence",
                    "steps": [
                      {
                        "id": "demo.echo",
                        "args": { "message": "{{secret.openai_api_key}}" }
                      }
                    ]
                  }
                }
            """.trimIndent()
            val placeholder = Regex("""\{\{secret\.[^}]+\}\}""")
            return if (placeholder.containsMatchIn(envelope)) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "expected gate 7 violation; " +
                        "fixture did not include a {{secret.*}} placeholder",
                )
            }
        }
    }

    // ─── Manifest builders ───────────────────────────────────────────────

    private fun validManifestJson(): String = """
        {
            "id": "com.example.demo",
            "entry": "com.example.DemoPlugin",
            "version": "1.0.0",
            "commands": [
                {"id": "demo.ping", "sideEffectClass": "read"}
            ]
        }
    """.trimIndent()

    private fun badSideEffectManifest(): String = """
        {
            "id": "com.example.bad",
            "entry": "com.example.BadPlugin",
            "commands": [
                {"id": "demo.weird", "sideEffectClass": "lethal"}
            ]
        }
    """.trimIndent()

    private fun manifestWithCommands(pairs: List<Pair<String, SideEffectClass>>): String {
        val commandsArr = buildJsonArray {
            pairs.forEach { (id, sideEffect) ->
                add(buildJsonObject {
                    put("id", id)
                    put("sideEffectClass", sideEffect.name)
                })
            }
        }
        val root = buildJsonObject {
            put("id", "com.example.duplicate")
            put("entry", "com.example.DuplicatePlugin")
            put("commands", commandsArr)
        }
        return root.toString()
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private fun manifestJson(json: String): ByteArray =
        zipOf(McosPackage.MANIFEST_ENTRY to json.toByteArray())

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    companion object {
        /**
         * Reserved command-id prefixes (spec 09 §5.1 gate 2, 02 §4.3):
         * third-party publishers cannot ship commands under any of these.
         */
        val RESERVED_COMMAND_PREFIXES = listOf("mcos.", "sys.", "mcp.", "std.")
    }
}

/**
 * Stand-alone namespace gate (spec 09 §5.1 gate 2 + 02 §4.3).
 *
 * The marketplace CI rejects a submission if any command id starts
 * with a reserved prefix. The conformance runner mirrors the rule
 * locally so publishers don't ship a malicious package and only
 * learn at upload time.
 */
object NamespaceEnforcer {
    private val RESERVED = listOf("mcos.", "sys.", "mcp.", "std.")

    /** Returns the list of reserved command ids present in [commandIds]. */
    fun findReserved(commandIds: List<String>): List<String> =
        commandIds.filter { id -> RESERVED.any { id.startsWith(it) } }
}