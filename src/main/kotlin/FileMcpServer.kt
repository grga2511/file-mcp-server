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
import kotlinx.serialization.json.JsonObject
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
    val host = requiredEnv("MCP_HOST")
    val port = requiredEnv("MCP_PORT").toInt()
    val path = requiredEnv("MCP_PATH")

    embeddedServer(CIO, host = host, port = port) {
        mcpStreamableHttp(path = path) {
            createFileServer()
        }
    }.start(wait = true)
}

private fun createFileServer(): Server {
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
        description = "List files in a directory relative to the project root.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("projectRoot", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute project root path on the MCP server machine."
                    )
                })
                put("directory", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Directory relative to the project root, such as Testing or Testing/config. Use . for the project root."
                    )
                })
            },
            required = listOf("projectRoot", "directory")
        )
    ) { request ->
        try {
            val directory = resolveDirectory(request.arguments)

            val names = Files.list(directory).use { entries ->
                entries
                    .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }

            System.err.println("MCP: list_files ($directory)")

            toolResult(
                names.joinToString("\n").ifEmpty { "No files found." }
            )
        } catch (e: IllegalArgumentException) {
            toolResult(
                e.message ?: "Invalid directory.",
                isError = true
            )
        } catch (_: IOException) {
            toolResult(
                "Could not list the directory.",
                isError = true
            )
        }
    }

    server.addTool(
        name = "read_text_file",
        description = "Detect a file's format and extract readable text from a directory relative to the project root.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("projectRoot", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute project root path on the MCP server machine."
                    )
                })
                put("directory", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Directory relative to the project root. Use the same value as in list_files."
                    )
                })
                put("fileName", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "A file name from list_files, without a directory path."
                    )
                })
            },
            required = listOf("projectRoot", "directory", "fileName")
        )
    ) { request ->
        val directory = try {
            resolveDirectory(request.arguments)
        } catch (e: IllegalArgumentException) {
            return@addTool toolResult(
                e.message ?: "Invalid directory.",
                isError = true
            )
        } catch (_: IOException) {
            return@addTool toolResult(
                "Could not open the directory.",
                isError = true
            )
        }

        val fileName = try {
            requiredArgument(request.arguments, "fileName")
        } catch (e: IllegalArgumentException) {
            return@addTool toolResult(
                e.message ?: "Invalid file name.",
                isError = true
            )
        }

        if (
            fileName == "." ||
            fileName == ".." ||
            fileName.any { it in "/\\:" }
        ) {
            return@addTool toolResult(
                "Provide a file name without a path.",
                isError = true
            )
        }

        val file = try {
            directory.resolve(fileName)
        } catch (_: InvalidPathException) {
            return@addTool toolResult(
                "Invalid file name.",
                isError = true
            )
        }

        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) {
            return@addTool toolResult(
                "File not found in the requested directory.",
                isError = true
            )
        }

        try {
            val mediaType = tika.detect(file)

            val metadata = Metadata().apply {
                set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
            }

            val content = Files.newInputStream(file).use { input ->
                tika.parseToString(input, metadata, 20_000).trim()
            }

            System.err.println(
                "MCP: read_text_file ($directory, $fileName, $mediaType)"
            )

            toolResult(
                "File: $fileName\n" +
                        "Directory: $directory\n" +
                        "Detected format: $mediaType\n\n" +
                        content.ifBlank {
                            "No readable text could be extracted."
                        }
            )
        } catch (_: IOException) {
            toolResult(
                "Could not read the file.",
                isError = true
            )
        } catch (_: TikaException) {
            toolResult(
                "Could not extract text from the file.",
                isError = true
            )
        }
    }

    return server
}

private fun resolveDirectory(arguments: JsonObject?): Path {
    val projectRootValue = requiredArgument(arguments, "projectRoot")
    val directoryValue = requiredArgument(arguments, "directory")

    val projectRootPath = Path.of(projectRootValue)

    require(projectRootPath.isAbsolute) {
        "The projectRoot argument must be an absolute path."
    }

    val projectRoot = projectRootPath.toRealPath()

    require(Files.isDirectory(projectRoot)) {
        "The projectRoot argument must point to a directory."
    }

    val relativeDirectory = Path.of(directoryValue)

    require(!relativeDirectory.isAbsolute && relativeDirectory.root == null) {
        "The directory argument must be relative to the project root."
    }

    val directory = projectRoot.resolve(relativeDirectory).normalize()

    require(directory.startsWith(projectRoot)) {
        "The directory must remain inside the project root."
    }

    val realDirectory = directory.toRealPath()

    require(realDirectory.startsWith(projectRoot)) {
        "The directory must not resolve outside the project root."
    }

    require(Files.isDirectory(realDirectory, NOFOLLOW_LINKS)) {
        "The directory argument must point to a directory."
    }

    return realDirectory
}

private fun requiredArgument(
    arguments: JsonObject?,
    name: String
): String {
    val value = (arguments?.get(name) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    require(!value.isNullOrBlank()) {
        "The $name argument must be a non-blank string."
    }

    return value
}

private fun toolResult(
    text: String,
    isError: Boolean = false
): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text)),
        isError = isError
    )

private fun requiredEnv(name: String): String =
    System.getenv(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: error("Missing environment variable: $name")