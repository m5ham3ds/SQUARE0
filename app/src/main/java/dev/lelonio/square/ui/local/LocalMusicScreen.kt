package dev.lelonio.square.ui.local

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Queue
import com.kyant.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.backend.local.LocalMusicBackend
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch

/** A simple, permission-gated view of music MediaStore has indexed on this device. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LocalMusicScreen(
    contentPadding: PaddingValues,
    nowPlayingUri: String?,
    onPlay: (List<CatalogTrack>, Int) -> Unit,
    onEnqueue: (CatalogTrack) -> Unit,
    onOpenSettings: () -> Unit,
    backdrop: Backdrop,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backend = remember(context) {
        (context.applicationContext as SquareApplication).localMusicBackend
    }
    val library by backend.library.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var permissionGranted by remember {
        mutableStateOf(LocalMusicBackend.hasReadPermission(context))
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
    }

    // Returning from Android app settings updates the screen and starts the first scan.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = LocalMusicBackend.hasReadPermission(context)
                if (permissionGranted) {
                    permissionDenied = false
                } else {
                    backend.clearIfPermissionMissing()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionGranted, library.hasScanned) {
        if (permissionGranted && !library.hasScanned) backend.scan()
    }

    fun requestOrScan() {
        if (permissionGranted) {
            scope.launch { backend.scan(force = true) }
        } else {
            permissionLauncher.launch(LocalMusicBackend.requiredPermission())
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "local_music_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.local_music_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.settings),
                        style = MaterialTheme.typography.labelLarge,
                        color = InkDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenSettings)
                            .padding(10.dp),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(R.string.local_music_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
                Spacer(Modifier.height(10.dp))
                if (library.hasScanned) {
                    Text(
                        stringResource(R.string.local_music_track_count, library.tracks.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkDim,
                    )
                }
                Spacer(Modifier.height(14.dp))
                LiquidButton(
                    onClick = ::requestOrScan,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    contentHeight = 48.dp,
                    contentPadding = 18.dp,
                ) {
                    if (library.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Ink,
                        )
                        Text(stringResource(R.string.local_music_scanning), color = Ink)
                    } else {
                        Text(
                            stringResource(
                                if (library.hasScanned) R.string.local_music_rescan
                                else if (permissionGranted) R.string.local_music_scan
                                else R.string.local_music_permission_title,
                            ),
                            color = Ink,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (permissionDenied && !permissionGranted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.local_music_permission_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        stringResource(R.string.local_music_open_settings),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            }
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                    )
                }
                library.errorMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (!permissionGranted && !library.hasScanned && !permissionDenied) {
            item(key = "local_music_permission") {
                EmptyState(text = stringResource(R.string.local_music_permission_body))
            }
        } else if (!library.isScanning && library.hasScanned && library.tracks.isEmpty() && library.errorMessage == null) {
            item(key = "local_music_empty") {
                EmptyState(text = stringResource(R.string.local_music_no_tracks))
            }
        }

        itemsIndexed(library.tracks, key = { _, track -> track.uri }) { index, track ->
            LocalTrackRow(
                track = track,
                isPlaying = track.uri == nowPlayingUri,
                onClick = { onPlay(library.tracks, index) },
                onEnqueue = { onEnqueue(track) },
            )
        }
        item(key = "local_music_bottom_space") { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun LocalTrackRow(
    track: CatalogTrack,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onEnqueue: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.035f))
            .border(
                width = 1.dp,
                color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.045f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.artworkUrl,
            title = track.name,
            modifier = Modifier.size(52.dp),
            corner = 10.dp,
            decodeSize = 64.dp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                track.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else Ink,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (track.album.isBlank()) track.artist else "${track.artist} · ${track.album}"
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEnqueue) {
            Icon(
                PhosphorIcons.Regular.Queue,
                contentDescription = stringResource(R.string.add_to_queue),
                tint = InkDim,
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
        )
    }
}
