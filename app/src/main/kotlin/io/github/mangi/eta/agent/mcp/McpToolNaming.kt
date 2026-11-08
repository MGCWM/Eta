package io.github.mangi.eta.agent.mcp

/**
 * 工具在模型侧的命名。
 *
 * 旧实现是 `mcp_<8 位服务器 id>_<工具名>_<8 位 hash>`，模型看到一长串十六进制，
 * 既分不清属于哪个服务器，也读不出工具用途。这里改成 `<服务器>__<工具>`，
 * 与 Claude / Minis 的 `server__tool` 习惯一致，同时满足模型工具名 64 字符、`[A-Za-z0-9_-]` 的限制。
 */
internal object McpToolNaming {

    private const val SERVER_MAX = 20
    private const val TOOL_MAX = 40
    private const val MAX_LENGTH = 64

    /**
     * 生成模型侧工具名。
     *
     * [used] 在调用方跨服务器共享，重名时追加序号，保证同一次 run 内全局唯一。
     */
    fun modelName(serverName: String, toolName: String, used: MutableSet<String>): String {
        val prefix = slug(serverName, SERVER_MAX).ifBlank { "server" }
        val suffix = slug(toolName, TOOL_MAX).ifBlank { "tool" }
        var candidate = "${prefix}__$suffix".take(MAX_LENGTH)
        var counter = 2
        while (!used.add(candidate)) {
            val tail = "_$counter"
            candidate = "${prefix}__$suffix".take(MAX_LENGTH - tail.length) + tail
            counter += 1
            if (counter > 99) break
        }
        return candidate
    }

    /** 反向解析：模型侧工具名 → 原始工具名。仅用于诊断展示，路由仍走快照映射。 */
    fun toolPart(modelName: String): String =
        modelName.substringAfter("__", modelName)

    fun slug(raw: String, maxLength: Int): String = raw
        .lowercase()
        .map { character ->
            if (character.isLetterOrDigit() && character.code < 128) character else '_'
        }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(maxLength)
        .trim('_')
}
