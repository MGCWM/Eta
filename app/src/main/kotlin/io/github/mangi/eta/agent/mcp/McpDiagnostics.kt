package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务器诊断入口。
 *
 * 对应 Minis 的 `ping` / `tools` / `call` / `info` 四个动作：装完一个服务器先能自己验通，
 * 而不是只在界面上看到一个「刷新失败」。
 */
internal object McpDiagnostics {

    /** 探活：握手 + 记录往返耗时。 */
    fun ping(
        server: McpServerSetting,
        bearerToken: String?,
        processContext: McpProcessContext,
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        return withTransport(server, bearerToken, processContext) { transport ->
            val handshake = transport.handshake()
            val roundTrip = transport.ping()
            base(server)
                .put("ok", true)
                .put("transport", server.transport)
                .put("endpoint", describeEndpoint(server))
                .put("protocol_version", handshake.protocolVersion)
                .put("server_name", handshake.serverName)
                .put("server_version", handshake.serverVersion)
                .put("instructions", handshake.instructions)
                .put("handshake_ms", handshake.roundTripMs)
                .put("ping_ms", roundTrip)
                .put("total_ms", System.currentTimeMillis() - startedAt)
                .put("capabilities", handshake.capabilities)
                .also { payload ->
                    missingVariables(server).takeIf { it.isNotEmpty() }?.let {
                        payload.put("missing_variables", JSONArray(it))
                    }
                }
        }
    }

    /** 列出工具。分页拉完，并按 `enabledToolNames` 标注每项是否启用。 */
    fun listTools(
        server: McpServerSetting,
        bearerToken: String?,
        processContext: McpProcessContext,
        maxTools: Int = MAX_DIAGNOSTIC_TOOLS,
    ): JSONObject = withTransport(server, bearerToken, processContext) { transport ->
        val handshake = transport.handshake()
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_PAGES && tools.size < maxTools) {
            val page = transport.listTools(cursor)
            tools += page.tools
            cursor = page.nextCursor
            pages += 1
            if (cursor == null) break
        }
        val items = JSONArray()
        tools.take(maxTools).forEach { tool ->
            items.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("title", tool.title)
                    .put("description", tool.description.take(MAX_DESCRIPTION_CHARS))
                    .put("enabled", tool.name in server.enabledToolNames)
                    .put("read_only", tool.readOnlyHint ?: JSONObject.NULL)
                    .put("destructive", tool.destructiveHint ?: JSONObject.NULL)
                    .put("model_name", McpToolNaming.modelName(server.name, tool.name, mutableSetOf()))
            )
        }
        base(server)
            .put("ok", true)
            .put("transport", server.transport)
            .put("protocol_version", handshake.protocolVersion)
            .put("count", tools.size)
            .put("truncated", tools.size >= maxTools)
            .put("tools", items)
    }

    /** 直接调用一个工具，用于在设置页验证参数与返回值。 */
    fun call(
        server: McpServerSetting,
        bearerToken: String?,
        processContext: McpProcessContext,
        toolName: String,
        arguments: JSONObject,
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        return withTransport(server, bearerToken, processContext) { transport ->
            transport.handshake()
            val result = transport.callTool(toolName, arguments)
            base(server)
                .put("ok", !result.optBoolean("isError", false))
                .put("transport", server.transport)
                .put("tool", toolName)
                .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                .put("result", result)
        }
    }

    /** 配置回显，不含任何密钥。 */
    fun describe(server: McpServerSetting): JSONObject = base(server)
        .put("ok", true)
        .put("transport", server.transport)
        .put("endpoint", describeEndpoint(server))
        .put("protocol_mode", server.protocolMode)
        .put("authorization_type", server.authorizationType)
        .put("enabled", server.enabled)
        .put("enabled_tool_count", server.enabledToolNames.size)
        .put("discovered_tool_count", server.tools.size)
        .put("startup_timeout_ms", server.startupTimeoutMs)
        .put("request_timeout_ms", server.requestTimeoutMs)
        .put("last_refreshed_at", server.lastRefreshedAt ?: JSONObject.NULL)
        .put("last_protocol_version", server.lastProtocolVersion ?: JSONObject.NULL)
        .put("headers", JSONArray(server.headers.map { it.substringBefore(':').trim() }))
        .put("environment", JSONArray(server.environment.map { it.substringBefore('=').trim() }))
        .also { payload ->
            missingVariables(server).takeIf { it.isNotEmpty() }?.let {
                payload.put("missing_variables", JSONArray(it))
            }
        }

    private fun <T> withTransport(
        server: McpServerSetting,
        bearerToken: String?,
        processContext: McpProcessContext,
        block: (McpTransport) -> T,
    ): T {
        val transport = McpTransportFactory.create(server, bearerToken, processContext)
        return try {
            block(transport)
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun base(server: McpServerSetting): JSONObject = JSONObject()
        .put("server", server.name)
        .put("server_id", server.id)

    private fun describeEndpoint(server: McpServerSetting): String = if (server.isStdio) {
        (listOf(server.command) + server.args).joinToString(" ")
    } else {
        server.url
    }

    private fun missingVariables(server: McpServerSetting): List<String> =
        (server.environment + server.headers).flatMap(McpEnv::missingVariables).distinct()

    private const val MAX_DIAGNOSTIC_TOOLS = 200
    private const val MAX_PAGES = 16
    private const val MAX_DESCRIPTION_CHARS = 400
}

/** 把传输层异常转成统一诊断载荷。 */
internal fun McpTransportException.toDiagnostic(server: McpServerSetting): JSONObject =
    toPayload(server.name)
        .put("transport", server.transport)
        .put("endpoint", if (server.isStdio) {
            (listOf(server.command) + server.args).joinToString(" ")
        } else {
            server.url
        })

/** 猜测传输类型，供配置导入与界面默认值共用。 */
internal fun inferTransport(rawUrl: String, rawCommand: String): String =
    if (rawUrl.isBlank() && rawCommand.isNotBlank()) McpTransportType.STDIO else McpTransportType.HTTP
