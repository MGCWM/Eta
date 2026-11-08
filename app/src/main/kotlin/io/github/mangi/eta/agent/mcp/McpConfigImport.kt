package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpTimeouts
import io.github.mangi.eta.data.model.McpTransportType
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claude Desktop / Minis 兼容的 `mcpServers` 配置导入导出。
 *
 * 导入后可以直接粘贴别人现成的 MCP 配置，不用在界面上一个个填；
 * 导出成同样的形状，方便备份与分享（不含任何密钥，值里只有变量名）。
 */
internal object McpConfigImport {

    data class Result(
        val servers: List<McpServerSetting>,
        val warnings: List<String>,
    )

    fun parse(raw: String): Result {
        val root = runCatching { JSONObject(raw.trim()) }.getOrElse {
            return Result(emptyList(), listOf("配置不是合法 JSON"))
        }
        val container = when {
            root.optJSONObject("mcpServers") != null -> root.getJSONObject("mcpServers")
            root.optJSONObject("mcp_servers") != null -> root.getJSONObject("mcp_servers")
            root.has("command") || root.has("url") -> JSONObject().put("imported", root)
            else -> root
        }
        val warnings = mutableListOf<String>()
        val servers = mutableListOf<McpServerSetting>()
        container.keys().forEach { name ->
            val entry = container.optJSONObject(name) ?: run {
                warnings += "「$name」不是对象，已跳过"
                return@forEach
            }
            val parsed = runCatching { toSetting(name, entry) }.getOrElse { failure ->
                warnings += "「$name」解析失败：${failure.message.orEmpty()}"
                null
            } ?: return@forEach
            if (servers.none { it.name == parsed.name }) {
                servers += parsed
            } else {
                warnings += "「$name」与已导入项重名，已跳过"
            }
        }
        return Result(servers, warnings)
    }

    fun export(servers: List<McpServerSetting>): String {
        val container = JSONObject()
        servers.forEach { server ->
            container.put(server.name, toJson(server))
        }
        return JSONObject().put("mcpServers", container).toString(2)
    }

    private fun toSetting(name: String, entry: JSONObject): McpServerSetting {
        val command = entry.optString("command").trim()
        val url = entry.optString("url").ifBlank { entry.optString("endpoint") }.trim()
        val declaredType = entry.optString("type").lowercase()
        val transport = when {
            declaredType == McpTransportType.STDIO || declaredType == "local" -> McpTransportType.STDIO
            declaredType == "http" || declaredType == "sse" || declaredType == "streamable-http" ->
                McpTransportType.HTTP
            command.isNotBlank() && url.isBlank() -> McpTransportType.STDIO
            else -> McpTransportType.HTTP
        }
        if (transport == McpTransportType.STDIO && command.isBlank()) {
            error("缺少 command")
        }
        if (transport == McpTransportType.HTTP && url.isBlank()) {
            error("缺少 url")
        }
        val environment = entry.optJSONObject("env")?.let(::flattenEnvironment).orEmpty()
        val headers = entry.optJSONObject("headers")?.let(::flattenHeaders).orEmpty()
        return McpServerSetting(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            transport = transport,
            command = command,
            args = entry.optJSONArray("args")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }.orEmpty(),
            environment = environment,
            headers = headers,
            startupTimeoutMs = entry.optLong("startup_timeout_ms", McpTimeouts.DEFAULT_STARTUP_MS)
                .coerceIn(5_000L, McpTimeouts.MAX_STARTUP_MS),
            requestTimeoutMs = entry.optLong("request_timeout_ms", McpTimeouts.DEFAULT_REQUEST_MS)
                .coerceIn(5_000L, McpTimeouts.MAX_REQUEST_MS),
            authorizationType = if (headers.any { it.startsWith("Authorization:", ignoreCase = true) }) {
                io.github.mangi.eta.data.model.McpAuthorizationType.BEARER
            } else {
                io.github.mangi.eta.data.model.McpAuthorizationType.NONE
            },
        )
    }

    private fun toJson(server: McpServerSetting): JSONObject {
        val json = JSONObject()
        if (server.isStdio) {
            json.put("type", "stdio")
            json.put("command", server.command)
            if (server.args.isNotEmpty()) json.put("args", JSONArray(server.args))
            if (server.environment.isNotEmpty()) {
                json.put("env", JSONObject().apply {
                    McpEnv.parseAssignments(server.environment).forEach { (key, value) -> put(key, value) }
                })
            }
        } else {
            json.put("type", "http")
            json.put("url", server.url)
            if (server.headers.isNotEmpty()) {
                json.put("headers", JSONObject().apply {
                    McpEnv.parseHeaders(server.headers).forEach { (key, value) -> put(key, value) }
                })
            }
        }
        json.put("startup_timeout_ms", server.startupTimeoutMs)
        json.put("request_timeout_ms", server.requestTimeoutMs)
        return json
    }

    private fun flattenEnvironment(source: JSONObject): List<String> =
        source.keys().asSequence().mapNotNull { key ->
            val value = source.optString(key)
            if (key.isBlank()) null else "$key=$value"
        }.toList()

    private fun flattenHeaders(source: JSONObject): List<String> =
        source.keys().asSequence().mapNotNull { key ->
            val value = source.optString(key)
            if (key.isBlank()) null else "$key: $value"
        }.toList()
}
