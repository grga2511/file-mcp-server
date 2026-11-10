import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.tika.Tika
import org.apache.tika.exception.TikaException
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

fun main() {
    val folder = Path.of(requiredEnv("MCP_ROOT")).toRealPath()
    require(Files.isDirectory(folder)) {
        "MCP_ROOT must point to a directory."
    }

    val host = requiredEnv("MCP_HOST")
    val port = requiredEnv("MCP_PORT").toInt()
    val path = requiredEnv("MCP_PATH")

    embeddedServer(CIO, host = host, port = port) {
        mcpStreamableHttp(path = path) {
            createFileServer(folder)
        }
    }.start(wait = true)
}

private fun createFileServer(folder: Path): Server {
    val tika = Tika()

    val server = Server(
        serverInfo = Implementation("file-mcp-server", "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    server.addTool(
        name = "list_files",
        description = "List the names of files in the configured directory."
    ) {
        val names = Files.list(folder).use { entries ->
            entries
                .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

        System.err.println("MCP: list_files")
        toolResult(names.joinToString("\n").ifEmpty { "No files found." })
    }

    server.addTool(
        name = "read_text_file",
        description = "Detect a file's format and extract readable text.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("fileName", buildJsonObject {
                    put("type", "string")
                    put("description", "A file name from list_files, without a path.")
                })
            },
            required = listOf("fileName")
        )
    ) { request ->
        val fileName = (request.arguments?.get("fileName") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

        if (fileName.isNullOrBlank() ||
            fileName == "." ||
            fileName == ".." ||
            fileName.any { it in "/\\:" }
        ) {
            return@addTool toolResult("Provide a file name without a path.", isError = true)
        }

        val file = try {
            folder.resolve(fileName)
        } catch (_: InvalidPathException) {
            return@addTool toolResult("Invalid file name.", isError = true)
        }

        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) {
            return@addTool toolResult("File not found in the configured directory.", isError = true)
        }

        try {
            val mediaType = tika.detect(file)
            val metadata = Metadata().apply {
                set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
            }
            val content = Files.newInputStream(file).use { input ->
                tika.parseToString(input, metadata, 20_000).trim()
            }

            System.err.println("MCP: read_text_file ($fileName, $mediaType)")

            toolResult(
                "File: $fileName\n" +
                        "Detected format: $mediaType\n\n" +
                        content.ifBlank { "No readable text could be extracted." }
            )
        } catch (_: IOException) {
            toolResult("Could not read the file.", isError = true)
        } catch (_: TikaException) {
            toolResult("Could not extract text from the file.", isError = true)
        }
    }

    return server
}

private fun toolResult(text: String, isError: Boolean = false): CallToolResult =
    CallToolResult(content = listOf(TextContent(text)), isError = isError)

private fun requiredEnv(name: String): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing environment variable: $name")