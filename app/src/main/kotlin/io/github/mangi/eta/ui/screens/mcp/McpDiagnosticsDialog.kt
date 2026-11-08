package io.github.mangi.eta.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.data.model.McpServerSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MCP 服务器诊断台。
 *
 * 对应 Minis 的 `ping` / `tools` / `call`：装完一个服务器先自己验通，
 * 失败时能看到具体是哪一步、什么码、上游原话，而不是界面上一句「刷新失败」。
 */
@Composable
internal fun McpDiagnosticsDialog(
    server: McpServerSetting,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var toolName by remember { mutableStateOf("") }
    var arguments by remember { mutableStateOf("{}") }

    fun run(label: String, block: () -> JSONObject) {
        if (busy.isNotEmpty()) return
        scope.launch {
            busy = label
            output = "$label …"
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            busy = ""
            output = result.fold(
                onSuccess = { payload -> "$label\n" + payload.toString(2) },
                onFailure = { failure ->
                    "$label\n" + (failure.message ?: failure.javaClass.simpleName)
                },
            )
        }
    }

    Dialog(
        onDismissRequest = { if (busy.isEmpty()) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp)) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.mcp_diagnostics_title, server.name),
                    style = MiuixTheme.textStyles.title3,
                )
                Text(
                    text = if (server.isStdio) {
                        "stdio · ${server.command} ${server.args.joinToString(" ")}"
                    } else {
                        "http · ${server.url}"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = stringResource(R.string.mcp_diagnostic_ping),
                        enabled = busy.isEmpty(),
                        onClick = { run("ping") { McpServerManager.ping(server.id) } },
                    )
                    TextButton(
                        text = stringResource(R.string.mcp_diagnostic_tools),
                        enabled = busy.isEmpty(),
                        onClick = { run("tools") { McpServerManager.probeTools(server.id) } },
                    )
                }

                TextField(
                    value = toolName,
                    onValueChange = { toolName = it },
                    label = stringResource(R.string.mcp_diagnostic_tool_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = arguments,
                    onValueChange = { arguments = it },
                    label = stringResource(R.string.mcp_diagnostic_arguments),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = stringResource(R.string.mcp_diagnostic_call),
                    enabled = busy.isEmpty() && toolName.isNotBlank(),
                    onClick = {
                        val name = toolName
                        val args = arguments
                        run("call $name") { McpServerManager.invoke(server.id, name, args) }
                    },
                )

                if (output.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = output,
                            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.action_close),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}
