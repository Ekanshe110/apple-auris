package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.apple.AppleLibraryCollection
import com.music.bitchord.data.apple.AppleLibraryRepository
import com.music.bitchord.data.apple.AppleLibraryState
import com.music.bitchord.data.apple.toSong
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import kotlinx.coroutines.launch

/**
 * The authenticated Apple Music library exposed by the configured addon.
 *
 * Loading, empty, signed-out and failed are separate visible outcomes. An
 * empty account is not an error, and a signed-out account is not an empty one.
 */
@Composable
fun AppleLibraryScreen(
    config: SourceConfig,
    onPlaySong: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val repository = remember(config.id, config.baseUrl, config.settings) {
        AppleLibraryRepository(config)
    }
    val scope = rememberCoroutineScope()
    var collectionIndex by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<AddonLibraryItem?>(null) }
    var state by remember { mutableStateOf<AppleLibraryState>(AppleLibraryState.Loading) }
    var retry by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    val collection = AppleLibraryCollection.entries[collectionIndex]

    LaunchedEffect(collection, detail?.id, retry) {
        state = AppleLibraryState.Loading
        state = if (detail == null) {
            repository.load(collection)
        } else {
            repository.tracks(collection, detail!!.id)
        }
    }

    fun loadMore() {
        val ready = state as? AppleLibraryState.Ready ?: return
        val nextOffset = ready.nextOffset ?: return
        if (!ready.hasMore || loadingMore) return
        scope.launch {
            loadingMore = true
            val next = if (detail == null) {
                repository.load(collection, nextOffset)
            } else {
                repository.tracks(collection, detail!!.id, nextOffset)
            }
            loadingMore = false
            when (next) {
                is AppleLibraryState.Ready -> state = AppleLibraryState.Ready(
                    items = ready.items + next.items,
                    hasMore = next.hasMore,
                    nextOffset = next.nextOffset,
                )
                is AppleLibraryState.Failure -> state = next
                else -> Unit
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Text(
                text = "Apple Music Library",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppleLibraryCollection.entries.forEachIndexed { index, item ->
                    TextButton(
                        onClick = {
                            collectionIndex = index
                            detail = null
                            state = AppleLibraryState.Loading
                        },
                    ) {
                        Text(item.label)
                    }
                }
            }
            if (detail != null) {
                TextButton(
                    onClick = {
                        detail = null
                        state = AppleLibraryState.Loading
                    },
                ) {
                    Text("Back to ${collection.label}")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        when (val current = state) {
            AppleLibraryState.Unconfigured -> item {
                Message("Apple Music library is not configured")
            }
            AppleLibraryState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            AppleLibraryState.SignedOut -> item {
                Message(
                    "Apple Music account is not connected. Sign in through the configured addon.",
                    error = true,
                )
            }
            AppleLibraryState.Empty -> item {
                Message("This Apple Music library collection is empty")
            }
            is AppleLibraryState.Failure -> item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Message(current.message, error = true)
                    if (current.retryable) {
                        Button(onClick = { retry++ }) { Text("Retry") }
                    }
                }
            }
            is AppleLibraryState.Ready -> {
                itemsIndexed(
                    items = current.items,
                    key = { index, item -> "${item.id}:$index" },
                ) { _, item ->
                    val playable = detail != null || collection == AppleLibraryCollection.SONGS
                    LibraryRow(
                        title = item.title.ifBlank { "Untitled" },
                        subtitle = item.artist.ifBlank { item.album },
                        imageUrl = item.artwork,
                        onClick = {
                            if (playable) {
                                item.toSong(config.id)?.let(onPlaySong)
                            } else {
                                detail = item
                                state = AppleLibraryState.Loading
                            }
                        },
                    )
                }
                if (current.hasMore) {
                    item {
                        Button(
                            onClick = ::loadMore,
                            enabled = !loadingMore,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator()
                            } else {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
