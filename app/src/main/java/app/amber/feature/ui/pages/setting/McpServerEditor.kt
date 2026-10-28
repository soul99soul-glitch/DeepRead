package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderLabeledField
import app.amber.feature.ui.pages.setting.components.ProviderPillSeg
import app.amber.feature.ui.pages.setting.components.ProviderSecretField
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSmallIconButton
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.hooks.EditState
import app.amber.feature.ui.hooks.EditStateContent
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun McpServerConfigModal(state: EditState<McpServerConfig>) {
    state.EditStateContent { config, updateValue ->
        val pagerState = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LocalAmberTokens.current.raised,
            dragHandle = { ProviderSheetGrabber() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_basic_settings))
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_tools))
                        }
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> {
                            McpCommonOptionsConfigure(
                                config = config,
                                update = updateValue
                            )
                        }

                        1 -> {
                            McpToolsConfigure(
                                config = config,
                                update = updateValue,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    ProviderCommandButton(
                        text = stringResource(R.string.setting_mcp_page_save),
                        accent = true,
                        enabled = config.commonOptions.name.isNotBlank(),
                        onClick = { state.confirm() },
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun McpCommonOptionsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit
) {
    val enableLabel = stringResource(R.string.setting_mcp_page_enable)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setting_mcp_page_enable), style = LocalAmberType.current.body)
                Text(
                    stringResource(R.string.setting_mcp_page_enable_desc),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
            }
            Switch(
                checked = config.commonOptions.enable,
                modifier = Modifier.semantics { contentDescription = enableLabel },
                onCheckedChange = { enabled ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(
                                commonOptions = config.commonOptions.copy(enable = enabled)
                            )
                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                commonOptions = config.commonOptions.copy(enable = enabled)
                            )
                        }
                    )
                },
            )
        }

        ProviderLabeledField(stringResource(R.string.setting_mcp_page_name)) {
            ProviderTextField(
                value = config.commonOptions.name,
                onValueChange = { name ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )
                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )
                        }
                    )
                },
                placeholder = stringResource(R.string.setting_mcp_page_name_placeholder),
            )
        }

        val currentTypeIndex = when (config) {
            is McpServerConfig.StreamableHTTPServer -> 0
            is McpServerConfig.SseTransportServer -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setting_mcp_page_transport_type), style = LocalAmberType.current.body)
            Text(
                stringResource(R.string.setting_mcp_page_transport_type_desc),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
            ProviderPillSeg(
                options = listOf(
                    ProviderSegOption(0, "Streamable HTTP"),
                    ProviderSegOption(1, "SSE"),
                ),
                selected = currentTypeIndex,
                onSelected = { index ->
                    val newConfig = when (index) {
                        0 -> McpServerConfig.StreamableHTTPServer(
                            id = config.id,
                            commonOptions = config.commonOptions,
                            url = when (config) {
                                is McpServerConfig.SseTransportServer -> config.url
                                is McpServerConfig.StreamableHTTPServer -> config.url
                            }
                        )
                        else -> McpServerConfig.SseTransportServer(
                            id = config.id,
                            commonOptions = config.commonOptions,
                            url = when (config) {
                                is McpServerConfig.SseTransportServer -> config.url
                                is McpServerConfig.StreamableHTTPServer -> config.url
                            }
                        )
                    }
                    update(newConfig)
                },
                mono = true,
            )
        }

        ProviderLabeledField(stringResource(R.string.setting_mcp_page_url_label)) {
            ProviderTextField(
                value = when (config) {
                    is McpServerConfig.SseTransportServer -> config.url
                    is McpServerConfig.StreamableHTTPServer -> config.url
                },
                onValueChange = { url ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(url = url)
                            is McpServerConfig.StreamableHTTPServer -> config.copy(url = url)
                        }
                    )
                },
                placeholder = when (config) {
                    is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_placeholder)
                    is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_placeholder)
                },
                mono = true,
            )
        }
        Text(
            when (config) {
                is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_desc)
                is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_desc)
            },
            style = LocalAmberType.current.secondary,
            color = LocalAmberTokens.current.ink3,
        )

        // 请求头配置
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setting_mcp_page_custom_headers), style = LocalAmberType.current.body)
            Text(
                stringResource(R.string.setting_mcp_page_custom_headers_desc),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                config.commonOptions.headers.forEachIndexed { index, header ->
                    var headerName by remember(header.first) { mutableStateOf(header.first) }
                    var headerValue by remember(header.second) { mutableStateOf(header.second) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            ProviderLabeledField(stringResource(R.string.setting_mcp_page_header_name)) {
                                ProviderTextField(
                                value = headerName,
                                onValueChange = {
                                    headerName = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] =
                                        it.trim() to updatedHeaders[index].second
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                placeholder = stringResource(R.string.setting_mcp_page_header_name_placeholder),
                                mono = true,
                                )
                            }
                            ProviderLabeledField(stringResource(R.string.setting_mcp_page_header_value)) {
                                if (headerName.equals("authorization", ignoreCase = true)) {
                                    ProviderSecretField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].first to it
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                        placeholder = stringResource(R.string.setting_mcp_page_header_value_placeholder),
                                    )
                                } else {
                                    ProviderTextField(
                                        value = headerValue,
                                        onValueChange = {
                                            headerValue = it
                                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                                            updatedHeaders[index] = updatedHeaders[index].first to it
                                            update(
                                                when (config) {
                                                    is McpServerConfig.SseTransportServer -> config.copy(
                                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                                    )
                                                    is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                                    )
                                                }
                                            )
                                        },
                                        placeholder = stringResource(R.string.setting_mcp_page_header_value_placeholder),
                                        mono = true,
                                    )
                                }
                            }
                        }
                        ProviderSmallIconButton(
                            imageVector = Lucide.Trash2,
                            contentDescription = stringResource(R.string.setting_mcp_page_delete_header),
                            onClick = {
                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            update(
                                when (config) {
                                    is McpServerConfig.SseTransportServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )

                                    is McpServerConfig.StreamableHTTPServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )
                                }
                            )
                            },
                        )
                    }
                }

                ProviderGhostButton(
                    text = stringResource(R.string.setting_mcp_page_add_header),
                    imageVector = Lucide.Plus,
                    onClick = {
                        val updatedHeaders = config.commonOptions.headers.toMutableList()
                        updatedHeaders.add("" to "")
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )

                                is McpServerConfig.StreamableHTTPServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )
                            }
                        )
                    },
                    modifier = Modifier.align(Alignment.Start),
                )
            }
        }
    }
}
