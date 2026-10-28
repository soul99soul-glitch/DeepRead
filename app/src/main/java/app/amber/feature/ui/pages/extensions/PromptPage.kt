package app.amber.feature.ui.pages.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel

@Composable
fun PromptPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                navigationIcon = { BackButton() },
                title = stringResource(R.string.prompt_page_title),
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            PromptTabs(
                selected = pagerState.currentPage,
                onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> ModeInjectionTab(
                        modeInjections = settings.modeInjections,
                        onSave = vm::saveModeInjection,
                        onToggle = vm::toggleModeInjection,
                        onDelete = vm::deleteModeInjection,
                        onMove = vm::moveModeInjection,
                        onImport = vm::importModeInjection,
                    )

                    1 -> LorebookTab(
                        lorebooks = settings.lorebooks,
                        onSave = vm::saveLorebook,
                        onDelete = vm::deleteLorebook,
                        onMove = vm::moveLorebook,
                        onImport = vm::importLorebook,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val labels = listOf(
        stringResource(R.string.prompt_page_mode_injection_tab),
        stringResource(R.string.prompt_page_lorebook_tab),
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(horizontal = 16.dp, vertical = 0.dp),
        shape = RoundedCornerShape(10.dp),
        color = tokens.surface2,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min).padding(2.dp)) {
            labels.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected == index) tokens.raised else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = LocalAmberType.current.eyebrow,
                        color = if (selected == index) tokens.ink else tokens.ink3,
                    )
                }
            }
        }
    }
}
