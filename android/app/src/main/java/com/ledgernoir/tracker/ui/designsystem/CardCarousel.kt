package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.ui.theme.AccentTeal

/**
 * Horizontal pager of cards with a peeking next card and page dots. Generic over
 * page content. [count] is the number of pages; [page] renders page index i.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardCarousel(
    count: Int,
    modifier: Modifier = Modifier,
    scrollToPage: Int? = null,
    page: @Composable (index: Int) -> Unit,
) {
    if (count <= 0) return  // top-level guard (no layout node emitted yet) — safe
    val state = rememberPagerState(pageCount = { count })
    LaunchedEffect(scrollToPage, count) {
        val target = scrollToPage
        if (target != null && target in 0 until count) {
            state.animateScrollToPage(target)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = state,
            contentPadding = PaddingValues(end = 36.dp),
            pageSpacing = 12.dp,
        ) { i -> page(i) }
        if (count > 1) {
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(count) { i ->
                    val selected = i == state.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (selected) 16.dp else 5.dp, height = 5.dp)
                            .clip(if (selected) RoundedCornerShape(3.dp) else CircleShape)
                            .background(if (selected) AccentTeal else androidx.compose.ui.graphics.Color(0xFF33333D)),
                    )
                }
            }
        }
    }
}
