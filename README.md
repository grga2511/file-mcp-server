# file-mcp-server

A standalone Kotlin MCP server that provides tools for listing files
and extracting readable text from files.

The server uses Ktor CIO and MCP Streamable HTTP. File format
detection and text extraction are handled by Apache Tika.

## Related project

The server can be used by the following Koog client application:

https://github.com/grga2511/FileSorterAgent

Other compatible MCP clients can also connect to the server.
The server is not tied to a single agent project.

## Requirements

- JDK 21
- IntelliJ IDEA, recommended
- Internet access for downloading dependencies
- Read access to the directories requested by clients

The Gradle Wrapper is included.

This server does not call an LLM and does not require a Google,
OpenAI or other LLM API key. LLM configuration belongs to the
agent application.

## Configuration

Configure the following environment variables:

| Variable | Required | Purpose |
|---|---|---|
| MCP_HOST | Yes | Network interface to bind to |
| MCP_PORT | Yes | HTTP port |
| MCP_PATH | Yes | MCP endpoint path |

For local development:

```text
MCP_HOST=127.0.0.1;MCP_PORT=8080;MCP_PATH=/mcp
```

In IntelliJ IDEA, open:

**Run → Edit Configurations → the server run configuration
→ Environment variables**

With these values, the MCP endpoint is:

```text
http://127.0.0.1:8080/mcp
```

This is an MCP protocol endpoint, not a regular web page.

## Running

1. Clone the repository:

   ```powershell
   git clone https://github.com/grga2511/file-mcp-server.git
   cd file-mcp-server
   ```

2. Open the project in IntelliJ IDEA.
3. Select JDK 21 and synchronize Gradle.
4. Configure `MCP_HOST`, `MCP_PORT` and `MCP_PATH`.
5. Run the `main()` function in `FileMcpServer.kt`.

Alternatively, set the variables in PowerShell and use Gradle:

```powershell
$env:MCP_HOST = "127.0.0.1"
$env:MCP_PORT = "8080"
$env:MCP_PATH = "/mcp"

.\gradlew.bat run
```

Keep the server running while using connected agents.
Stop it with IntelliJ IDEA's Stop button or Ctrl+C in the terminal.

## Connecting FileSorterAgent

Start this server first.

In the FileSorterAgent run configuration, set:

```text
MCP_URL=http://127.0.0.1:8080/mcp
```

Configure the agent's API key and model separately, following
its README.

The server runs independently. Starting or exiting an agent does
not automatically start or stop this server.

## Directory selection

The server does not use a fixed startup directory or `MCP_ROOT`.

Each tool call supplies:

- `projectRoot`: an absolute directory path
- `directory`: a path relative to that root

The server combines them to determine the requested directory.

Example:

```json
{
  "projectRoot": "C:\\Apps\\FileSorterAgent",
  "directory": "Testing"
}
```

This targets:

```text
C:\Apps\FileSorterAgent\Testing
```

Use `.` for `directory` to target the root itself.

Paths always refer to the server machine's filesystem.
Sending a path does not upload files from the client.

Different clients can supply different roots and directories
without restarting the server.

## Available tools

### list_files

Lists regular files directly inside the requested directory,
sorted by name.

Required arguments:

| Argument | Type | Description |
|---|---|---|
| projectRoot | string | Absolute root directory on the server machine |
| directory | string | Directory relative to the supplied root |

Example arguments:

```json
{
  "projectRoot": "C:\\Apps\\FileSorterAgent",
  "directory": "Testing"
}
```

Example text result:

```text
notes.txt
sample-2-mb.pdf
settings.conf
```

The listing is not recursive and does not include subdirectories.

### read_text_file

Detects the file format and attempts to extract readable text
using Apache Tika.

Required arguments:

| Argument | Type | Description |
|---|---|---|
| projectRoot | string | Absolute root directory on the server machine |
| directory | string | Directory relative to the supplied root |
| fileName | string | File name without a directory path |

Example arguments:

```json
{
  "projectRoot": "C:\\Apps\\FileSorterAgent",
  "directory": "Testing",
  "fileName": "settings.conf"
}
```

The text result includes:

- File name
- Resolved directory
- Detected media type
- Extracted text, when available

Text extraction is limited to 20,000 characters by the current
parser call. Files exceeding that extraction limit may produce
an extraction error.

Supported formats depend on the installed Tika parsers.
A PDF containing only scanned images may require additional
OCR configuration; readable text is not guaranteed for every file.

The tool extracts text. It does not execute source code or scripts.

## Path validation and access

The server checks that:

- Required arguments are non-blank strings.
- `projectRoot` is an absolute path to an existing directory.
- `directory` is relative to the supplied root.
- The normalized and resolved directory stays inside that root.
- The requested file is a regular file.
- `fileName` contains no directory separators or colon.

The tools do not modify, move or delete files.

The root is supplied by the client. It is not a server-side
allowlist, so clients can request different directories accessible
to the server process.

The current application does not configure authentication.
Use the loopback address for local development and do not expose
the endpoint publicly without adding access controls.

## Tool discovery and JSON

Tool names, descriptions and input schemas are published through MCP.

Each tool schema specifies its required arguments, allowing clients
to discover which values must be supplied.

The MCP SDK handles JSON message serialization and deserialization.
Tool handlers receive arguments as a JSON object.

Results are returned as `CallToolResult` objects containing
`TextContent`. Plain text results do not need to be parsed as JSON
by application code.

## Logging

Tool activity is printed to the server console, including:

- Directory listing calls
- Successful text extraction calls
- Resolved directories and detected file formats

An idle server remaining active is expected: it is waiting for
client requests.

## Troubleshooting

- **Missing environment variable:** configure all three server
  variables in the configuration used to launch the application.
- **Address already in use:** stop the previous server instance
  or select another port. Update the client's `MCP_URL` if needed.
- **Could not list or open the directory:** check the supplied
  paths and filesystem permissions on the server machine.
- **File not found:** use an exact file name returned by `list_files`.
- **Could not extract text:** the file may be unreadable,
  unsupported, damaged or exceed the extraction limit.
- **Client cannot connect:** check that the server is running
  and that the host, port and endpoint match.
- **Gradle download or certificate errors:** configure Gradle's
  proxy and trusted certificates for your network.
