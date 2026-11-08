package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository

/**
 * 服务器生命周期：发现工具、刷新缓存、诊断。
 *
 * 相对旧实现的差别：不再只认 [McpHttpTransport]，统一走 [McpTransportFactory]，
 * 因此 HTTP 与 STDIO 的刷新、探活、调试走完全相同的代码路径。
 */
internal object McpServerManager {

    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
        processContext: McpProcessContext = McpRuntime.processContext(),
    ): McpServerSetting {
        val transport = McpTransportFactory.create(server, bearerToken, processContext)
        return transport.use { client ->
            val handshake = client.handshake()
            val tools = mutableListOf<McpToolDefinition>()
            var cursor: String? = null
            var pages = 0
            var cacheTtlMs: Long? = null
            while (pages < MAX_PAGES) {
                val page = client.listTools(cursor)
                tools += page.tools
                cacheTtlMs = page.cacheTtlMs
                cursor = page.nextCursor
                pages += 1
                if (cursor == null || tools.size >= MAX_DISCOVERED_TOOLS) break
            }
            val availableNames = tools.mapTo(mutableSetOf()) { it.name }
            val refreshedAt = System.currentTimeMillis()
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
            )
        }
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(server, McpServerRepository.bearerToken(serverId))
        McpServerRepository.update(refreshed)
        return refreshed
    }

    /** 探活，返回可直接展示的诊断载荷。 */
    suspend fun ping(serverId: String): org.json.JSONObject {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
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
            "MCP 服务器不存在"
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
            "MCP 服务器不存在"
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

    private const val MAX_PAGES = 16
    private const val MAX_DISCOVERED_TOOLS = 512
}
