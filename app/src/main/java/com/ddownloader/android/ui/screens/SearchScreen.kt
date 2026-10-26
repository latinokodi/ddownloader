package com.ddownloader.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ddownloader.android.api.TmdbApi
import com.ddownloader.android.api.tmdbPoster
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.GlassCard
import com.ddownloader.android.ui.components.GradientButton
import com.ddownloader.android.ui.components.SectionLabel
import com.ddownloader.android.ui.components.StatusPill
import com.ddownloader.android.ui.components.TdTextField
import com.ddownloader.android.ui.components.SessionHero
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor

/**
 * "Search" = resolve a title to a torrent, or queue a link directly.
 *
 * Without a TMDB key there is no title search (Comet is a stream add-on with no
 * `catalog`/`meta`/`search` resource), so the direct-link path is always shown and
 * remains fully functional — that is also the path the automated tests use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: AppViewModel, onOpenDownloads: () -> Unit = {}) {
    val active by vm.activeCount.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SessionHero(
                activeTransfers = active,
                queuedTransfers = records.count { it.state == com.ddownloader.android.data.db.TransferState.QUEUED },
                completedTransfers = records.count { it.state == com.ddownloader.android.data.db.TransferState.COMPLETED },
                hasDebrid = settings.hasAnyDebrid,
                onOpenDownloads = onOpenDownloads,
            )
        }

        item {
            GlassCard {
                SectionLabel("Find something")
                Spacer(Modifier.height(10.dp))
                TdTextField(
                    value = vm.searchQuery,
                    onValueChange = { vm.searchQuery = it },
                    label = "Movie or series",
                    placeholder = "Dune, Severance, Cien años de soledad…",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton(
                        text = if (vm.searching) "Searching…" else "Search",
                        modifier = Modifier.weight(1f),
                        icon = AppIcons.Search,
                        enabled = !vm.searching && vm.searchQuery.isNotBlank(),
                        onClick = { vm.search() },
                    )
                    if (!settings.hasTmdb) {
                        StatusPill("TMDB key needed", TdColor.Amber, AppIcons.Key)
                    }
                }
                vm.searchError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.Amber,
                    )
                }
                if (vm.searching) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = TdColor.Cyan,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        if (vm.searchResults.isNotEmpty()) {
            item { SectionLabel("Results", Modifier.padding(start = 4.dp)) }
            items(vm.searchResults, key = { "${it.mediaType}-${it.id}" }) { item ->
                TitleRow(item) { vm.openItem(item) }
            }
        }

        item {
            GlassCard {
                SectionLabel("Paste a link")
                Spacer(Modifier.height(10.dp))
                TdTextField(
                    value = vm.pastedLink,
                    onValueChange = { vm.pastedLink = it },
                    label = "Direct URL",
                    placeholder = "https://… direct link",
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = "Queue download",
                    icon = AppIcons.CloudDownload,
                    enabled = vm.pastedLink.isNotBlank(),
                    onClick = {
                        vm.addUrl(vm.pastedLink)
                        vm.pastedLink = ""
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Transfers run in a foreground service and survive process death. API tokens are stored encrypted in the Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
            }
        }
    }

    val item = vm.streamSheetItem
    if (item != null) {
        ModalBottomSheet(
            onDismissRequest = { vm.closeStreamSheet() },
            sheetState = sheetState,
            containerColor = TdColor.Ink1,
            contentColor = TdColor.TextHi,
        ) {
            StreamSheetContent(vm, item)
        }
    }
}

@Composable
internal fun StreamSheetContent(vm: AppViewModel, item: TmdbApi.Item) {
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
        SectionLabel("Comet streams")
        Spacer(Modifier.height(6.dp))
        Text(
            item.title + (item.year?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.headlineSmall,
            color = TdColor.TextHi,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Embedded add-on • stremio.comet.fast",
            style = MaterialTheme.typography.labelSmall,
            color = TdColor.TextLow,
        )
        Spacer(Modifier.height(14.dp))
        when {
            vm.streamsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = TdColor.Cyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Asking Comet through Tor…", color = TdColor.TextMid, style = MaterialTheme.typography.bodySmall)
            }
            vm.streamsError != null -> Text(
                vm.streamsError!!,
                color = TdColor.Amber,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.streams) { stream ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(TdColor.Ink2)
                            .clickable { vm.downloadStream(stream) }
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            AppIcons.Bolt,
                            contentDescription = null,
                            tint = TdColor.Violet,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stream.displayName(),
                                style = MaterialTheme.typography.bodySmall,
                                color = TdColor.TextHi,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                buildString {
                                    append(stream.name)
                                    stream.videoSize?.let { append(" • ${com.ddownloader.android.core.Fmt.bytes(it)}") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TdColor.TextLow,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleRow(item: TmdbApi.Item, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(TdColor.Ink2)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 76.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TdColor.Ink3),
        ) {
            AsyncImage(
                model = tmdbPoster(item.posterPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = TdColor.TextHi,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    if (item.mediaType == "tv") "series" else "movie",
                    TdColor.Cyan,
                )
                item.year?.let { StatusPill("$it", TdColor.TextMid) }
            }
        }
        Icon(AppIcons.PlayArrow, contentDescription = null, tint = TdColor.TextLow, modifier = Modifier.size(20.dp))
    }
}
