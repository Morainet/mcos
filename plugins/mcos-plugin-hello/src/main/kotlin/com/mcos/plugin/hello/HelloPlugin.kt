package com.mcos.plugin.hello

import com.mcos.sdk.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reference sample plugin implementing hello.world.
 * Matches [04-plugin-sdk.md 16].
 */
class HelloPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "example.hello",
        name = "Hello Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "Reference sample plugin — hello.world",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.mcos.plugin.hello.HelloPlugin"
    )

    override suspend fun onLoad(services: HostServices) {}
    override suspend fun onUnload() {}

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "hello.world" to HelloWorldHandler
    )
}

object HelloWorldHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val name = ctx.args.jsonObject["name"]?.jsonPrimitive?.content ?: "World"
        return CommandResult.Ok(JsonPrimitive("Hello, $name!"))
    }
}
