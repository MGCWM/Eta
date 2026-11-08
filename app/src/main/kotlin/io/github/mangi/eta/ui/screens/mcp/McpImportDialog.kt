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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 从 Claude Desktop / Minis 兼容的 `mcpServers` JSON 批量导入。
 *
 * 粘贴一段现成配置就能把 HTTP 与 STDIO 服务器一起建好，不用逐字段手填。
 */
@Composable
internal fun McpImportDialog(
    value: String,
    onValueChange: (String) -> Unit,
    result: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
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
                    text = stringResource(R.string.mcp_import_json),
                    style = MiuixTheme.textStyles.title3,
                )
                Text(
                    text = stringResource(R.string.mcp_import_json_hint),
                    style = MiuixTheme.textStyles.footnote1,
                )
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = "",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp),
                )
                result?.let { message ->
                    SelectionContainer {
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
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
                        enabled = !busy && value.isNotBlank(),
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}
