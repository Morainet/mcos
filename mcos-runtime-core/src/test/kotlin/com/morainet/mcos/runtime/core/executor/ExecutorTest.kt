package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.security.ScopeBasedEgressPolicy
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.TokenBucketRateLimiter
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for Executor v0.1.
 * Matches [03-runtime.md 9].
 */
class ExecutorTest {

    private lateinit var registry: CommandRegistry
    private lateinit var executor: Executor
    private val services = StubHostServices()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        // Baseline executor: the named permissive posture (authorization,
        // rate, egress, signatures all inert by choice). Individual tests
        // swap in real components via `.copy(...)`.
        executor = Executor(registry, services, SecurityConfig.permissive())
    }

    @AfterTest
    fun tearDown() {
        registry.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // E1-E3: Basic execution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E1-execute simple command returns Ok`() = runBlocking {
        val plugin = createPlugin("test.basic", "1.0.0", mapOf(
            "cmd.ok" to EchoHandler("done")
        ))
        registry.register(plugin)

        val result = executor.execute("cmd.ok")
        assertIs<CommandResult.Ok>(result)
        assertEquals("done", result.value.jsonPrimitive.content)
        assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun `E2-args are passed through to handler`() = runBlocking {
        val plugin = createPlugin("test.args", "1.0.0", mapOf(
            "echo.args" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    val name = ctx.args.jsonObject["name"]!!.jsonPrimitive.content
                    return CommandResult.Ok(JsonPrimitive("Hello, $name"))
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute(
            commandId = "echo.args",
            args = buildJsonObject { put("name", JsonPrimitive("MCOS")) }
        )

        assertIs<CommandResult.Ok>(result)
        assertEquals("Hello, MCOS", result.value.jsonPrimitive.content)
    }

    @Test
    fun `E3-unknown command returns UNKNOWN_COMMAND`() = runBlocking {
        val result = executor.execute("not.registered")

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.UNKNOWN_COMMAND.name, result.code)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("not.registered"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E4-E5: Error handling and exception mapping
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E4-handler throws generic exception maps to PLUGIN_ERROR`() = runBlocking {
        val plugin = createPlugin("test.err", "1.0.0", mapOf(
            "cmd.bomb" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    throw RuntimeException("something went wrong internally")
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute("cmd.bomb")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PLUGIN_ERROR.name, result.code)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("cmd.bomb"))
        // Stack trace should NOT be in the message
        assertFalse(result.message.contains(".kt:"))
    }

    @Test
    fun `E5-handler throws McosException maps directly`() = runBlocking {
        val details = buildJsonObject { put("hint", JsonPrimitive("camera busy")) }
        val plugin = createPlugin("test.mcoserr", "1.0.0", mapOf(
            "cmd.mcos" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    throw McosException(
                        code = McosErrorCode.UNAVAILABLE.name,
                        message = "Camera hardware is busy",
                        retryable = true,
                        details = details
                    )
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute("cmd.mcos")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.UNAVAILABLE.name, result.code)
        assertEquals("Camera hardware is busy", result.message)
        assertTrue(result.retryable)
        assertEquals("camera busy", result.details["hint"]?.jsonPrimitive?.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // E6-E7: Timeout and cancellation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E6-handler exceeds timeout returns TIMEOUT`() = runBlocking {
        val plugin = createPluginWithTimeout(
            id = "test.timeout",
            version = "1.0.0",
            commandId = "cmd.slow",
            timeoutMs = 100, // very short timeout
            handler = object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    delay(5000) // way longer than timeout
                    return CommandResult.Ok(JsonPrimitive("never"))
                }
            }
        )
        registry.register(plugin)

        val result = executor.execute("cmd.slow")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.TIMEOUT.name, result.code)
        assertTrue(result.retryable)
        assertTrue(result.message.contains("timed out"))
    }

    @Test
    fun `E7-cancellation returns CANCELLED`() = runBlocking {
        val plugin = createPlugin("test.cancel", "1.0.0", mapOf(
            "cmd.wait" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    delay(Long.MAX_VALUE) // wait forever
                    return CommandResult.Ok(JsonPrimitive("never"))
                }
            }
        ))
        registry.register(plugin)

        var result: CommandResult? = null
        val job = launch {
            result = executor.execute("cmd.wait")
        }

        delay(50) // let it start
        job.cancel()
        job.join()

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.CANCELLED.name, (result as CommandResult.Err).code)
    }

    // ═══════════════════════════════════════════════════════════════
    // E8: Sequence execution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E8-executeSequence runs all steps in order`() = runBlocking {
        val executed = mutableListOf<String>()
        val plugin = createPlugin("test.seq", "1.0.0", mapOf(
            "step.a" to TrackingHandler("step.a", executed, "A"),
            "step.b" to TrackingHandler("step.b", executed, "B"),
            "step.c" to TrackingHandler("step.c", executed, "C")
        ))
        registry.register(plugin)

        val results = executor.executeSequence(
            listOf(
                Command("step.a"),
                Command("step.b"),
                Command("step.c")
            )
        )

        assertEquals(3, results.size)
        assertEquals(listOf("A", "B", "C"), results.map { (it as CommandResult.Ok).value.jsonPrimitive.content })
        assertEquals(listOf("step.a", "step.b", "step.c"), executed)
    }

    @Test
    fun `E9-executeSequence stops on first error`() = runBlocking {
        val executed = mutableListOf<String>()
        val plugin = createPlugin("test.seqerr", "1.0.0", mapOf(
            "step.a" to TrackingHandler("step.a", executed, "A"),
            "step.b" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    executed.add("step.b")
                    throw RuntimeException("BOOM")
                }
            },
            "step.c" to TrackingHandler("step.c", executed, "C")
        ))
        registry.register(plugin)

        val results = executor.executeSequence(
            listOf(
                Command("step.a"),
                Command("step.b"),
                Command("step.c")
            )
        )

        assertEquals(2, results.size) // stops after step.b error
        assertIs<CommandResult.Ok>(results[0])
        assertIs<CommandResult.Err>(results[1])
        assertEquals(listOf("step.a", "step.b"), executed)
        // step.c should NOT have been executed
        assertFalse(executed.contains("step.c"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E10-E11: Execution context integrity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E10-ExecutionContext has correct commandId and args`() = runBlocking {
        var capturedCtx: ExecutionContext? = null
        val plugin = createPlugin("test.ctx", "1.0.0", mapOf(
            "ctx.check" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    capturedCtx = ctx
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        ))
        registry.register(plugin)

        val args = buildJsonObject { put("key", JsonPrimitive("value")) }
        executor.execute("ctx.check", args)

        assertEquals("ctx.check", capturedCtx!!.commandId)
        assertEquals("value", capturedCtx!!.args.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `E11-ExecutionContext deadline is set from descriptor timeout`() = runBlocking {
        var capturedCtx: ExecutionContext? = null
        val plugin = createPluginWithTimeout(
            id = "test.deadline",
            version = "1.0.0",
            commandId = "deadline.check",
            timeoutMs = 30000,
            handler = object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    capturedCtx = ctx
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        )
        registry.register(plugin)

        val beforeCall = System.currentTimeMillis()
        executor.execute("deadline.check")
        val afterCall = System.currentTimeMillis()

        assertNotNull(capturedCtx!!.deadline)
        // deadline should be roughly beforeCall + 30000
        val expectedDeadline = beforeCall + 30000
        assertTrue(
            capturedCtx!!.deadline!! >= expectedDeadline - 100,
            "deadline should be ≥ roughly ${expectedDeadline - 100}, was ${capturedCtx!!.deadline}"
        )
        assertTrue(
            capturedCtx!!.deadline!! <= afterCall + 30000,
            "deadline should be ≤ $afterCall + 30000, was ${capturedCtx!!.deadline}"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // E12-E13: Ok with artifacts
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E12-handler returns Ok with artifacts`() = runBlocking {
        val plugin = createPlugin("test.art", "1.0.0", mapOf(
            "art.produce" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    return CommandResult.Ok(
                        value = JsonPrimitive("created"),
                        artifacts = listOf(
                            Artifact("image", "file:///photo.jpg", "image/jpeg"),
                            Artifact("thumbnail", "file:///thumb.jpg", "image/jpeg")
                        )
                    )
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute("art.produce")
        assertIs<CommandResult.Ok>(result)
        assertEquals(2, result.artifacts.size)
        assertEquals("image", result.artifacts[0].type)
        assertEquals("file:///photo.jpg", result.artifacts[0].uri)
    }

    @Test
    fun `E13-handler executes with alias resolution`() = runBlocking {
        val plugin = createPluginWithAlias(
            id = "test.alias.exec",
            version = "1.0.0",
            commandId = "sys.notify",
            alias = "notify",
            handler = EchoHandler("notified")
        )
        registry.register(plugin)

        val result = executor.execute("notify")
        assertIs<CommandResult.Ok>(result)
        assertEquals("notified", result.value.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // E14-E15: Schema validation integration (Stage 5)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E14-schema violation returns SCHEMA_VIOLATION`() = runBlocking {
        val inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray {
                add(JsonPrimitive("url"))
            })
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("minLength", JsonPrimitive(1))
                })
            })
        }
        val plugin = createPluginWithSchema(
            id = "test.schema",
            version = "1.0.0",
            commandId = "net.fetch",
            inputSchema = inputSchema,
            handler = EchoHandler("ok")
        )
        registry.register(plugin)

        // Execute with missing required field "url"
        val result = executor.execute("net.fetch", buildJsonObject {
            put("other", JsonPrimitive("value"))
        })

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.SCHEMA_VIOLATION.name, result.code)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("Schema validation failed"))
        // Details should contain errors array
        assertTrue(result.details.containsKey("errors"))
    }

    @Test
    fun `E15-valid args against schema execute successfully`() = runBlocking {
        val inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("url")) })
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("minLength", JsonPrimitive(1))
                })
            })
        }
        val plugin = createPluginWithSchema(
            id = "test.schema2",
            version = "1.0.0",
            commandId = "net.fetch2",
            inputSchema = inputSchema,
            handler = EchoHandler("fetched")
        )
        registry.register(plugin)

        val result = executor.execute("net.fetch2", buildJsonObject {
            put("url", JsonPrimitive("https://example.com"))
        })

        assertIs<CommandResult.Ok>(result)
        assertEquals("fetched", result.value.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // E16-E17: PermissionKernel integration (Stage 6)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E16-PERMISSION_DENIED when required permission missing`() = runBlocking {
        val permKernel = DefaultPermissionKernel()
        val executorWithPerm =
            Executor(registry, services, SecurityConfig.permissive().copy(kernel = permKernel))

        // Register a command that requires CAMERA permission (not granted)
        val permPlugin = createPluginWithPerms(
            id = "test.perm2",
            version = "1.0.0",
            commandId = "camera.capture",
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA")),
            handler = EchoHandler("photo")
        )
        registry.register(permPlugin)

        val result = executorWithPerm.execute("camera.capture")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
        // PERMISSION_DENIED is non-retryable: a denied command stays denied
        // until the user explicitly grants the permission.
        assertFalse(result.retryable)
    }

    @Test
    fun `E17-permission granted allows execution`() = runBlocking {
        val permKernel = DefaultPermissionKernel()
        // Grant required permission
        permKernel.grant("test.perm3", "android.permission.CAMERA")

        val executorWithPerm =
            Executor(registry, services, SecurityConfig.permissive().copy(kernel = permKernel))

        val plugin = createPluginWithPerms(
            id = "test.perm3",
            version = "1.0.0",
            commandId = "camera.capture",
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA")),
            handler = EchoHandler("photo taken")
        )
        registry.register(plugin)

        val result = executorWithPerm.execute("camera.capture")
        // AlwaysConfirm is off, but sideEffectClass=read → authorization passes
        assertIs<CommandResult.Ok>(result)
        assertEquals("photo taken", result.value.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // E18-E19: Rate limiting integration (Stage 5.5)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E18-rate limited command returns RATE_LIMITED`() = runBlocking {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 1, maxDestructivePerHour = 100)
        val executorWithLimiter =
            Executor(registry, services, SecurityConfig.permissive().copy(rateLimiter = limiter))

        val plugin = createPlugin("test.rl", "1.0.0", mapOf(
            "cmd.fast" to EchoHandler("done")
        ))
        registry.register(plugin)

        // First call — allowed
        val result1 = executorWithLimiter.execute("cmd.fast")
        assertIs<CommandResult.Ok>(result1)

        // Second call — rate limited
        val result2 = executorWithLimiter.execute("cmd.fast")
        assertIs<CommandResult.Err>(result2)
        assertEquals(McosErrorCode.RATE_LIMITED.name, result2.code)
        assertTrue(result2.retryable)
        assertTrue(result2.message.contains("Rate limited"))
    }

    @Test
    fun `E19-destructive rate limit uses separate counter`() = runBlocking {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 100, maxDestructivePerHour = 1)
        val executorWithLimiter =
            Executor(registry, services, SecurityConfig.permissive().copy(rateLimiter = limiter))

        val plugin = createPluginWithSideEffect(
            id = "test.destrl",
            version = "1.0.0",
            commandId = "cmd.delete",
            sideEffectClass = SideEffectClass.destructive,
            handler = EchoHandler("deleted")
        )
        registry.register(plugin)

        // First destructive — allowed
        val result1 = executorWithLimiter.execute("cmd.delete")
        assertIs<CommandResult.Ok>(result1)

        // Second destructive — limited
        val result2 = executorWithLimiter.execute("cmd.delete")
        assertIs<CommandResult.Err>(result2)
        assertEquals(McosErrorCode.RATE_LIMITED.name, result2.code)
    }

    // ═══════════════════════════════════════════════════════════════
    // E20-E21: Network egress policy integration (Stage 5.6)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E20-network command denied without egress scope`() = runBlocking {
        val egressPolicy = ScopeBasedEgressPolicy()
        val executorWithEgress =
            Executor(registry, services, SecurityConfig.permissive().copy(egress = egressPolicy))

        val plugin = createPluginWithSideEffect(
            id = "test.egress",
            version = "1.0.0",
            commandId = "net.fetch",
            sideEffectClass = SideEffectClass.network,
            handler = EchoHandler("ok")
        )
        registry.register(plugin)

        val result = executorWithEgress.execute("net.fetch", buildJsonObject {
            put("url", JsonPrimitive("https://example.com/api"))
        })

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
        assertTrue(result.message.contains("Network egress denied"))
    }

    @Test
    fun `E21-network egress not checked for non-network side effects`() = runBlocking {
        val egressPolicy = ScopeBasedEgressPolicy()
        val executorWithEgress =
            Executor(registry, services, SecurityConfig.permissive().copy(egress = egressPolicy))

        // read side effect — egress check skipped even with url arg
        val plugin = createPlugin("test.noegress", "1.0.0", mapOf(
            "cmd.read" to EchoHandler("ok")
        ))
        registry.register(plugin)

        val result = executorWithEgress.execute("cmd.read", buildJsonObject {
            put("url", JsonPrimitive("https://example.com"))
        })

        // Should pass through without egress check
        assertIs<CommandResult.Ok>(result)
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // E22: PermissionKernel wiring verified (Stage 6 integration)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E22-PERMISSION_DENIED when PermissionKernel wired via Executor`() = runBlocking {
        // This test verifies the PermissionKernel is correctly connected.
        // Without the fix, permissionKernel would be null and this would pass
        // (because the check is skipped). With the fix, it should deny.
        val permKernel = DefaultPermissionKernel()
        val executorWithAll = Executor(
            registry, services,
            security = SecurityConfig.permissive().copy(
                kernel = permKernel,
                rateLimiter = TokenBucketRateLimiter(),
                egress = ScopeBasedEgressPolicy(),
            ),
        )

        val plugin = createPluginWithPerms(
            id = "test.wired",
            version = "1.0.0",
            commandId = "cmd.restricted",
            permissions = listOf(PermissionEntry("android", "android.permission.SEND_SMS")),
            handler = EchoHandler("sent")
        )
        registry.register(plugin)

        val result = executorWithAll.execute("cmd.restricted")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
    }

    @Test
    fun `E23-expired AuthStamp is rejected with PERMISSION_DENIED`() = runBlocking {
        // Verify the Executor.kt:180 expiry-check path: when a caller supplies
        // an AuthStamp whose expiresAt <= now, the Executor must reject it
        // rather than trusting it. This covers the trust-bypass defense.
        val permKernel = DefaultPermissionKernel()
        val executorWithKernel =
            Executor(registry, services, SecurityConfig.permissive().copy(kernel = permKernel))

        val plugin = createPlugin("test.expiry", "1.0.0", mapOf(
            "cmd.check" to EchoHandler("ok")
        ))
        registry.register(plugin)

        val now = System.currentTimeMillis()
        val expiredStamp = AuthStamp(
            runId = "run_1",
            commandId = "cmd.check",
            pluginId = "test.expiry",
            grantsUsed = emptySet(),
            issuedAt = now - 10_000,
            expiresAt = now - 1 // already expired
        )

        val result = executorWithKernel.execute("cmd.check", JsonObject(emptyMap()), expiredStamp)
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
        assertTrue(result.message.contains("expired"), "Error message should mention expiry: ${result.message}")
        assertFalse(result.retryable)
    }

    @Test
    fun `E24-forged AuthStamp rejected when signer configured`() = runBlocking {
        // Issue #3: a caller-supplied stamp without a valid signature must be
        // rejected when an AuthStampSigner is wired into the Executor.
        val signer = HmacAuthStampSigner()
        val executorWithSigner =
            Executor(registry, services, SecurityConfig.permissive().copy(signer = signer))

        val plugin = createPlugin("test.forge", "1.0.0", mapOf(
            "cmd.free" to EchoHandler("ok")
        ))
        registry.register(plugin)

        val now = System.currentTimeMillis()
        val forged = AuthStamp(
            runId = "run_forged",
            commandId = "cmd.free",
            pluginId = "test.forge",
            grantsUsed = emptySet(),
            issuedAt = now - 1_000,
            expiresAt = now + 60_000 // not expired — would pass without signing
        )

        val result = executorWithSigner.execute("cmd.free", JsonObject(emptyMap()), forged)
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
        assertTrue(result.message.contains("signature"), "Should mention signature verification: ${result.message}")
    }

    @Test
    fun `E25-validly signed AuthStamp accepted`() {
        // A stamp signed by the configured signer must be accepted.
        runBlocking {
            val signer = HmacAuthStampSigner()
            val executorWithSigner =
                Executor(registry, services, SecurityConfig.permissive().copy(signer = signer))

            val plugin = createPlugin("test.signed", "1.0.0", mapOf(
                "cmd.free" to EchoHandler("ok")
            ))
            registry.register(plugin)

            val now = System.currentTimeMillis()
            val valid = signer.sign(
                AuthStamp(
                    runId = "run_signed",
                    commandId = "cmd.free",
                    pluginId = "test.signed",
                    grantsUsed = emptySet(),
                    issuedAt = now - 1_000,
                    expiresAt = now + 60_000
                )
            )

            val result = executorWithSigner.execute("cmd.free", JsonObject(emptyMap()), valid)
            assertIs<CommandResult.Ok>(result)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // E25-E28: Security pipeline regression tests (P0-S1/S2/S3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E25-forged AuthStamp cannot bypass egress even with network scope`() = runBlocking {
        // P0-S1 regression: egress must run AFTER signature verification.
        // A caller forges a stamp granting network.evil.com; without the
        // reorder, the egress check would read the forged grantsUsed and
        // ALLOW egress to evil.com. With the fix, the forged signature is
        // rejected first.
        val signer = HmacAuthStampSigner()
        val executorWithAll = Executor(
            registry, services,
            security = SecurityConfig.permissive().copy(
                egress = ScopeBasedEgressPolicy(),
                signer = signer,
            ),
        )
        val plugin = createPluginWithSideEffect(
            id = "test.egressForge", version = "1.0.0",
            commandId = "net.fetch", sideEffectClass = SideEffectClass.network,
            handler = EchoHandler("ok")
        )
        registry.register(plugin)

        val now = System.currentTimeMillis()
        val forged = AuthStamp(
            runId = "run_forge",
            commandId = "net.fetch",
            pluginId = "test.egressForge",
            grantsUsed = setOf("network.evil.com"), // forged scope
            issuedAt = now - 1_000,
            expiresAt = now + 60_000,
        )

        val result = executorWithAll.execute(
            "net.fetch",
            buildJsonObject { put("url", JsonPrimitive("https://evil.com/api")) },
            forged,
        )
        assertIs<CommandResult.Err>(result)
        // Must be rejected for signature, NOT allowed through egress.
        assertTrue(result.message.contains("signature"), "Should reject forged signature: ${result.message}")
    }

    @Test
    fun `E26-global kill switch denies all network egress`() = runBlocking {
        // P0-S2 regression: the global kill switch must reach decideEgress.
        val executorWithKill = Executor(
            registry, services,
            security = SecurityConfig.permissive().copy(egress = ScopeBasedEgressPolicy()),
            globalKillSwitch = { true },
        )
        val plugin = createPluginWithSideEffect(
            id = "test.kill", version = "1.0.0",
            commandId = "net.fetch", sideEffectClass = SideEffectClass.network,
            handler = EchoHandler("ok")
        )
        registry.register(plugin)

        val result = executorWithKill.execute(
            "net.fetch",
            buildJsonObject { put("url", JsonPrimitive("https://example.com/api")) },
        )
        assertIs<CommandResult.Err>(result)
        assertTrue(result.message.contains("kill_switch"), "Should deny via kill switch: ${result.message}")
    }

    @Test
    fun `E27-IDN homograph host is normalised before scope match`() = runBlocking {
        // P0-S3 regression: a Unicode host must be Punycode-normalised so it
        // cannot bypass a narrower scope. Here the granted scope is the ASCII
        // "example.com" but the request targets a homograph; the egress check
        // must deny (domain_not_in_scope), not allow.
        val signer = HmacAuthStampSigner()
        val executorWithEgress = Executor(
            registry, services,
            security = SecurityConfig.permissive().copy(
                egress = ScopeBasedEgressPolicy(),
                signer = signer,
            ),
        )
        val plugin = createPluginWithSideEffect(
            id = "test.idn", version = "1.0.0",
            commandId = "net.fetch", sideEffectClass = SideEffectClass.network,
            handler = EchoHandler("ok")
        )
        registry.register(plugin)

        val now = System.currentTimeMillis()
        // Grant only the legitimate ASCII scope.
        val stamp = signer.sign(AuthStamp(
            runId = "run_idn",
            commandId = "net.fetch",
            pluginId = "test.idn",
            grantsUsed = setOf("network.example.com"),
            issuedAt = now - 1_000,
            expiresAt = now + 60_000,
        ))

        // Request a homograph domain (ä ≈ a, visually similar but distinct).
        val result = executorWithEgress.execute(
            "net.fetch",
            buildJsonObject { put("url", JsonPrimitive("https://exämple.com/api")) },
            stamp,
        )
        assertIs<CommandResult.Err>(result)
        assertTrue(
            result.message.contains("domain_not_in_scope") || result.message.contains("no_network_scope"),
            "Homograph host should be denied: ${result.message}"
        )
    }

    @Test
    fun `E28-egress allows when granted scope matches host`() = runBlocking {
        // Positive control for E25-E27: a properly signed stamp with a
        // matching network scope must be allowed through egress.
        val signer = HmacAuthStampSigner()
        val executorWithEgress = Executor(
            registry, services,
            security = SecurityConfig.permissive().copy(
                egress = ScopeBasedEgressPolicy(),
                signer = signer,
            ),
        )
        val plugin = createPluginWithSideEffect(
            id = "test.allow", version = "1.0.0",
            commandId = "net.fetch", sideEffectClass = SideEffectClass.network,
            handler = EchoHandler("ok")
        )
        registry.register(plugin)

        val now = System.currentTimeMillis()
        val stamp = signer.sign(AuthStamp(
            runId = "run_allow",
            commandId = "net.fetch",
            pluginId = "test.allow",
            grantsUsed = setOf("network.example.com"),
            issuedAt = now - 1_000,
            expiresAt = now + 60_000,
        ))

        val result = executorWithEgress.execute(
            "net.fetch",
            buildJsonObject { put("url", JsonPrimitive("https://example.com/api")) },
            stamp,
        )
        assertIs<CommandResult.Ok>(result)
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // E29-E30: Hardening — unconditional stamp validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E29-permissive config still rejects expired AuthStamp`() = runBlocking {
        // SecurityConfig.permissive() waives grants, rate, egress and
        // signatures by NAMED choice — but caller-supplied stamp expiry and
        // permission-coverage checks are unconditional and must still fire.
        // (The historical null-kernel executor silently accepted expired
        // stamps: fail-open. This test pins the hole shut.)
        val executorPermissive = Executor(registry, services, SecurityConfig.permissive())

        val plugin = createPlugin("test.expiry29", "1.0.0", mapOf(
            "cmd.check" to EchoHandler("ok")
        ))
        registry.register(plugin)

        val now = System.currentTimeMillis()
        val expiredStamp = AuthStamp(
            runId = "run_e29",
            commandId = "cmd.check",
            pluginId = "test.expiry29",
            grantsUsed = emptySet(),
            issuedAt = now - 10_000,
            expiresAt = now - 1 // already expired
        )

        val result = executorPermissive.execute("cmd.check", JsonObject(emptyMap()), expiredStamp)
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
        assertTrue(result.message.contains("expired"), "Should mention expiry: ${result.message}")
        assertFalse(result.retryable)
    }

    @Test
    fun `E30-permissive config still rejects AuthStamp missing required permission`() = runBlocking {
        // Coverage check is likewise unconditional: a stamp that does not
        // span the descriptor's required permissions is rejected even under
        // the permissive posture (privilege-escalation defense).
        val executorPermissive = Executor(registry, services, SecurityConfig.permissive())

        val plugin = createPluginWithPerms(
            id = "test.scope30",
            version = "1.0.0",
            commandId = "cmd.restricted",
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA")),
            handler = EchoHandler("photo")
        )
        registry.register(plugin)

        val now = System.currentTimeMillis()
        val underprivileged = AuthStamp(
            runId = "run_e30",
            commandId = "cmd.restricted",
            pluginId = "test.scope30",
            grantsUsed = emptySet(), // does not cover CAMERA
            issuedAt = now - 1_000,
            expiresAt = now + 60_000
        )

        val result = executorPermissive.execute("cmd.restricted", JsonObject(emptyMap()), underprivileged)
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
        assertTrue(result.message.contains("does not cover"), "Should mention scope gap: ${result.message}")
        assertFalse(result.retryable)
    }

    @Test
    fun `E31-executed commands see optional device capabilities through the secret-resolving facade`() = runBlocking {
        // Regression for the secretResolvingServices delegation: the anonymous
        // wrapper must forward deviceInfo/clipboard/haptics — a missing
        // override would silently null them for every executed command
        // (interface defaults), even though the host provides them.
        val executorCapable = Executor(registry, CapableHostServices(), SecurityConfig.permissive())

        var seenDeviceInfo: DeviceInfoService? = null
        var seenClipboard: ClipboardService? = null
        var seenHaptics: HapticsService? = null
        val plugin = createPlugin("test.caps31", "1.0.0", mapOf(
            "cmd.caps" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    seenDeviceInfo = ctx.services.deviceInfo
                    seenClipboard = ctx.services.clipboard
                    seenHaptics = ctx.services.haptics
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        ))
        registry.register(plugin)

        val result = executorCapable.execute("cmd.caps")
        assertIs<CommandResult.Ok>(result)
        assertNotNull(seenDeviceInfo, "deviceInfo must survive the secret-resolving facade")
        assertNotNull(seenClipboard, "clipboard must survive the secret-resolving facade")
        assertNotNull(seenHaptics, "haptics must survive the secret-resolving facade")
        Unit
    }

    @Test
    fun `E32-executed commands see a per-plugin namespaced sandbox`() = runBlocking {
        // 04 §6.1: the Stage-4 facade roots every sandbox path at the
        // executing plugin's namespace — one plugin cannot address another's
        // files. tempFile round-trips plugin-relative. A host without the
        // capability stays null (no fabricated sandbox).
        val recording = RecordingSandbox()
        val executorSandbox = Executor(registry, SandboxedHostServices(recording), SecurityConfig.permissive())

        var seenSandbox: SandboxFileService? = null
        var tempReturned: String? = null
        val plugin = createPlugin("test.sandbox32", "1.0.0", mapOf(
            "cmd.touch" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    seenSandbox = ctx.services.sandbox
                    ctx.services.sandbox!!.write("probe.txt", byteArrayOf(1))
                    ctx.services.sandbox!!.stat("probe.txt")
                    tempReturned = ctx.services.sandbox!!.tempFile("pfx", ".tmp")
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        ))
        registry.register(plugin)

        val result = executorSandbox.execute("cmd.touch")
        assertIs<CommandResult.Ok>(result)
        assertNotNull(seenSandbox, "sandbox must survive the secret-resolving facade")
        // probe write first; the tempFile reservation below adds one more.
        assertEquals("test.sandbox32/probe.txt", recording.writePaths.first())
        assertEquals(listOf("test.sandbox32/probe.txt"), recording.statPaths)
        val temp = tempReturned!!
        assertFalse(temp.contains('/'), "temp path must be plugin-relative: $temp")
        assertTrue(
            recording.writePaths.any { it == "test.sandbox32/$temp" },
            "tempFile must be reserved inside the plugin namespace: ${recording.writePaths}",
        )

        // A host without the sandbox capability must surface null, not a
        // sandbox pointing at the shared root.
        var seenBare = "never-set"
        val pluginBare = createPlugin("test.bare32", "1.0.0", mapOf(
            "cmd.bare" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    seenBare = ctx.services.sandbox?.let { "present" } ?: "null"
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        ))
        registry.register(pluginBare)

        val bare = Executor(registry, services, SecurityConfig.permissive()).execute("cmd.bare")
        assertIs<CommandResult.Ok>(bare)
        assertEquals("null", seenBare)
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun createPlugin(
        id: String,
        version: String,
        commands: Map<String, CommandHandler>
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin"
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = commands
    }

    private fun createPluginWithTimeout(
        id: String,
        version: String,
        commandId: String,
        timeoutMs: Long,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with timeout",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Timed command",
                    sideEffectClass = SideEffectClass.read,
                    timeoutMs = timeoutMs
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithAlias(
        id: String,
        version: String,
        commandId: String,
        alias: String,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with alias",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Command with alias",
                    sideEffectClass = SideEffectClass.read,
                    aliases = listOf(alias)
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithSchema(
        id: String,
        version: String,
        commandId: String,
        inputSchema: JsonObject,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with schema",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Command with input schema",
                    sideEffectClass = SideEffectClass.read,
                    inputSchema = inputSchema
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithSideEffect(
        id: String,
        version: String,
        commandId: String,
        sideEffectClass: SideEffectClass,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with custom side effect class",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Command with side effect $sideEffectClass",
                    sideEffectClass = sideEffectClass,
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithPerms(
        id: String,
        version: String,
        commandId: String,
        permissions: List<PermissionEntry>,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with permissions",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Command with permissions",
                    sideEffectClass = SideEffectClass.read,
                    permissions = permissions
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    class EchoHandler(private val response: String) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult =
            CommandResult.Ok(JsonPrimitive(response))
    }

    class TrackingHandler(
        private val name: String,
        private val tracker: MutableList<String>,
        private val response: String
    ) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            tracker.add(name)
            return CommandResult.Ok(JsonPrimitive(response))
        }
    }

    /**
     * Minimal HostServices stub for JVM testing.
     */
    open class StubHostServices : HostServices {
        override val files = object : FileService {
            override suspend fun list(uri: String, mimeType: String?): List<FileEntry> = emptyList()
        }
        override val net = object : NetService {
            override suspend fun request(method: String, url: String, body: String?, headers: Map<String, String>): NetResponse =
                NetResponse(200, "{}")
        }
        override val ui = object : UiService {
            override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = null
        }
        override val secureStore = object : SecureStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        override val clock = object : Clock {
            override fun nowMs(): Long = System.currentTimeMillis()
        }
        override val json = object : JsonService {
            override fun parse(json: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(json)
        }
        override val memory = object : MemoryFacade {
            override suspend fun get(path: String): JsonElement? = null
            override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound()
        }
    }

    /**
     * [StubHostServices] with the optional device capabilities filled in —
     * used to prove the executor forwards them to executed commands.
     */
    class CapableHostServices : StubHostServices() {
        override val deviceInfo = object : DeviceInfoService {
            override suspend fun battery() = BatteryInfo(percent = 100, charging = true)
            override suspend fun wifi() = WifiInfo(connected = false)
            override suspend fun screen() = ScreenInfo(1080, 2400, densityDpi = 420, rotation = 0)
            override suspend fun volume() = VolumeInfo(musicPercent = 50)
            override suspend fun location(): LocationInfo? = null
            override suspend fun brightness() = BrightnessInfo(level = 128, auto = false)
            override suspend fun setBrightness(level: Int) {}
        }
        override val clipboard = object : ClipboardService {
            override suspend fun set(text: String) {}
            override suspend fun get(): String? = null
        }
        override val haptics = object : HapticsService {
            override suspend fun vibrate(durationMs: Int) {}
        }
    }

    /** [StubHostServices] plus a recording sandbox capability (E32). */
    class SandboxedHostServices(private val sandboxDelegate: SandboxFileService) : StubHostServices() {
        override val sandbox: SandboxFileService get() = sandboxDelegate
    }

    /** Records every path it is asked to touch (E32 real-call assertions). */
    class RecordingSandbox : SandboxFileService {
        val writePaths = mutableListOf<String>()
        val statPaths = mutableListOf<String>()

        override suspend fun read(path: String): ByteArray? = null
        override suspend fun write(path: String, data: ByteArray, append: Boolean) {
            writePaths += path
        }
        override suspend fun stat(path: String): SandboxEntry? {
            statPaths += path
            return SandboxEntry(path = path, isDir = false, size = 0L)
        }
        override suspend fun delete(path: String): Boolean = false
        override suspend fun list(dir: String): List<SandboxEntry> = emptyList()
        override suspend fun tempFile(prefix: String, suffix: String): String = "${prefix}x$suffix"
    }
}
