package com.ddownloader.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ddownloader.android.api.TmdbApi
import com.ddownloader.android.api.tmdbPoster
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.EmptyState
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.ui.theme.pressEffect

/**
 * Discover — a shelf-based catalogue.
 *
 * Twelve categories per type (trending, popular, top rated, in cinemas / airing today and
 * genre rows), loaded in parallel so one slow category never blocks the page. Tapping a
 * poster opens the Comet streams sheet, which is where a download starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    val language = com.ddownloader.android.ui.i18n.Language.fromTag(vm.settings.value.language)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(settings.hasTmdb, vm.discoverKind) {
        if (settings.hasTmdb) vm.loadShelves(vm.discoverKind)
    }

    if (!settings.hasTmdb) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            EmptyState(
                icon = AppIcons.Explore,
                title = str.tmdbNeeded,
                body = "Add a free TMDB API key in Settings to browse curated rows here. " +
                    "Search and direct links keep working without it.",
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        str.discover,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TdColor.TextHi,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        str.discoverSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TdColor.TextLow,
                    )
                }
                Spacer(Modifier.width(10.dp))
                SegmentedType(
                    isMovie = vm.discoverKind == "movie",
                    onSelect = { kind ->
                        if (kind != vm.discoverKind) {
                            vm.discoverKind = kind
                            vm.loadShelves(kind)
                        }
                    },
                )
            }
        }

        when {
            vm.shelvesLoading && vm.shelves.isEmpty() -> {
                items(4) { SkeletonShelf() }
            }
            vm.shelvesError != null -> {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            vm.shelvesError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TdColor.Amber,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        GhostButton("Retry", icon = AppIcons.Refresh) {
                            vm.loadShelves(vm.discoverKind)
                        }
                    }
                }
            }
            else -> {
                items(vm.shelves, key = { it.title }) { shelf ->
                    ShelfRow(shelf, language) { item -> vm.openItem(item) }
                }
            }
        }
    }

    val selected = vm.streamSheetItem
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { vm.closeStreamSheet() },
            sheetState = sheetState,
            containerColor = TdColor.Ink1,
            contentColor = TdColor.TextHi,
        ) {
            StreamSheetContent(vm, selected)
        }
    }
}

/** Movies / Series switch, drawn as a small segmented control. */
@Composable
private fun SegmentedType(isMovie: Boolean, onSelect: (String) -> Unit) {
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TdColor.Ink2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SegmentButton(str.moviesLabel, isMovie) { onSelect("movie") }
        SegmentButton(str.seriesLabel, !isMovie) { onSelect("tv") }
    }
}

@Composable
private fun SegmentButton(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) TdColor.Ink0 else TdColor.TextMid,
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) TdColor.Cyan else TdColor.Ink2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** One category: header plus a horizontally scrolling row of posters. */
@Composable
private fun ShelfRow(
    shelf: AppViewModel.Shelf,
    language: com.ddownloader.android.ui.i18n.Language,
    onOpen: (TmdbApi.Item) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                com.ddownloader.android.ui.i18n.ShelfNames.shelfTitle(language, shelf.title),
                style = MaterialTheme.typography.titleMedium,
                color = TdColor.TextHi,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${shelf.items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = TdColor.TextLow,
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items, key = { "${it.mediaType}-${it.id}" }) { item ->
                PosterCard(item) { onOpen(item) }
            }
        }
    }
}

/** Poster with a rating badge and a two-line caption. */
@Composable
private fun PosterCard(item: TmdbApi.Item, onClick: () -> Unit) {
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(128.dp)
            .pressEffect(interaction)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(TdColor.Ink3),
        ) {
            AsyncImage(
                model = tmdbPoster(item.posterPath, "w342"),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, TdColor.Ink0.copy(alpha = 0.65f)),
                        ),
                    ),
            )
            if (item.voteAverage > 0) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(TdColor.Ink0.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(TdColor.Mint))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        String.format(java.util.Locale.US, "%.1f", item.voteAverage),
                        style = MaterialTheme.typography.labelSmall,
                        color = TdColor.TextHi,
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            color = TdColor.TextHi,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        item.year?.let {
            Text(
                "$it",
                style = MaterialTheme.typography.labelSmall,
                color = TdColor.TextLow,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** Placeholder shelf shown while the categories load. */
@Composable
private fun SkeletonShelf() {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .height(14.dp)
                .width(140.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TdColor.Ink3),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(4) {
                com.ddownloader.android.ui.components.ShimmerBlock(
                    modifier = Modifier.width(128.dp).aspectRatio(2f / 3f),
                    radius = 16.dp,
                )
            }
        }
    }
}
