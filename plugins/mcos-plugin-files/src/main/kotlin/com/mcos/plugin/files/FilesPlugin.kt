package com.mcos.plugin.files

import com.mcos.sdk.*
import kotlinx.serialization.json.*

/**
 * Files plugin: file.list, file.search, photo.search, photo.compress.
 * Matches [04-plugin-sdk.md §17].
 */
class FilesPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.files",
        name = "Files Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "File listing, photo search and compression",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.mcos.plugin.files.FilesPlugin",
        commands = listOf(
            CommandManifestEntry(
                id = "file.list", version = "1.0.0",
                title = "List Files", description = "List files in a directory",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("""file.list(path="/sdcard/DCIM")""")
            ),
            CommandManifestEntry(
                id = "file.search", version = "1.0.0",
                title = "Search Files", description = "Search files by name pattern",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("""file.search(pattern="*.jpg")""")
            ),
            CommandManifestEntry(
                id = "photo.search", version = "1.0.0",
                title = "Search Photos", description = "Search photos by date/metadata",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("""photo.search(date="today")""")
            ),
            CommandManifestEntry(
                id = "photo.compress", version = "1.0.0",
                title = "Compress Photo", description = "Compress photos by quality",
                sideEffectClass = SideEffectClass.write,
                idempotent = false,
                examples = listOf(
                    "photo.compress(quality=80)",
                    """photo.compress(quality=80, uris=["content://1","content://2"])"""
                )
            )
        ),
        namespaces = listOf("file", "photo")
    )

    override suspend fun onLoad(services: HostServices) {}
    override suspend fun onUnload() {}

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "file.list" to FileListHandler,
        "file.search" to FileSearchHandler,
        "photo.search" to PhotoSearchHandler,
        "photo.compress" to PhotoCompressHandler
    )
}

object FileListHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: files listed"))
    }
}

object FileSearchHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: files found"))
    }
}

object PhotoSearchHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: photos found"))
    }
}

object PhotoCompressHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: photo compressed"))
    }
}
