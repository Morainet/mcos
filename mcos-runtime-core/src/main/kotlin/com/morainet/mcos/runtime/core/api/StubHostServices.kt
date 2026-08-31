package com.morainet.mcos.runtime.core.api

import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.UiService
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal [com.morainet.mcos.sdk.HostServices] stub for tests and default construction.
 * Real Android host should provide a full implementation.
 */
class StubHostServices(
    override val memory: MemoryFacade,
) : HostServices {
    private val stubSecureStore = object : SecureStore {
        private val entries = ConcurrentHashMap<String, ByteArray>()
        override suspend fun get(key: String): ByteArray? = entries[key]
        override suspend fun put(key: String, value: ByteArray) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
        override suspend fun keys(): Set<String> = entries.keys.toSet()
    }

    override val files: FileService get() = error("FileService not available in stub")
    override val net: NetService get() = error("NetService not available in stub")
    override val ui: UiService get() = error("UiService not available in stub")
    override val secureStore: SecureStore get() = stubSecureStore
    override val clock: Clock get() = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        override fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    }
    override val json: JsonService get() = error("JsonService not available in stub")
}
