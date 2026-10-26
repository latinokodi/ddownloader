package com.ddownloader.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ddownloader.android.BuildConfig
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.EmptyState
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.SectionLabel
import com.ddownloader.android.ui.components.StatusPill
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.api.tmdbPoster

/**
 * Discover: trending titles from TMDB, resolved to torrents through the embedded
 * Comet add-on. TMDB is only the metadata/catalogue source — the catalogue role
 * the old BetterPosters add-on used to play in the Electron app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        if (settings.hasTmdb && vm.discoverItems.isEmpty()) vm.loadDiscover(vm.discoverKind)
    }

    if (!settings.hasTmdb) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            EmptyState(
                icon = AppIcons.Explore,
                title = "Discover needs a TMDB key",
                body = "The embedded Comet add-on resolves streams but has no catalogue of its own. " +
                    "Add a free TMDB API key in Settings to browse trending titles here.",
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", style = MaterialTheme.typography.headlineMedium, color = TdColor.TextHi)
                    Text(
                        "TMDB catalogue → Comet streams",
                        style = MaterialTheme.typography.labelSmall,
                        color = TdColor.TextLow,
                    )
                }
                GhostButton(
                    text = if (vm.discoverKind == "movie") "Movies" else "Series",
                    icon = if (vm.discoverKind == "movie") AppIcons.Movie else AppIcons.LiveTv,
                ) {
                    val next = if (vm.discoverKind == "movie") "tv" else "movie"
                    vm.discoverKind = next
                    vm.loadDiscover(next)
                }
            }
        }
        if (vm.discoverLoading) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TdColor.Cyan, strokeWidth = 2.dp)
                }
            }
        }
        if (vm.discoverError != null) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Text(vm.discoverError!!, color = TdColor.Amber, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(vm.discoverItems, key = { "${it.mediaType}-${it.id}" }) { item ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(TdColor.Ink2)
                    .clickable { vm.openItem(item) },
            ) {
                AsyncImage(
                    model = tmdbPoster(item.posterPath, "w342"),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                )
                Column(Modifier.padding(8.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.TextHi,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.year?.let { StatusPill("$it", TdColor.TextMid) }
                        if (item.voteAverage > 0) {
                            StatusPill(String.format(java.util.Locale.US, "%.1f", item.voteAverage), TdColor.Mint)
                        }
                    }
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

@Suppress("unused")
private val debugBadge: Boolean = BuildConfig.DEBUG
