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

/** 传输方式。HTTP 与 STDIO 对外暴露同一套工具语义。 */
internal object McpTransportType {
    const val HTTP = "http"
    const val STDIO = "stdio"
}

internal object McpTimeouts {
    const val DEFAULT_STARTUP_MS = 60_000L
    const val DEFAULT_REQUEST_MS = 120_000L
    const val MAX_STARTUP_MS = 900_000L
    const val MAX_REQUEST_MS = 600_000L
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
    /** [McpTransportType.HTTP] 或 [McpTransportType.STDIO]。 */
    val transport: String = McpTransportType.HTTP,
    /** STDIO 启动命令，例如 `npx`、`uvx`、`node`。 */
    val command: String = "",
    /** STDIO 参数，按 argv 传递，不经 shell 二次解析。 */
    val args: List<String> = emptyList(),
    /** STDIO 环境变量，`K=V` 形式，值支持 `${VAR}` 展开。 */
    val environment: List<String> = emptyList(),
    /** HTTP 额外请求头，`K: V` 形式，值支持 `${VAR}` 展开。 */
    val headers: List<String> = emptyList(),
    /** STDIO 首次 initialize 等待上限。 */
    val startupTimeoutMs: Long = McpTimeouts.DEFAULT_STARTUP_MS,
    /** 单次列出工具或调用工具的等待上限。 */
    val requestTimeoutMs: Long = McpTimeouts.DEFAULT_REQUEST_MS,
) {
    val isStdio: Boolean get() = transport == McpTransportType.STDIO

    val activeTools: List<McpToolDefinition>
        get() = if (!enabled) {
            emptyList()
        } else {
            tools.filter { it.name in enabledToolNames }
        }

    /** 配置完整性检查，供保存与诊断共用。 */
    fun validate(): String? = when {
        name.isBlank() -> "服务器名称不能为空"
        isStdio && command.isBlank() -> "STDIO 服务器需要填写启动命令"
        !isStdio && url.isBlank() -> "HTTP 服务器需要填写地址"
        else -> null
    }
}
