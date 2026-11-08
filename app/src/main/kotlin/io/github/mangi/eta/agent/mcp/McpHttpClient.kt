package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONObject

/**
 * HTTP 客户端的兼容外壳。
 *
 * 实现已迁到 [McpHttpTransport]（支持自定义请求头、并发在途请求、完整错误透传），
 * 这里只保留既有测试与少量调用方使用的 `discoverTools` / `callTool` 形状，
 * 让协议协商的回归测试不必跟着重写。新代码请直接用 [McpTransportFactory]。
 */
internal class McpHttpClient(
    server: McpServerSetting,
    bearerToken: String?,
) : AutoCloseable {

    data class Discovery(
        val protocolVersion: String,
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
    )

    private val delegate = McpHttpTransport(
        server = server,
        bearerToken = bearerToken,
        extraHeaders = McpEnv.parseHeaders(server.headers),
    )

    fun discoverTools(): Discovery {
        val handshake = delegate.handshake()
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        var cacheTtlMs: Long? = null
        var pages = 0
        while (pages < MAX_PAGES) {
            val page = delegate.listTools(cursor)
            tools += page.tools
            cacheTtlMs = page.cacheTtlMs
            cursor = page.nextCursor
            pages += 1
            if (cursor == null || tools.size >= MAX_TOOLS) break
        }
        return Discovery(handshake.protocolVersion, tools, cacheTtlMs)
    }

    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject =
        delegate.callTool(tool.name, arguments)

    override fun close() = delegate.close()

    private companion object {
        const val MAX_PAGES = 16
        const val MAX_TOOLS = 512
    }
}
