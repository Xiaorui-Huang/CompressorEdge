package compressedge.joshattic.us.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compressedge.joshattic.us.R
import compressedge.joshattic.us.model.CompressorUiState
import compressedge.joshattic.us.ui.components.InfoCard
import compressedge.joshattic.us.ui.components.TargetSizeWarning
import compressedge.joshattic.us.ui.tabs.AudioOptionsTab
import compressedge.joshattic.us.ui.tabs.PresetsTab
import compressedge.joshattic.us.ui.tabs.QueueTab
import compressedge.joshattic.us.ui.tabs.VideoOptionsTab
import compressedge.joshattic.us.utils.expressiveScale
import compressedge.joshattic.us.viewmodel.CompressorViewModel
import kotlinx.coroutines.launch

@Composable
fun ConfigScreen(
    state: CompressorUiState,
    viewModel: CompressorViewModel,
    context: Context,
    onStartCompression: () -> Unit = { viewModel.startCompression(context) }
) {
    val tabs = if (state.isBatchMode) {
        listOf("Queue", stringResource(R.string.tab_presets), stringResource(R.string.tab_video), stringResource(R.string.tab_audio))
    } else {
        listOf(stringResource(R.string.tab_presets), stringResource(R.string.tab_video), stringResource(R.string.tab_audio))
    }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val canStart = if (state.isBatchMode) state.queue.isNotEmpty() && !state.isCompressing else state.selectedUri != null && !state.isCompressing

    // At large accessibility font scales the fixed structure (card + tabs + button)
    // can't all fit — reflow the whole portrait screen into one scrolling column.
    val largeFontScale = LocalDensity.current.fontScale >= 1.3f

    fun tabIconFor(title: String): androidx.compose.ui.graphics.vector.ImageVector = when (title) {
        "Queue" -> Icons.Outlined.FormatListBulleted
        stringResource(R.string.tab_presets) -> Icons.Outlined.BookmarkBorder
        stringResource(R.string.tab_video) -> Icons.Default.Movie
        else -> Icons.Default.MusicNote
    }

    @Composable
    fun TabContent(index: Int) {
        when (val title = tabs[index]) {
            "Queue" -> QueueTab(state, viewModel)
            stringResource(R.string.tab_presets) -> PresetsTab(state, viewModel)
            stringResource(R.string.tab_video) -> VideoOptionsTab(state, viewModel)
            stringResource(R.string.tab_audio) -> AudioOptionsTab(state, viewModel)
            else -> error("Unknown tab: $title")
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val useSplitLayout = maxWidth >= 600.dp
        // Combined max font + max display size shrinks the dp viewport until the
        // stacked estimate card leaves no room for options. Compact only then.
        val cramped = largeFontScale && maxHeight < 720.dp

        if (useSplitLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Spacer(Modifier.weight(1f))
                    tabs.forEachIndexed { index, title ->
                        NavigationRailItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = { Icon(tabIconFor(title), contentDescription = null) },
                            label = { Text(title, fontWeight = FontWeight.Bold) }
                        )
                        if (index < tabs.size - 1) {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Content and the compression button are laid out in flow so that
                // nothing is ever hidden behind an overlay at large font/display sizes.
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(top = 24.dp, bottom = 12.dp)
                    ) {
                        InfoCard(state)
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = false
                        ) { index ->
                            TabContent(index)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (state.targetSizeWarning) {
                            TargetSizeWarning(state, viewModel)
                        }
                        val interactionSource = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartCompression()
                            },
                            enabled = canStart,
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .expressiveScale(interactionSource),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(stringResource(R.string.start_compression), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (largeFontScale) {
            // Large font scale: card, tabs, and Start button are in normal flow so nothing
            // overlaps. The options pager stays in a bounded weight(1f) slot — its pages
            // scroll internally, and nesting a pager/verticalScroll inside another
            // verticalScroll is disallowed (infinite height constraints → crash).
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (cramped) 16.dp else 24.dp)
                        .padding(top = if (cramped) 4.dp else 12.dp, bottom = if (cramped) 4.dp else 8.dp)
                ) {
                    InfoCard(state, compact = cramped)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (cramped) 16.dp else 20.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val selected = pagerState.currentPage == index
                            val tabIcon = tabIconFor(title)

                            Surface(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(50),
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = tabIcon,
                                        contentDescription = title,
                                        modifier = Modifier.size(if (cramped) 22.dp else 19.dp)
                                    )
                                    if (!cramped) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { index ->
                    TabContent(index)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (cramped) 16.dp else 24.dp,
                            vertical = if (cramped) 4.dp else 8.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.targetSizeWarning) {
                        TargetSizeWarning(state, viewModel)
                    }
                    val interactionSource = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStartCompression()
                        },
                        enabled = canStart,
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .expressiveScale(interactionSource),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text(stringResource(R.string.start_compression), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Box(
                 modifier = Modifier.fillMaxSize(),
                 contentAlignment = Alignment.TopCenter
            ) {
                 Column(
                     modifier = Modifier.fillMaxSize(),
                     horizontalAlignment = Alignment.CenterHorizontally
                 ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(top = 12.dp, bottom = 8.dp)
                     ) {
                          InfoCard(state)
                     }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(52.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shadowElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tabs.forEachIndexed { index, title ->
                                val selected = pagerState.currentPage == index
                                val tabIcon = tabIconFor(title)

                                Surface(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                    shape = CircleShape,
                                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxHeight(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = tabIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Options area takes the leftover space; each tab scrolls internally,
                    // so a large InfoCard or tab bar can never squeeze it into unusability.
                    Box(modifier = Modifier.weight(1f)) {
                         HorizontalPager(
                             state = pagerState,
                             modifier = Modifier.fillMaxSize()
                         ) { index ->
                             TabContent(index)
                         }
                    }

                    // Compression button in normal layout flow so it never covers options.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (state.targetSizeWarning) {
                            TargetSizeWarning(state, viewModel)
                        }
                        val interactionSource = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartCompression()
                            },
                            enabled = canStart,
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .widthIn(max = 600.dp)
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .expressiveScale(interactionSource),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(stringResource(R.string.start_compression), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
