package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.McpDiscoveryTruncation
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository

/**
 * 服务器生命周期：发现工具、刷新缓存、诊断。
 *
 * 统一走 [McpTransportFactory]，HTTP 与 STDIO 的刷新、探活、调试是同一条代码路径。
 * 分页与截断判定沿用上游 3.4.4 的语义：工具数 / 页数 / 目录体积三重上限，外加游标回环检测。
 */
internal object McpServerManager {

    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
        processContext: McpProcessContext = McpRuntime.processContext(),
    ): McpServerSetting {
        val transport = McpTransportFactory.create(server, bearerToken, processContext)
        return transport.use { client ->
            // 先列工具：listTools 内部完成协议协商，避免握手与发现各发一次请求。
            val tools = mutableListOf<McpToolDefinition>()
            val seenCursors = hashSetOf<String>()
            var cursor: String? = null
            var cacheTtlMs: Long? = null
            var catalogChars = 0
            var truncation: McpDiscoveryTruncation? = null

            loop@ for (page in 0 until MAX_LIST_PAGES) {
                val listed = client.listTools(cursor)
                cacheTtlMs = listed.cacheTtlMs ?: cacheTtlMs
                for (tool in listed.tools) {
                    if (tools.size >= MAX_DISCOVERED_TOOLS) {
                        truncation = McpDiscoveryTruncation.TOOL_LIMIT
                        break@loop
                    }
                    val estimated = estimatedToolChars(tool)
                    if (tools.isNotEmpty() && catalogChars + estimated > MAX_CATALOG_CHARS) {
                        truncation = McpDiscoveryTruncation.SIZE_LIMIT
                        break@loop
                    }
                    tools += tool
                    catalogChars += estimated
                }
                // 规范要求把游标当不透明字符串：空串也是合法游标，仅缺失或 null 表示结束。
                val next = listed.nextCursor ?: break@loop
                if (!seenCursors.add(next)) {
                    truncation = McpDiscoveryTruncation.CURSOR_LOOP
                    break@loop
                }
                cursor = next
            }
            if (truncation == null && cursor != null) {
                truncation = McpDiscoveryTruncation.PAGE_LIMIT
            }

            truncation?.let { reason ->
                AndroidAgentLogger.warn(
                    "MCP tool discovery truncated: serverId=${server.id}, " +
                        "imported=${tools.size}, reason=${reason.wireValue}",
                )
            }

            val availableNames = tools.mapTo(mutableSetOf()) { it.name }
            val refreshedAt = System.currentTimeMillis()
            val handshake = client.handshake()
            server.copy(
                transport = server.transport,
                tools = tools,
                // 首次发现时 enabledToolNames 为空，按「全部启用」落库，避免新装的服务器一个工具都调不动。
                enabledToolNames = if (server.enabledToolNames.isEmpty()) {
                    availableNames
                } else {
                    server.enabledToolNames.intersect(availableNames)
                },
                lastRefreshedAt = refreshedAt,
                // STDIO 没有会话协商版本，记录握手返回的协议版本，便于界面展示。
                lastProtocolVersion = handshake.protocolVersion,
                toolsExpireAt = cacheTtlMs?.let { ttl ->
                    if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
                },
                toolsTruncationReason = truncation,
            )
        }
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            AppStrings.get(R.string.mcp_err_server_not_found, "MCP 服务器不存在")
        }
        val refreshed = discover(server, McpServerRepository.bearerToken(serverId))
        McpServerRepository.update(refreshed)
        return refreshed
    }

    /** 探活，返回可直接展示的诊断载荷。 */
    suspend fun ping(serverId: String): org.json.JSONObject {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            AppStrings.get(R.string.mcp_err_server_not_found, "MCP 服务器不存在")
        }
        val token = McpServerRepository.bearerToken(serverId)
        val context = McpRuntime.processContext()
        return runCatching { McpDiagnostics.ping(server, token, context) }
            .getOrElse { failure ->
                (failure as? McpTransportException)?.toDiagnostic(server)
                    ?: org.json.JSONObject()
                        .put("ok", false)
                        .put("server", server.name)
                        .put("stage", McpStage.LAUNCH)
                        .put("code", "MCP_DIAGNOSTIC_FAILED")
                        .put("message", failure.message.orEmpty())
            }
    }

    /** 拉取服务器声明的全部工具，不写入数据库。 */
    suspend fun probeTools(serverId: String): org.json.JSONObject {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            AppStrings.get(R.string.mcp_err_server_not_found, "MCP 服务器不存在")
        }
        val token = McpServerRepository.bearerToken(serverId)
        val context = McpRuntime.processContext()
        return runCatching { McpDiagnostics.listTools(server, token, context) }
            .getOrElse { failure ->
                (failure as? McpTransportException)?.toDiagnostic(server)
                    ?: org.json.JSONObject()
                        .put("ok", false)
                        .put("server", server.name)
                        .put("stage", McpStage.LIST_TOOLS)
                        .put("code", "MCP_LIST_FAILED")
                        .put("message", failure.message.orEmpty())
            }
    }

    /** 在设置页直接调用工具。 */
    suspend fun invoke(serverId: String, toolName: String, argumentsJson: String): org.json.JSONObject {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            AppStrings.get(R.string.mcp_err_server_not_found, "MCP 服务器不存在")
        }
        val arguments = runCatching { org.json.JSONObject(argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return org.json.JSONObject()
                    .put("ok", false)
                    .put("server", server.name)
                    .put("stage", McpStage.CALL_TOOL)
                    .put("code", "MCP_INVALID_ARGUMENT")
                    .put("message", "参数不是合法 JSON object")
            }
        val token = McpServerRepository.bearerToken(serverId)
        val context = McpRuntime.processContext()
        return runCatching {
            McpDiagnostics.call(server, token, context, toolName, arguments)
        }.getOrElse { failure ->
            (failure as? McpTransportException)?.toDiagnostic(server)
                ?: org.json.JSONObject()
                    .put("ok", false)
                    .put("server", server.name)
                    .put("stage", McpStage.CALL_TOOL)
                    .put("code", "MCP_CALL_FAILED")
                    .put("message", failure.message.orEmpty())
        }
    }

    private fun estimatedToolChars(tool: McpToolDefinition): Int =
        tool.name.length + tool.title.length + tool.description.length +
            tool.inputSchemaJson.length + 128

    private const val MAX_DISCOVERED_TOOLS = 2_048
    private const val MAX_LIST_PAGES = 64
    private const val MAX_CATALOG_CHARS = 2_000_000
}
