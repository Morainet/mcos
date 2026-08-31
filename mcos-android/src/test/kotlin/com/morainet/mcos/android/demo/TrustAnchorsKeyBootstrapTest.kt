package com.morainet.mcos.android.demo

import androidx.lifecycle.viewModelScope
import com.morainet.mcos.android.TrustAnchors
import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.KeyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * §6.3 key bootstrap + revocation. [TrustAnchors] seeds the store on a cold
 * start; [MarketplaceViewModel.refreshKeyTrust] pulls `/v1/keys/revoked` and
 * marks matching keys REVOKED. Pure JVM, same scaffolding as
 * [MarketplaceViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrustAnchorsKeyBootstrapTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val mainRule = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(mainDispatcher)
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    private lateinit var vm: MarketplaceViewModel

    @get:Rule
    val rule = mainRule

    @Before
    fun setUp() {
        vm = MarketplaceViewModel()
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
    }

    // ── bundled trust anchors ───────────────────────────────────────────

    @Test
    fun marketplaceAnchorDecodesAndBlocklistVerifierIsFailClosed() {
        val store = InMemoryPublisherKeyStore().apply { bootstrap(TrustAnchors.bundled()) }
        // The anchor is present and decodes to a real public key (no throw).
        assertNotNull(store.get(TrustAnchors.marketplaceKey.keyId))
        assertNotNull(store.publicKey(TrustAnchors.marketplaceKey.keyId))

        // BlocklistVerifier constructs and fails closed — never throws — for a
        // bogus or missing signature (the placeholder anchor has no live signer).
        val verifier = BlocklistVerifier(TrustAnchors.marketplaceKey)
        assertFalse(verifier.verify("doc".encodeToByteArray(), "not-a-valid-signature"))
        assertFalse(verifier.verify("doc".encodeToByteArray(), null))
    }

    @Test
    fun bootstrapIsIdempotentAndPreservesInstallPinnedKeys() {
        val pinned = TestMarketplace.pubKey(TestMarketplace.keyPair(), keyId = "pinned_key")
        val store = InMemoryPublisherKeyStore().apply {
            put(pinned)
            bootstrap(TrustAnchors.bundled())
            bootstrap(TrustAnchors.bundled()) // second call is a no-op
        }
        assertNotNull("install-pinned key preserved", store.get("pinned_key"))
        assertNotNull("anchor added", store.get(TrustAnchors.marketplaceKey.keyId))
    }

    // ── revocation refresh (§6.3) ───────────────────────────────────────

    @Test
    fun refreshKeyTrustMarksRevokedKeyInSharedStore() = runBlocking {
        val pair = TestMarketplace.keyPair()
        // deps() seeds the store with pubKey(pair) → keyId "key_2026_01" ACTIVE.
        val revoked = TestMarketplace.pubKey(pair) // same keyId
        val transport = TestMarketplace.FakeIndexTransport(
            revokedBody = TestMarketplace.revokedKeysJson(revoked),
        )
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)
        assertEquals(KeyStatus.ACTIVE, deps.marketplace.keyStore.get("key_2026_01")!!.status)

        vm.onBaseUrlChange("http://idx.test")
        vm.refreshKeyTrust()

        withTimeout(5_000) {
            while (deps.marketplace.keyStore.get("key_2026_01")?.status != KeyStatus.REVOKED) delay(10)
        }
        assertEquals(KeyStatus.REVOKED, deps.marketplace.keyStore.get("key_2026_01")!!.status)
        assertTrue(transport.getJsonUrls.any { it.contains("/v1/keys/revoked") })
    }

    @Test
    fun refreshKeyTrustWithoutBaseUrlIsNoOp() {
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        // No base URL set → returns immediately, no crash, key untouched.
        vm.refreshKeyTrust()
        assertEquals(KeyStatus.ACTIVE, deps.marketplace.keyStore.get("key_2026_01")!!.status)
    }
}
