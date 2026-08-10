package com.mcos.plugin.system

import com.mcos.sdk.*
import kotlinx.serialization.json.*

/**
 * System commands plugin: sys.notify, sys.share, sys.intent.start, etc.
 * Matches [04-plugin-sdk.md §17].
 */
class SystemPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.system",
        name = "System Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "System-level commands: notify, share, clipboard, open URL, device info",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.mcos.plugin.system.SystemPlugin",
        commands = listOf(
            CommandManifestEntry(
                id = "sys.notify", version = "1.0.0",
                title = "Send Notification", description = "Post a system notification",
                sideEffectClass = SideEffectClass.write,
                examples = listOf("""sys.notify(title="MCOS", text="Done")""")
            ),
            CommandManifestEntry(
                id = "sys.share", version = "1.0.0",
                title = "Share Content", description = "Share content via system share sheet",
                sideEffectClass = SideEffectClass.write,
                examples = listOf("""sys.share(text="Hello", uri="content://...")""")
            ),
            CommandManifestEntry(
                id = "sys.clipboard", version = "1.0.0",
                title = "Clipboard", description = "Read or write system clipboard",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("""sys.clipboard(text="Copy this")""")
            ),
            CommandManifestEntry(
                id = "sys.openUrl", version = "1.0.0",
                title = "Open URL", description = "Open a URL in default browser",
                sideEffectClass = SideEffectClass.network,
                examples = listOf("""sys.openUrl(url="https://example.com")""")
            ),
            CommandManifestEntry(
                id = "sys.intent.start", version = "1.0.0",
                title = "Start Intent", description = "Start a schematized Android Intent",
                sideEffectClass = SideEffectClass.write,
                examples = listOf()
            ),
            CommandManifestEntry(
                id = "sys.vibrate", version = "1.0.0",
                title = "Vibrate", description = "Trigger device vibration",
                sideEffectClass = SideEffectClass.control,
                examples = listOf("""sys.vibrate(duration=500)""")
            )
        ),
        namespaces = listOf("sys"),
        threadHint = "main"
    )

    override suspend fun onLoad(services: HostServices) {}
    override suspend fun onUnload() {}

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "sys.notify" to NotifyHandler,
        "sys.share" to ShareHandler,
        "sys.clipboard" to ClipboardHandler,
        "sys.openUrl" to OpenUrlHandler,
        "sys.intent.start" to IntentStartHandler,
        "sys.vibrate" to VibrateHandler
    )
}

// ─── Stub handlers — P1 MVP returns stub results ──────────────────────────

object NotifyHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: notification sent"))
    }
}

object ShareHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: content shared"))
    }
}

object ClipboardHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val text = ctx.args.jsonObject["text"]?.jsonPrimitive?.content
        return CommandResult.Ok(JsonPrimitive("stub: clipboard set to '$text'"))
    }
}

object OpenUrlHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val url = ctx.args.jsonObject["url"]?.jsonPrimitive?.content ?: return CommandResult.Err(
            "SCHEMA_VIOLATION", "Missing required arg: url", retryable = false
        )
        return CommandResult.Ok(JsonPrimitive("stub: opened $url"))
    }
}

object IntentStartHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: intent started"))
    }
}

object VibrateHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: vibrate"))
    }
}
