package com.morainet.mcos.runtime.core.registry

import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.sdk.*
import com.morainet.mcos.sdk.ResolveResult as SdkResolveResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for CommandRegistry v0.1.
 * Matches [03-runtime.md 6].
 */
class CommandRegistryTest {

    private lateinit var registry: CommandRegistry

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
    }

    @AfterTest
    fun tearDown() {
        registry.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // R1: Basic registration and resolution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R1-register single command and resolve`() {
        val plugin = createPlugin(
            id = "example.test",
            version = "1.0.0",
            commands = mapOf("test.hello" to EchoHandler("Hello"))
        )
        val result = registry.register(plugin)

        assertIs<RegisterResult.Ok>(result)
        assertEquals(1, result.commandsRegistered)

        val resolved = registry.resolve("test.hello")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals("test.hello", resolved.entry.descriptor.id)
        assertEquals("example.test", resolved.entry.descriptor.pluginId)
    }

    @Test
    fun `R2-register multiple commands from same plugin`() {
        val plugin = createPlugin(
            id = "example.multi",
            version = "1.0.0",
            commands = mapOf(
                "cmd.a" to EchoHandler("A"),
                "cmd.b" to EchoHandler("B"),
                "cmd.c" to EchoHandler("C")
            )
        )
        val result = registry.register(plugin)

        assertIs<RegisterResult.Ok>(result)
        assertEquals(3, result.commandsRegistered)

        assertIs<ResolveResult.Found>(registry.resolve("cmd.a"))
        assertIs<ResolveResult.Found>(registry.resolve("cmd.b"))
        assertIs<ResolveResult.Found>(registry.resolve("cmd.c"))
    }

    @Test
    fun `R3-case insensitive resolution`() {
        val plugin = createPlugin(
            id = "example.cs",
            version = "1.0.0",
            commands = mapOf("Camera.Capture" to EchoHandler(""))
        )
        registry.register(plugin)

        assertIs<ResolveResult.Found>(registry.resolve("camera.capture"))
        assertIs<ResolveResult.Found>(registry.resolve("Camera.Capture"))
        assertIs<ResolveResult.Found>(registry.resolve("CAMERA.CAPTURE"))
    }

    @Test
    fun `R4-resolve non-existent command returns NotFound`() {
        val result = registry.resolve("not.registered")
        assertIs<ResolveResult.NotFound>(result)
        assertEquals("not.registered", result.commandId)
    }

    // ═══════════════════════════════════════════════════════════════
    // R5-R7: Alias resolution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R5-resolve via alias`() {
        val plugin = createPluginWithAlias(
            id = "example.alias",
            version = "1.0.0",
            commandId = "sys.notify",
            alias = "notify",
            handler = EchoHandler("notified")
        )
        registry.register(plugin)

        // Resolve by alias
        val resolved = registry.resolve("notify")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals("sys.notify", resolved.entry.descriptor.id)
    }

    @Test
    fun `R6-alias case insensitive`() {
        val plugin = createPluginWithAlias(
            id = "example.cs2",
            version = "1.0.0",
            commandId = "sys.notify",
            alias = "Notify",
            handler = EchoHandler("")
        )
        registry.register(plugin)

        assertIs<ResolveResult.Found>(registry.resolve("notify"))
        assertIs<ResolveResult.Found>(registry.resolve("NOTIFY"))
    }

    @Test
    fun `R7-alias resolves to same entry as direct ID`() {
        val plugin = createPluginWithAlias(
            id = "example.same",
            version = "1.0.0",
            commandId = "sys.share",
            alias = "share",
            handler = EchoHandler("shared")
        )
        registry.register(plugin)

        val direct = registry.resolve("sys.share")
        val viaAlias = registry.resolve("share")

        assertIs<ResolveResult.Found>(direct)
        assertIs<ResolveResult.Found>(viaAlias)
        assertEquals(
            direct.entry.descriptor.id,
            viaAlias.entry.descriptor.id
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // R8-R9: Namespace queries
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R8-list commands by namespace`() {
        val plugin1 = createPlugin("example.ns1", "1.0.0", mapOf(
            "camera.capture" to EchoHandler(""),
            "camera.scan" to EchoHandler("")
        ))
        val plugin2 = createPlugin("example.ns2", "1.0.0", mapOf(
            "sys.notify" to EchoHandler(""),
            "sys.share" to EchoHandler("")
        ))
        registry.register(plugin1)
        registry.register(plugin2)

        val cameraCommands = registry.listByNamespace("camera")
        assertEquals(2, cameraCommands.size)
        assertTrue(cameraCommands.any { it.id == "camera.capture" })
        assertTrue(cameraCommands.any { it.id == "camera.scan" })

        val sysCommands = registry.listByNamespace("sys")
        assertEquals(2, sysCommands.size)
    }

    @Test
    fun `R9-list all namespaces`() {
        val plugin = createPlugin("example.nss", "1.0.0", mapOf(
            "camera.capture" to EchoHandler(""),
            "sys.notify" to EchoHandler(""),
            "file.list" to EchoHandler("")
        ))
        registry.register(plugin)

        val nss = registry.namespaces()
        assertEquals(setOf("camera", "sys", "file"), nss)
    }

    // ═══════════════════════════════════════════════════════════════
    // R10-R11: Version coexistence
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R10-multiple versions resolve to highest by default`() {
        val v1 = createPlugin("example.ver", "1.0.0", mapOf(
            "cmd.run" to EchoHandler("v1")
        ))
        val v2 = createPlugin("example.ver", "1.2.0", mapOf(
            "cmd.run" to EchoHandler("v2")
        ))
        registry.register(v1) // registered first
        registry.register(v2) // re-registered (same pluginId replaces)

        // After re-registration, v2 replaces v1 (same plugin)
        val resolved = registry.resolve("cmd.run")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals("1.2.0", resolved.entry.descriptor.version)
        assertEquals("example.ver", resolved.entry.descriptor.pluginId)
    }

    @Test
    fun `R11-version range resolution`() {
        val v1 = createPlugin("example.vr", "1.0.0", mapOf(
            "cmd.exec" to EchoHandler("v1")
        ))
        val v2 = createPlugin("example.vr", "2.0.0", mapOf(
            "cmd.exec" to EchoHandler("v2")
        ))
        registry.register(v1)
        registry.register(v2)

        // Resolve with minimum version constraint — v2 (same plugin, replaces)
        val resolved = registry.resolve("cmd.exec", "2.0.0")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals("2.0.0", resolved.entry.descriptor.version)
    }

    // ═══════════════════════════════════════════════════════════════
    // R12-R13: Plugin lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R12-unregister plugin removes its commands`() {
        val plugin = createPlugin("example.rm", "1.0.0", mapOf(
            "cmd.one" to EchoHandler(""),
            "cmd.two" to EchoHandler("")
        ))
        registry.register(plugin)

        assertEquals(2, registry.commandCount())

        val removed = registry.unregister("example.rm")
        assertEquals(2, removed)

        assertIs<ResolveResult.NotFound>(registry.resolve("cmd.one"))
        assertIs<ResolveResult.NotFound>(registry.resolve("cmd.two"))
        assertEquals(0, registry.commandCount())
    }

    @Test
    fun `R13-unregister one plugin does not affect another`() {
        val p1 = createPlugin("example.a", "1.0.0", mapOf(
            "cmd.a" to EchoHandler("")
        ))
        val p2 = createPlugin("example.b", "1.0.0", mapOf(
            "cmd.b" to EchoHandler("")
        ))
        registry.register(p1)
        registry.register(p2)

        registry.unregister("example.a")

        assertIs<ResolveResult.NotFound>(registry.resolve("cmd.a"))
        assertIs<ResolveResult.Found>(registry.resolve("cmd.b"))
    }

    // ═══════════════════════════════════════════════════════════════
    // R14-R15: Conflict handling
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R14-duplicate command ID from different plugins is a conflict`() {
        val p1 = createPlugin("plugin.first", "1.0.0", mapOf(
            "shared.command" to EchoHandler("first")
        ))
        val p2 = createPlugin("plugin.second", "1.0.0", mapOf(
            "shared.command" to EchoHandler("second")
        ))
        registry.register(p1)

        val result = registry.register(p2)
        assertIs<RegisterResult.Conflict>(result)
        assertEquals(1, result.conflicts.size)
        assertEquals("shared.command", result.conflicts.first().commandId)
        assertEquals("plugin.first", result.conflicts.first().existingPlugin)
        assertEquals("plugin.second", result.conflicts.first().incomingPlugin)
    }

    @Test
    fun `R15-first-to-load wins on conflict`() {
        val p1 = createPlugin("plugin.first", "1.0.0", mapOf(
            "winner.command" to EchoHandler("I win")
        ))
        val p2 = createPlugin("plugin.second", "1.0.0", mapOf(
            "winner.command" to EchoHandler("I lose")
        ))
        registry.register(p1)
        registry.register(p2) // conflict, p2's entry not registered

        val resolved = registry.resolve("winner.command")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals("plugin.first", resolved.entry.descriptor.pluginId)
    }

    // ═══════════════════════════════════════════════════════════════
    // R16-R17: Edge cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R16-plugin with commands from manifest entries`() {
        val plugin = createPluginWithManifest(
            pluginId = "example.manifest",
            pluginVersion = "2.0.0",
            entries = listOf(
                CommandManifestEntry(
                    id = "camera.capture",
                    version = "1.0.0",
                    title = "Capture Photo",
                    description = "Takes a photo using the camera",
                    sideEffectClass = SideEffectClass.write,
                    timeoutMs = 30000,
                    aliases = listOf("photo", "snap"),
                    examples = listOf("camera.capture flash=auto")
                )
            ),
            handlers = mapOf("camera.capture" to EchoHandler("captured"))
        )
        val result = registry.register(plugin)

        assertIs<RegisterResult.Ok>(result)
        assertEquals(1, result.commandsRegistered)
        assertEquals(2, result.aliasesRegistered)

        val resolved = registry.resolve("camera.capture")
        assertIs<ResolveResult.Found>(resolved)

        val desc = resolved.entry.descriptor
        assertEquals("camera.capture", desc.id)
        assertEquals("Capture Photo", desc.title)
        assertEquals(SideEffectClass.write, desc.sideEffectClass)
        assertEquals(30000, desc.timeoutMs)
        assertEquals(listOf("photo", "snap"), desc.aliases)
        assertEquals("example.manifest", desc.pluginId)

        // Aliases should work
        assertIs<ResolveResult.Found>(registry.resolve("photo"))
        assertIs<ResolveResult.Found>(registry.resolve("snap"))
    }

    @Test
    fun `R17-register empty plugin handles gracefully`() {
        val plugin = createPlugin("example.empty", "1.0.0", emptyMap())
        val result = registry.register(plugin)

        assertIs<RegisterResult.Ok>(result)
        assertEquals(0, result.commandsRegistered)
        assertEquals(0, registry.commandCount())
    }

    // ═══════════════════════════════════════════════════════════════
    // R18-R19: Entry count and isRegistered
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R18-entryCount counts all version entries`() {
        val p1 = createPlugin("p.a", "1.0.0", mapOf("cmd.x" to EchoHandler("")))
        val p2 = createPlugin("p.b", "1.0.0", mapOf("cmd.y" to EchoHandler("")))
        registry.register(p1)
        registry.register(p2)

        assertEquals(2, registry.entryCount())
        assertEquals(2, registry.commandCount())
    }

    @Test
    fun `R19-isRegistered checks both id and alias`() {
        val plugin = createPluginWithAlias(
            id = "example.reg",
            version = "1.0.0",
            commandId = "sys.notify",
            alias = "notify",
            handler = EchoHandler("")
        )
        registry.register(plugin)

        assertTrue(registry.isRegistered("sys.notify"))
        assertTrue(registry.isRegistered("notify"))
        assertTrue(registry.isRegistered("SYS.NOTIFY")) // case-insensitive
        assertFalse(registry.isRegistered("not.registered"))
    }

    // ═══════════════════════════════════════════════════════════════
    // R20: SemVer parsing
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R20-SemanticVersion parsing and comparison`() {
        val v1 = SemanticVersion.parse("1.0.0")
        val v2 = SemanticVersion.parse("1.2.3")
        val v3 = SemanticVersion.parse("2.0.0")
        val v4 = SemanticVersion.parse("1.0")

        assertEquals(1, v1.major)
        assertEquals(0, v1.minor)
        assertEquals(0, v1.patch)

        assertEquals(1, v4.major)
        assertEquals(0, v4.minor)
        assertEquals(0, v4.patch) // patch defaults to 0

        assertTrue(v2 > v1)
        assertTrue(v3 > v2)
        assertEquals(v1, v4)
    }

    @Test
    fun `R21-SemanticVersion invalid input throws`() {
        assertFailsWith<IllegalArgumentException> { SemanticVersion.parse("") }
        assertFailsWith<IllegalArgumentException> { SemanticVersion.parse("1") }
        assertFailsWith<IllegalArgumentException> { SemanticVersion.parse("a.b.c") }
        assertFailsWith<IllegalArgumentException> { SemanticVersion.parse("1.0.0.0") }
    }

    // ═══════════════════════════════════════════════════════════════
    // R22-R24: Trust level on registration ([08-security.md §7])
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R22-registration records trust level`() {
        val plugin = createPlugin(
            id = "example.trusted",
            version = "1.0.0",
            commands = mapOf("trust.cmd" to EchoHandler(""))
        )
        val result = registry.register(plugin, TrustLevel.MARKETPLACE_VERIFIED)

        assertIs<RegisterResult.Ok>(result)
        val resolved = registry.resolve("trust.cmd")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, resolved.entry.trustLevel)
    }

    @Test
    fun `R23-registration defaults to builtin trust`() {
        val plugin = createPlugin(
            id = "example.builtin",
            version = "1.0.0",
            commands = mapOf("builtin.cmd" to EchoHandler(""))
        )
        registry.register(plugin)

        val resolved = registry.resolve("builtin.cmd")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals(TrustLevel.BUILTIN, resolved.entry.trustLevel)
    }

    @Test
    fun `R24-untrusted plugin is rejected and not registered`() {
        val plugin = createPlugin(
            id = "example.evil",
            version = "1.0.0",
            commands = mapOf("evil.cmd" to EchoHandler(""))
        )
        val result = registry.register(plugin, TrustLevel.UNTRUSTED)

        assertIs<RegisterResult.Rejected>(result)
        assertEquals("example.evil", result.pluginId)
        // Must not be resolvable afterwards.
        assertIs<ResolveResult.NotFound>(registry.resolve("evil.cmd"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // R#: Manifest-only registration (08 §8 — no plugin code in-process)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R-manifest-only registration indexes commands, aliases and merged permissions`() {
        val manifest = PluginManifest(
            id = "example.wire",
            name = "Wire",
            version = "2.0.0",
            minRuntimeVersion = "0.1.0",
            description = "manifest-only plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.test.Wire",
            permissions = listOf(PermissionEntry("mcos", "sandbox", "plugin-wide")),
            commands = listOf(
                CommandManifestEntry(
                    id = "wire.fetch",
                    version = "1.0.0",
                    title = "Fetch",
                    description = "network fetch",
                    sideEffectClass = SideEffectClass.network,
                    timeoutMs = 30_000,
                    permissions = listOf(PermissionEntry("mcos", "network.api.example.test", null)),
                    aliases = listOf("wire.get"),
                ),
                CommandManifestEntry(
                    id = "wire.ping",
                    version = "1.0.0",
                    title = "Ping",
                    description = "read ping",
                    sideEffectClass = SideEffectClass.read,
                ),
            ),
        )

        val result = registry.registerManifest(manifest, TrustLevel.MARKETPLACE_VERIFIED)

        assertIs<RegisterResult.Ok>(result)
        assertEquals(2, result.commandsRegistered)
        assertEquals(1, result.aliasesRegistered)

        val resolved = registry.resolve("wire.fetch")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals("example.wire", resolved.entry.descriptor.pluginId)
        assertEquals(SideEffectClass.network, resolved.entry.descriptor.sideEffectClass)
        assertEquals(30_000L, resolved.entry.descriptor.timeoutMs)
        // plugin-level permissions are additive with per-command ones — same merge as the class path
        assertEquals(
            setOf("sandbox", "network.api.example.test"),
            resolved.entry.descriptor.permissions.map { it.name }.toSet(),
        )
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, resolved.entry.trustLevel)
        // alias resolves to the same entry
        val byAlias = registry.resolve("wire.get")
        assertIs<ResolveResult.Found>(byAlias)
        assertEquals(resolved.entry.descriptor.id, byAlias.entry.descriptor.id)
    }

    @Test
    fun `R-manifest-only handler fails honestly without an isolation host`() = runBlocking {
        registry.registerManifest(
            wireManifest(),
            TrustLevel.MARKETPLACE_VERIFIED,
        )
        val resolved = registry.resolve("wire.fetch")
        assertIs<ResolveResult.Found>(resolved)

        val result = resolved.entry.handler.invoke(
            ExecutionContext(
                runId = "r-1",
                commandId = "wire.fetch",
                args = JsonObject(emptyMap()),
                // the isolation stub never touches services — a minimal
                // in-memory MemoryFacade is all StubHostServices requires
                services = StubHostServices(object : MemoryFacade {
                    override suspend fun get(path: String) = null
                    override suspend fun resolveRef(ref: String, semanticType: String?) =
                        // Alias import: bare `ResolveResult` here means this package's
                        // own command-resolution type (32 uses); this is the SDK's
                        // memory-ref-resolution type (only use).
                        SdkResolveResult.NotFound("ref_unresolvable")
                }),
            ),
        )

        val err = assertIs<CommandResult.Err>(result)
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(IsolationRequiredHandler.AUDIT_REASON, err.details["reason"]?.jsonPrimitive?.content)
        assertFalse(err.retryable)
    }

    @Test
    fun `R-manifest-only untrusted registration is rejected`() {
        val result = registry.registerManifest(wireManifest(), TrustLevel.UNTRUSTED)

        assertIs<RegisterResult.Rejected>(result)
        assertIs<ResolveResult.NotFound>(registry.resolve("wire.fetch"))
    }

    @Test
    fun `R-manifest-only re-registration replaces prior entries`() {
        registry.registerManifest(wireManifest(), TrustLevel.MARKETPLACE_VERIFIED)
        val again = registry.registerManifest(
            wireManifest().copy(version = "2.1.0"),
            TrustLevel.MARKETPLACE_VERIFIED,
        )

        assertIs<RegisterResult.Ok>(again)
        val resolved = registry.resolve("wire.fetch")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals("2.1.0", resolved.entry.pluginVersion)
    }

    private fun wireManifest() = PluginManifest(
        id = "example.wire",
        name = "Wire",
        version = "2.0.0",
        minRuntimeVersion = "0.1.0",
        description = "manifest-only plugin",
        provider = ProviderInfo("Test", "https://test.local"),
        entry = "com.test.Wire",
        commands = listOf(
            CommandManifestEntry(
                id = "wire.fetch",
                version = "1.0.0",
                title = "Fetch",
                description = "network fetch",
                sideEffectClass = SideEffectClass.network,
            ),
        ),
    )

    private fun createPlugin(
        id: String,
        version: String,
        commands: Map<String, CommandHandler>
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = id,
            version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin $id",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin"
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = commands
    }

    private fun createPluginWithAlias(
        id: String,
        version: String,
        commandId: String,
        alias: String,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = id,
            version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin $id",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "A command",
                    sideEffectClass = SideEffectClass.read,
                    aliases = listOf(alias)
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithManifest(
        pluginId: String,
        pluginVersion: String,
        entries: List<CommandManifestEntry>,
        handlers: Map<String, CommandHandler>
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = pluginId,
            name = pluginId,
            version = pluginVersion,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin $pluginId",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = entries
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = handlers
    }

    class EchoHandler(private val response: String) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult =
            CommandResult.Ok(JsonPrimitive(response))
    }
}
