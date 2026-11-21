package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpDiscoveryTruncation
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONObject

/**
 * HTTP 客户端的兼容外壳。
 *
 * 实现已迁到 [McpHttpTransport]（支持自定义请求头、并发在途请求、完整错误透传），
 * 这里保留 `discoverTools` / `callTool` 形状，让既有协议回归测试原样跑。
 * 分页与截断判定沿用上游 3.4.4 的语义，新代码请直接用 [McpTransportFactory]。
 */
internal class McpHttpClient(
    server: McpServerSetting,
    bearerToken: String?,
) : AutoCloseable {

    data class Discovery(
        val protocolVersion: String,
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
        val truncation: McpDiscoveryTruncation? = null,
    )

    private val delegate = McpHttpTransport(
        server = server,
        bearerToken = bearerToken,
        extraHeaders = McpEnv.parseHeaders(server.headers),
    )

    fun discoverTools(): Discovery {
        val tools = mutableListOf<McpToolDefinition>()
        val seenCursors = hashSetOf<String>()
        var cursor: String? = null
        var cacheTtlMs: Long? = null
        var catalogChars = 0

        repeat(MAX_LIST_PAGES) {
            // 第一页顺带完成协议协商，不额外发握手请求。
            val page = delegate.listTools(cursor)
            cacheTtlMs = page.cacheTtlMs ?: cacheTtlMs
            for (tool in page.tools) {
                if (tools.size >= MAX_DISCOVERED_TOOLS) {
                    return result(tools, cacheTtlMs, McpDiscoveryTruncation.TOOL_LIMIT)
                }
                val estimated = estimatedToolChars(tool)
                if (tools.isNotEmpty() && catalogChars + estimated > MAX_CATALOG_CHARS) {
                    return result(tools, cacheTtlMs, McpDiscoveryTruncation.SIZE_LIMIT)
                }
                tools += tool
                catalogChars += estimated
            }
            // 规范要求把游标当不透明字符串：空串也是合法游标，仅缺失或 null 表示结束。
            val next = page.nextCursor ?: return result(tools, cacheTtlMs, null)
            if (!seenCursors.add(next)) {
                return result(tools, cacheTtlMs, McpDiscoveryTruncation.CURSOR_LOOP)
            }
            cursor = next
        }
        return result(tools, cacheTtlMs, McpDiscoveryTruncation.PAGE_LIMIT)
    }

    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject =
        delegate.callTool(tool.name, arguments, tool.inputSchemaJson)

    override fun close() = delegate.close()

    private fun result(
        tools: List<McpToolDefinition>,
        cacheTtlMs: Long?,
        truncation: McpDiscoveryTruncation?,
    ): Discovery = Discovery(
        protocolVersion = delegate.negotiatedProtocol() ?: McpProtocolMode.LATEST,
        tools = tools,
        cacheTtlMs = cacheTtlMs,
        truncation = truncation,
    )

    private fun estimatedToolChars(tool: McpToolDefinition): Int =
        tool.name.length + tool.title.length + tool.description.length +
            tool.inputSchemaJson.length + 128

    internal companion object {
        const val MAX_DISCOVERED_TOOLS = 2_048
        const val MAX_LIST_PAGES = 64
        const val MAX_CATALOG_CHARS = 2_000_000
    }
}
