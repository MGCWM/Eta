package io.github.mangi.eta.agent.mcp

import android.content.Context
import java.io.File

/**
 * MCP 运行时上下文。
 *
 * STDIO 服务器要有可写工作目录与 pid 文件位置；这里统一持有 Application Context，
 * 与 [io.github.mangi.eta.data.repository.McpServerRepository] 一样在 `EtaApp.onCreate` 里初始化。
 */
internal object McpRuntime {
    @Volatile
    private var applicationContext: Context? = null

    fun init(context: Context) {
        if (applicationContext == null) applicationContext = context.applicationContext
    }

    fun processContext(): McpProcessContext {
        val context = applicationContext ?: return McpProcessContext.EMPTY
        val workspace = File(context.filesDir, "eta-mcp-workspace").apply { runCatching { mkdirs() } }
        val pidDirectory = File(context.cacheDir, "eta-mcp").apply { runCatching { mkdirs() } }
        return McpProcessContext(
            workspace = workspace,
            pidDirectory = pidDirectory,
            baseEnvironment = mapOf(
                "HOME" to workspace.absolutePath,
                "TMPDIR" to context.cacheDir.absolutePath,
            ),
        )
    }
}
