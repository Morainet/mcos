package com.morainet.mcos.conformance.fixture

import java.io.File

/**
 * One discovered conformance fixture on disk.
 *
 * A fixture is a directory under [root] with:
 *  - `input.dsl` — always present;
 *  - `expected.ir.json` for positive cases (must round-trip exactly), OR
 *  - `expected.error.json` for negative cases (must reject with the listed
 *    error envelope).
 *
 * The single source of truth for fixture layout is
 * `docs/fixtures/README.md` — any change there is a wire change mirrored
 * by this loader.
 */
data class Fixture(
    val caseId: String,
    val type: FixtureType,
    val inputPath: File,
    val expectedPath: File,
)

enum class FixtureType { POSITIVE, NEGATIVE }

/**
 * Discovers fixtures under a directory tree.
 *
 * Discovery is breadth-first (no recursion into non-fixture directories) —
 * the layout is flat, and an accidental deep file (e.g. an editor swap
 * file dropped under a fixture directory) would otherwise pollute the
 * case list.
 */
object FixtureDiscovery {

    /** Default location of the golden DSL fixtures, relative to CWD. */
    const val DEFAULT_ROOT = "docs/fixtures"

    /**
     * Discover every fixture directly under [root]. Returns the empty
     * list if [root] does not exist or is not a directory — the caller
     * decides whether to surface that as a `Skip` per case or a hard IO
     * error.
     */
    fun discover(root: File = File(DEFAULT_ROOT)): List<Fixture> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.mapNotNull { dir ->
            val caseId = dir.name
            val input = File(dir, "input.dsl")
            if (!input.isFile) return@mapNotNull null
            val irExpected = File(dir, "expected.ir.json")
            val errExpected = File(dir, "expected.error.json")
            when {
                irExpected.isFile -> Fixture(caseId, FixtureType.POSITIVE, input, irExpected)
                errExpected.isFile -> Fixture(caseId, FixtureType.NEGATIVE, input, errExpected)
                else -> null
            }
        } ?: emptyList()
    }
}