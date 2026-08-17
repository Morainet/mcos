package com.mcos.runtime.api

import com.mcos.sdk.MemoryFacade
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal [com.mcos.sdk.HostServices] stub for tests and default construction.
 * Real Android host should provide a full implementation.
 */
class StubHostServices(
    override val memory: MemoryFacade,
) : com.mcos.sdk.HostServices {
    private val stubSecureStore = object : com.mcos.sdk.SecureStore {
        private val entries = ConcurrentHashMap<String, String>()
        override suspend fun get(key: String): String? = entries[key]
        override suspend fun put(key: String, value: String) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
    }

    override val files: com.mcos.sdk.FileService get() = error("FileService not available in stub")
    override val net: com.mcos.sdk.NetService get() = error("NetService not available in stub")
    override val ui: com.mcos.sdk.UiService get() = error("UiService not available in stub")
    override val secureStore: com.mcos.sdk.SecureStore get() = stubSecureStore
    override val clock: com.mcos.sdk.Clock get() = object : com.mcos.sdk.Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: com.mcos.sdk.JsonService get() = error("JsonService not available in stub")
}
