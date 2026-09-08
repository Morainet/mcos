package com.morainet.mcos.sdk

/**
 * Files the **user explicitly grants** to a plugin through the host's system
 * picker (04-plugin-sdk.md 6.1) — the out-of-sandbox surface beside
 * [SandboxFileService]. Every grant is a user decision made in system UI:
 * the host opens the picker, and only the chosen document is readable. No
 * path ever crosses to the plugin — see the per-plugin token scoping the
 * runtime layers over this capability.
 *
 * The capability is optional ([HostServices.userFiles]): a host without a
 * picker (plain JVM, headless run) leaves it null and plugins must surface
 * `UNAVAILABLE`, never fake success. Grants are **session-scoped** — they
 * live as long as the runtime process and a plugin re-picks after a host
 * restart. The picker dialog itself is the user-consent moment.
 */
interface UserFileGrantService {

    /**
     * Open the system picker and suspend until the user picks a document
     * or cancels.
     *
     * @param mimeTypes filter for the picker, e.g. `["text/plain"]`;
     *   the all-types default shows everything openable.
     * @return the grant, or null when the user cancelled.
     * @throws McosException `UNAVAILABLE` when no picker can launch
     *   (no Activity registered — e.g. a headless schedule run).
     */
    suspend fun pickForRead(mimeTypes: List<String> = listOf("*/*")): UserFileGrant?

    /**
     * Stat a granted file.
     * @return the entry, or null when the ref is unknown/revoked.
     */
    suspend fun statGranted(ref: String): UserFileGrant?

    /**
     * Read a granted file's bytes.
     * @return the bytes, or null when the ref is unknown/revoked or the
     *   file can no longer be opened.
     */
    suspend fun readGranted(ref: String): ByteArray?

    /**
     * Drop a grant. Session grants need no OS-level release, so this only
     * invalidates the ref on the host side.
     * @return true when a grant was dropped, false when the ref was unknown.
     */
    suspend fun releaseGranted(ref: String): Boolean
}

/**
 * One user-granted file (04-plugin-sdk.md 6.1). The plugin-facing [ref] is
 * minted by the runtime — an opaque token, never a raw content URI, so one
 * plugin cannot redeem another's grant.
 */
data class UserFileGrant(
    val ref: String,
    val name: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
)

/**
 * JVM reference [UserFileGrantService] — the seeding analogue of
 * [DirectorySandbox] for the sandbox: tests (and JVM hosts) pre-populate
 * what "the user" would pick and drive the exact code paths the Android
 * host runs.
 *
 * - `pickForRead` pops the next seeded grant (FIFO); an empty queue is the
 *   user cancelling — null, no error.
 * - `readGranted`/`statGranted` resolve against the seeded contents.
 * - `releaseGranted` removes; a second release is false.
 */
class StaticUserFileGrantService : UserFileGrantService {

    private val picks = ArrayDeque<UserFileGrant>()
    private val contents = LinkedHashMap<String, ByteArray>()

    /** Seed what the user will pick next (later calls pick first). */
    fun seed(grant: UserFileGrant, content: ByteArray = ByteArray(0)) {
        picks.addLast(grant)
        contents[grant.ref] = content
    }

    override suspend fun pickForRead(mimeTypes: List<String>): UserFileGrant? {
        val next = picks.removeFirstOrNull() ?: return null
        // The caller's filter is honoured the way a real picker would: a
        // seeded grant outside the filter stays unpicked (treated as cancel
        // — the user could not choose it).
        if (mimeTypes.any { it == "*/*" || it == next.mimeType || next.mimeType == null }) {
            return next
        }
        return null
    }

    override suspend fun statGranted(ref: String): UserFileGrant? {
        val bytes = contents[ref] ?: return null
        return UserFileGrant(ref = ref, sizeBytes = bytes.size.toLong())
    }

    override suspend fun readGranted(ref: String): ByteArray? = contents[ref]

    override suspend fun releaseGranted(ref: String): Boolean = contents.remove(ref) != null
}
