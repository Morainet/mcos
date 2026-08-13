package com.mcos.plugin.hello

import com.mcos.sdk.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        entry = "com.mcos.plugin.hello.HelloPlugin",
        // P0-F2: the manifest must declare its command(s) so the registry can
        // build a proper CommandDescriptor (with schema, title, side-effect
        // class). Without this entry the plugin registered zero commands and
        // hello.world was undiscoverable.
        commands = listOf(
            CommandManifestEntry(
                id = "hello.world",
                version = "1.0.0",
                title = "Hello World",
                description = "Returns a greeting for the given name",
                sideEffectClass = SideEffectClass.read,
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Name to greet"))
                        })
                    })
                },
                examples = listOf("""hello.world(name="Alice")"""),
            )
        )
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
