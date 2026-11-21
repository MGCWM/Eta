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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 从 Claude Desktop / Minis 兼容的 `mcpServers` JSON 批量导入。
 *
 * 粘贴一段现成配置就能把 HTTP 与 STDIO 服务器一起建好，不用逐字段手填。
 * 对话框自带文本、忙碌态与结果区，调用方只需要提供落库逻辑。
 */
@Composable
internal fun McpImportDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String) -> ImportOutcome,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.mcp_import_json),
                    style = MiuixTheme.textStyles.title3,
                )
                Text(
                    text = stringResource(R.string.mcp_import_json_hint),
                    style = MiuixTheme.textStyles.footnote1,
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "JSON",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp),
                )
                message?.let {
                    SelectionContainer {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                            color = if (failed) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        enabled = !busy,
                        onClick = onDismiss,
                    )
                    TextButton(
                        text = if (busy) {
                            stringResource(R.string.mcp_connecting)
                        } else {
                            stringResource(R.string.mcp_import_action)
                        },
                        enabled = !busy && text.isNotBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val outcome = runCatching { onImport(text) }
                                    .getOrElse { ImportOutcome(0, listOf(it.message.orEmpty()), true) }
                                busy = false
                                failed = outcome.failed
                                message = buildString {
                                    append(outcome.summary)
                                    outcome.warnings.forEach { warning ->
                                        append('\n')
                                        append("· ")
                                        append(warning)
                                    }
                                }
                                if (!outcome.failed) onDismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 导入结果：成功条数、逐条警告、是否整体失败。 */
internal data class ImportOutcome(
    val added: Int,
    val warnings: List<String> = emptyList(),
    val failed: Boolean = false,
) {
    val summary: String
        get() = if (failed) "导入失败" else "已导入 $added 个服务器"
}
