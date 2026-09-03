package com.morainet.mcos.android.host.isolation

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.CompositionRoot
import com.morainet.mcos.marketplace.ArtifactRef
import com.morainet.mcos.marketplace.InstallResult
import com.morainet.mcos.marketplace.PackageMetadata
import com.morainet.mcos.plugin.devicefixture.DeviceIsolatedPlugin
import com.morainet.mcos.runtime.core.registry.ResolveResult
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PublisherKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the full Binder isolation chain (08-security.md
 * §8, item 50) — the "deliberately the ONLY untested layer" left open since
 * items 36-45 built the chain JVM-side.
 *
 * Every production component is real here: [CompositionRoot.create] with
 * `processIsolation = true` wires the true [BinderIsolationHost] (real
 * `bindService`, real Parcel framing, real `linkToDeath`), the true
 * DirectorySandbox under `filesDir/plugin-sandbox`, the file-backed HMAC
 * signer, and the production [com.morainet.mcos.marketplace.PluginInstaller].
 * The test only supplies two honest inputs a host would get from its
 * marketplace backend:
 *  1. a publisher key — whichever of the two algorithms the production
 *     verifier accepts ("Ed25519" and "RSA-PSS-4096" are its only labels)
 *     this device's JCA providers actually expose, probed at runtime: OEM
 *     Android 10 builds vary (this suite has already met one exposing
 *     neither "RSASSA-PSS" nor "Ed25519" under those exact names);
 *  2. the artifact bytes — served over loopback HTTP by an in-test
 *     [ServerSocket] (Android has no com.sun.net.httpserver).
 *
 * The plugin itself is the `plugins:mcos-plugin-devicefixture` module,
 * dexed by the `deviceFixtureDex` gradle task into the androidTest assets
 * and packed into a signed `.mcos` here, so the whole chain — download →
 * Ed25519-or-RSA signature verify → staging → manifest-only registration →
 * `:mcos_plugin` bind → dex load → Binder invoke → sandbox facade hop →
 * death → rebind — runs against production code end to end.
 *
 * Honest boundaries (also recorded in docs/11 §item 50):
 *  - **Same-UID only.** The plugin process is this app's own `:mcos_plugin`
 *    split, so `getCallingUid` identity checks are exercised as "same-uid
 *    passes". Rejecting a FOREIGN uid needs a second APK and stays covered
 *    by the JVM `BinderIdentityPolicyTest` oracle.
 *  - **Fixture on the instrumentation classpath.** androidTest declares the
 *    fixture module, so the main process CAN resolve the class via the test
 *    classloader; what this suite proves is that execution, sandbox writes
 *    and crash isolation happen in the SEPARATE plugin process. Dex
 *    exclusivity in MAIN is enforced by the manifest-only registration path
 *    (never instantiating plugin code in-process), not re-asserted here.
 *  - **Single device.** Verified on one Android 10 (API 29) handset; no CI
 *    emulator gate — the CI android-build job only compiles this suite
 *    (`assembleDebugAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class BinderIsolationDeviceTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val echoId = "${DeviceIsolatedPlugin.ID}.echo"
    private val parkId = "${DeviceIsolatedPlugin.ID}.park"
    private val markerFile: File = File(
        File(context.filesDir, "plugin-sandbox/${DeviceIsolatedPlugin.ID}"),
        DeviceIsolatedPlugin.PARK_MARKER,
    )
    private val stagedArtifact: File =
        File(context.filesDir, "marketplace/${DeviceIsolatedPlugin.ID}-1.0.0.mcos")

    private lateinit var deps: AppDeps
    private lateinit var server: ServerSocket
    private lateinit var install: InstallResult

    @Before
    fun setUp() = runBlocking {
        // Fresh production harness per test: each method re-runs the full
        // install chain, so kill/truncate side-effects from one method can
        // never leak into another (re-install of the same version idempotently
        // re-downloads and re-stages).
        deps = CompositionRoot.create(
            context,
            builtIns = emptyList(),
            processIsolation = true,
        )
        val url = startArtifactServer()
        deps.marketplace.keyStore.put(publisherKey(artifact.keyPair))
        install = deps.marketplace.installer.installPackage(
            metadata(url),
            // Manifest-only registration (item 45): under processIsolation the
            // LOADING step must decode plugin.json and NEVER instantiate
            // plugin code in this process — reaching the factory is a failure
            // of exactly the boundary under test.
            pluginFactory = { error("plugin factory must not run in the host process") },
        )
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { deps.runtime.shutdown() } }
        runCatching { server.close() }
    }

    // ─── BD1 — production install chain, manifest-only ────────────────────

    @Test
    fun BD1_installRegistersManifestOnlyWithoutPluginProcess() {
        val installed = install as? InstallResult.Installed
            ?: error("install failed: $install")
        assertEquals(DeviceIsolatedPlugin.ID, installed.packageId)
        assertEquals("1.0.0", installed.version)
        assertEquals("both fixture commands registered from plugin.json", 2, installed.commandsRegistered)

        assertTrue(
            "echo descriptor resolvable from the manifest-only registration",
            deps.registry.resolve(echoId) is ResolveResult.Found,
        )
        assertTrue(
            "park descriptor resolvable from the manifest-only registration",
            deps.registry.resolve(parkId) is ResolveResult.Found,
        )
        assertEquals(
            "no plugin process before the first invocation — plugin code is never started by install",
            null,
            pluginProcessPid(),
        )
    }

    // ─── BD2 — process split + real Binder round-trip ──────────────────────

    @Test
    fun BD2_echoExecutesInMcosPluginProcess() = runBlocking {
        val events = runDsl("""$echoId(message="hi")""")

        assertTrue("echo run succeeds: ${events.lastOrNull()}", events.any { it is RuntimeEvent.RunSucceeded })
        assertTrue(
            "echo step succeeded",
            events.filterIsInstance<RuntimeEvent.StepSucceeded>().any { it.commandId == echoId },
        )
        val pluginPid = pluginProcessPid()
        assertNotNull("':mcos_plugin' process exists after the first invoke", pluginPid)
        assertTrue(
            "plugin process pid differs from the host pid",
            pluginPid != android.os.Process.myPid(),
        )
    }

    // ─── BD3 — real facade namespaced sandbox write ────────────────────────

    @Test
    fun BD3_parkMarkerLandsInNamespacedSandboxOnRealDisk() = runBlocking {
        markerFile.delete()

        val events = runDsl("""$parkId(seconds=0)""")

        assertTrue("park run succeeds", events.any { it is RuntimeEvent.RunSucceeded })
        assertTrue("marker written on disk: ${markerFile.path}", markerFile.exists())
        val markerPid = MARKER_PID.find(markerFile.readText())?.value?.toIntOrNull()
        assertNotNull("marker carries the handler's pid", markerPid)
        assertEquals("marker pid is the plugin process pid", pluginProcessPid(), markerPid)
        assertTrue(
            "sandbox write happened in the plugin process, not the host",
            markerPid != android.os.Process.myPid(),
        )
    }

    // ─── BD4 — crash isolation: kill mid-run ───────────────────────────────

    @Test
    fun BD4_killingPluginProcessMidRunFailsOnlyThatRun() = runBlocking {
        markerFile.delete()

        val deferred = async(Dispatchers.IO) { runDsl("""$parkId(seconds=30)""") }
        // The marker is the deterministic sync point: it exists only once the
        // plugin process is provably INSIDE the park handler (the facade hop
        // has already crossed Binder twice).
        val inRunPid = withTimeout(BIND_TIMEOUT_MS) { awaitMarkerPid() }
        assertEquals("marker pid is the live plugin process", pluginProcessPid(), inRunPid)

        android.os.Process.killProcess(inRunPid!!)

        val events = withTimeout(RUN_TIMEOUT_MS) { deferred.await() }
        val failure = events.filterIsInstance<RuntimeEvent.RunFailed>().singleOrNull()
        assertNotNull("run fails honestly after the plugin process dies: $events", failure)
        assertTrue(
            "failure surfaces as the transport failure, got: ${failure?.error}",
            failure?.error?.contains("Isolated process call failed") == true,
        )

        // The host (this instrumentation process) survived, and a fresh run
        // through a rebound process succeeds again.
        val echo = runDsl("""$echoId(message="after-crash")""")
        assertTrue("host alive and serving after the crash", echo.any { it is RuntimeEvent.RunSucceeded })
    }

    // ─── BD5 — transparent rebind after death ──────────────────────────────

    @Test
    fun BD5_nextInvokeAfterDeathTransparentlyRebinds() = runBlocking {
        assertTrue(runDsl("""$echoId(message="one")""").any { it is RuntimeEvent.RunSucceeded })
        val firstPid = pluginProcessPid()
        assertNotNull("plugin process bound after first invoke", firstPid)

        android.os.Process.killProcess(firstPid!!)
        withTimeout(BIND_TIMEOUT_MS) { awaitPidGone(firstPid) }

        val events = runDsl("""$echoId(message="two")""")
        assertTrue(
            "the invoke after death succeeds through a transparent rebind: ${events.lastOrNull()}",
            events.any { it is RuntimeEvent.RunSucceeded },
        )
        val reboundPid = pluginProcessPid()
        assertNotNull("a plugin process serves again", reboundPid)
        assertTrue(
            "the rebound process is a NEW process ($firstPid -> $reboundPid)",
            reboundPid != firstPid,
        )
    }

    // ─── BD6 — honest failure on a corrupt staged artifact ─────────────────

    @Test
    fun BD6_truncatedStagedArtifactReportsPluginLoadFailure() = runBlocking {
        assertTrue(runDsl("""$echoId(message="prime")""").any { it is RuntimeEvent.RunSucceeded })
        val boundPid = pluginProcessPid()
        assertNotNull(boundPid)

        val originalBytes = stagedArtifact.readBytes()
        try {
            // Force the next invoke onto a FRESH bind (the live process holds
            // the already-loaded plugin), then corrupt the staged file the
            // resolver hands the new process.
            android.os.Process.killProcess(boundPid!!)
            withTimeout(BIND_TIMEOUT_MS) { awaitPidGone(boundPid) }
            stagedArtifact.writeBytes(originalBytes.copyOf(10))

            val events = runDsl("""$echoId(message="corrupt")""")
            val failure = events.filterIsInstance<RuntimeEvent.RunFailed>().singleOrNull()
            assertNotNull("run fails instead of serving stale code: $events", failure)
            assertTrue(
                "failure names the plugin-process load failure, got: ${failure?.error}",
                failure?.error?.contains("failed to load in the plugin process") == true,
            )
        } finally {
            stagedArtifact.writeBytes(originalBytes)
        }
    }

    // ─── Harness ───────────────────────────────────────────────────────────

    /**
     * The signed `.mcos` and its signing key, built once per process: the
     * bytes and signature are port-independent (the loopback URL lives only
     * in the metadata), and keygen is worth not repeating six times on a
     * 2019 handset.
     */
    private class FixtureArtifact(
        val bytes: ByteArray,
        val keyPair: KeyPair,
        val algorithm: String,
        val signatureBase64: String,
        val sha256Hex: String,
    )

    private val artifact: FixtureArtifact by lazy {
        val dex = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("device-fixture.dex").use { it.readBytes() }
        val bytes = buildMcos(dex)
        val algorithm = supportedSignatureAlgorithm()
        val pair = keyPairFor(algorithm)
        val signer = when (algorithm) {
            ALGO_ED25519 -> Signature.getInstance("Ed25519")
            else -> rsaPssSignature()
        }.apply {
            initSign(pair.private)
            update(bytes)
        }
        FixtureArtifact(
            bytes = bytes,
            keyPair = pair,
            algorithm = algorithm,
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()),
            sha256Hex = sha256Hex(bytes),
        )
    }

    /**
     * The production verifier accepts exactly two algorithm labels; each
     * label resolves on this device through the same JCA names the verifier
     * uses (signing and verification share them). Probe what this OEM build
     * actually exposes; if nothing, fail honestly with the provider dump so
     * the gap is diagnosable straight from the test report.
     */
    private fun supportedSignatureAlgorithm(): String {
        val ed25519Available = runCatching {
            KeyPairGenerator.getInstance("Ed25519")
            Signature.getInstance("Ed25519")
        }.isSuccess
        if (ed25519Available) return ALGO_ED25519

        if (runCatching { rsaPssSignature() }.isSuccess) return ALGO_RSA_PSS

        val pssServices = java.security.Security.getProviders()
            .flatMap { provider ->
                provider.services
                    .filter { it.type.equals("Signature", ignoreCase = true) }
                    .map { "${provider.name}: ${it.algorithm}" }
            }
            .filter { it.contains("PSS", true) || it.contains("Ed25519", true) }
        error(
            "device exposes neither Ed25519 nor any RSA-PSS JCA name — the " +
                "production artifact verifier cannot run here. PSS/Ed25519 " +
                "Signature services: ${pssServices.ifEmpty { listOf("(none)") }}",
        )
    }

    /**
     * Mirrors the production verifier's PSS construction under either JCA
     * name (generic `RSASSA-PSS`, or the digest-pinned `SHA256withRSA/PSS`
     * some OEM Android 10 builds register instead) — the two interoperate.
     */
    private fun rsaPssSignature(): Signature {
        val instance = try {
            Signature.getInstance("RSASSA-PSS")
        } catch (_: java.security.NoSuchAlgorithmException) {
            Signature.getInstance("SHA256withRSA/PSS")
        }
        return instance.apply {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        }
    }

    private fun keyPairFor(algorithm: String): KeyPair =
        if (algorithm == ALGO_ED25519) {
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        } else {
            KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair()
        }

    /** Serves [artifact] bytes on 127.0.0.1 — one plain HTTP response per request. */
    private fun startArtifactServer(): String {
        server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "mcos-fixture-http") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    client.use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        // Drain the request head (request line + headers) byte-wise
                        // WITHOUT closing the stream — closing a socket stream can
                        // tear down the whole socket on some OEM stacks, killing the
                        // response write. A GET has no body, so the head is all
                        // there is until our response.
                        var last4 = 0
                        var drained = 0
                        while (drained < MAX_HEADER_BYTES) {
                            val b = input.read()
                            if (b < 0) break
                            drained++
                            last4 = (last4 shl 8) or b
                            if (last4 == CRLFCRLF) break
                        }
                        val output = socket.getOutputStream()
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${artifact.bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        output.write(artifact.bytes)
                        output.flush()
                    }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/device-fixture.mcos"
    }

    private fun buildMcos(dex: ByteArray): ByteArray = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(pluginJson().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex)
            zip.closeEntry()
        }
        buffer.toByteArray()
    }

    /** Mirrors [DeviceIsolatedPlugin.manifest] — the manifest-only source of truth on device. */
    private fun pluginJson(): String = """
        {
          "id": "${DeviceIsolatedPlugin.ID}",
          "entry": "com.morainet.mcos.plugin.devicefixture.DeviceIsolatedPlugin",
          "version": "1.0.0",
          "name": "Device Isolation Fixture",
          "minRuntimeVersion": "0.1.0",
          "description": "On-device Binder isolation verification fixture - never ship",
          "commands": [
            {
              "id": "$echoId",
              "version": "1.0.0",
              "title": "Echo",
              "description": "Echo the message back with the handler's pid",
              "sideEffectClass": "read",
              "inputSchema": {
                "type": "object",
                "properties": { "message": { "type": "string" } }
              }
            },
            {
              "id": "$parkId",
              "version": "1.0.0",
              "title": "Park",
              "description": "Write the sandbox entry marker, then sleep",
              "sideEffectClass": "read",
              "inputSchema": {
                "type": "object",
                "properties": { "seconds": { "type": "number" } }
              }
            }
          ]
        }
    """.trimIndent()

    private fun publisherKey(pair: KeyPair): PublisherKey = PublisherKey(
        keyId = KEY_ID,
        publisherId = PUBLISHER_ID,
        publicKeyFingerprint = "ff".repeat(32),
        algorithm = artifact.algorithm,
        publicKeyEncoded = Base64.getEncoder().encodeToString(pair.public.encoded),
        createdAt = "2026-09-01T00:00:00Z",
        status = KeyStatus.ACTIVE,
    )

    private fun metadata(url: String): PackageMetadata = PackageMetadata(
        packageId = DeviceIsolatedPlugin.ID,
        name = "Device Isolation Fixture",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        publisherId = PUBLISHER_ID,
        publisherName = "MCOS Device Verification",
        summary = "On-device Binder isolation verification fixture",
        artifact = ArtifactRef(
            url = url,
            sha256 = artifact.sha256Hex,
            signature = artifact.signatureBase64,
            signingKeyId = KEY_ID,
        ),
        publishedAt = "2026-09-01T00:00:00Z",
        updatedAt = "2026-09-01T00:00:00Z",
    )

    /** Execute DSL through the production runtime; returns the run's events, terminal included. */
    private suspend fun runDsl(dsl: String): List<RuntimeEvent> {
        val handle = deps.runtime.execute(
            ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(dsl)),
        )
        return withTimeout(RUN_TIMEOUT_MS) { deps.runtime.observe(handle.runId).toList() }
    }

    /** The pid of this app's `:mcos_plugin` split process, or null when not running. */
    private fun pluginProcessPid(): Int? =
        context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.firstOrNull { it.processName == context.packageName + PROCESS_SUFFIX }
            ?.pid

    private suspend fun awaitMarkerPid(): Int? {
        while (true) {
            val pid = markerFile.takeIf { it.exists() }
                ?.let { MARKER_PID.find(it.readText())?.value?.toIntOrNull() }
            if (pid != null) return pid
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun awaitPidGone(pid: Int) {
        while (pluginProcessPid() == pid) delay(POLL_INTERVAL_MS)
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_ID = "key_device_fixture"
        const val PUBLISHER_ID = "pub_device_fixture"

        /** The production verifier's two accepted algorithm labels (probed at runtime). */
        const val ALGO_ED25519 = "Ed25519"
        const val ALGO_RSA_PSS = "RSA-PSS-4096"
        const val RSA_BITS = 4096

        const val PROCESS_SUFFIX = ":mcos_plugin"
        val MARKER_PID = Regex("""pid=(\d+)""")

        /** Request-head terminator (`\r\n\r\n`) and drain cap for the fixture server. */
        const val CRLFCRLF = 0x0D0A0D0A
        const val MAX_HEADER_BYTES = 16 * 1024

        const val BACKLOG = 4
        const val POLL_INTERVAL_MS = 200L

        /** First-bind budget (matches BinderIsolationHost's own 10s bind timeout + slack). */
        const val BIND_TIMEOUT_MS = 20_000L

        /** Full-run budget; must exceed the manifest's 60s command timeout. */
        const val RUN_TIMEOUT_MS = 90_000L
    }
}
