package io.github.offlineglass.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Hosts the manager's root pages and their predictive-back navigation state.
 * Bottom-bar clicks animate the pager, while a direct horizontal swipe updates
 * the selected bottom-bar item after the page settles.
 */
@Composable
internal fun BoxScope.ManagerPagePager(
    selectedPage: ManagerPage,
    onSelectedPage: (ManagerPage) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (ManagerPage) -> Unit,
) {
    val pages = ManagerPage.entries
    val pagerState = rememberPagerState(
        initialPage = pages.indexOf(selectedPage).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    val currentSelectedPage by rememberUpdatedState(selectedPage)
    val currentOnSelectedPage by rememberUpdatedState(onSelectedPage)

    LaunchedEffect(selectedPage) {
        val target = pages.indexOf(selectedPage).coerceAtLeast(0)
        if (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f) {
            pagerState.animateScrollToPage(target)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val settled = pages[page]
                if (settled != currentSelectedPage) currentOnSelectedPage(settled)
            }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        beyondViewportPageCount = pages.lastIndex,
        key = { pages[it].name },
    ) { page ->
        content(pages[page])
    }
}
