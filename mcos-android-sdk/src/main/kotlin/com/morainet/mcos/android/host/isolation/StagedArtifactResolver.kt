package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.marketplace.InstallState
import com.morainet.mcos.marketplace.InstallRecordStore
import com.morainet.mcos.marketplace.PersistedInstallRecord
import java.io.File

/**
 * Resolves the staged `.mcos` artifact for a plugin id from the persisted
 * install records (isolation slice 3b-final activation): [BinderIsolationHost]
 * needs the artifact path to hand the plugin process at bind time — the
 * install pipeline staged (and signature-verified) it under the installer's
 * download dir at install.
 *
 * Only [InstallState.INSTALLED] records resolve: a DISABLED plugin is not
 * registered with the runtime, so it never dispatches — refusing here is
 * defense in depth. A resolved-but-missing file is deliberately NOT
 * filtered out: the plugin process reports the honest `plugin_load_failed`
 * with the actual IO reason, which beats a generic bind failure.
 */
object StagedArtifactResolver {

    /**
     * @param records the persisted install records
     *        ([InstallRecordStore.load] — tamper-evident, fail-closed).
     * @param downloadDir the installer's download dir (records hold the
     *        artifact file name relative to it).
     * @param pluginId the plugin the isolation host wants to bind.
     * @return the staged artifact file, or null when no INSTALLED record
     *         exists for the plugin.
     */
    fun resolve(records: List<PersistedInstallRecord>, downloadDir: File, pluginId: String): File? {
        val record = records.firstOrNull {
            it.packageId == pluginId && it.state == InstallState.INSTALLED.name
        } ?: return null
        return File(downloadDir, record.artifactFileName)
    }
}
