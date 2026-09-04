package com.morainet.mcos.indexserver

import kotlinx.serialization.json.Json

/**
 * Json instances for the index server.
 *
 * - [IndexJson.document] — registry persistence, lossless (explicit nulls on,
 *   defaults encoded) so documents round-trip byte-stable.
 * - [IndexJson.api] — HTTP payload codec; ignores unknown keys (forward
 *   compatibility with newer clients) and omits nulls (matches what
 *   MarketplaceIndex produces).
 *
 * Kotlinx `Json` instances are thread-safe and immutable.
 */
object IndexJson {
    val document: Json = Json {
        prettyPrint = true
        explicitNulls = true
        encodeDefaults = true
    }

    val api: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
