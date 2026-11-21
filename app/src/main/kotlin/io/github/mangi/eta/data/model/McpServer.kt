package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

internal object McpProtocolMode {
    const val AUTO = "auto"
    const val LATEST = "2026-07-28"
    const val LEGACY = "2025-11-25"
}

internal object McpAuthorizationType {
    const val NONE = "none"
    const val BEARER = "bearer"
}

/** 传输方式。HTTP 走网络，STDIO 起本地子进程。 */
internal object McpTransportType {
    const val HTTP = "http"
    const val STDIO = "stdio"
}


internal enum class McpDiscoveryTruncation(val wireValue: String) {
    TOOL_LIMIT("tool_limit"),
    PAGE_LIMIT("page_limit"),
    SIZE_LIMIT("size_limit"),
    CURSOR_LOOP("cursor_loop"),
    ;

    companion object {
        fun fromWireValue(value: String?): McpDiscoveryTruncation? =
            entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
internal data class McpToolDefinition(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchemaJson: String,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

@Serializable
internal data class McpServerSetting(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val protocolMode: String = McpProtocolMode.AUTO,
    val authorizationType: String = McpAuthorizationType.NONE,
    val tools: List<McpToolDefinition> = emptyList(),
    val enabledToolNames: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val lastRefreshedAt: Long? = null,
    val lastProtocolVersion: String? = null,
    val toolsExpireAt: Long? = null,
    val toolsTruncationReason: McpDiscoveryTruncation? = null,
    // ---- STDIO 传输字段，HTTP 类型全部为默认值 ----
    val transport: String = McpTransportType.HTTP,
    val command: String = "",
    val args: List<String> = emptyList(),
    val environment: List<String> = emptyList(),
    val headers: List<String> = emptyList(),
    val requestTimeoutMs: Long? = null,
) {
    /** STDIO 类型没有 URL，展示与摘要都走这里。 */
    val endpointSummary: String
        get() = if (transport == McpTransportType.STDIO) {
            (listOf(command) + args).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            url
        }

    val activeTools: List<McpToolDefinition>
        get() = if (!enabled) {
            emptyList()
        } else {
            tools.filter { it.name in enabledToolNames }
        }
}
