package dev.lelonio.square.backend.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lelonio.square.R
import dev.lelonio.square.backend.BackendAuthState
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.MusicBackend
import dev.lelonio.square.backend.PlaybackHost
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.playback.AudioEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Snapshot exposed to the local-library screen while a scan is running. */
data class LocalMusicLibraryState(
    val tracks: List<CatalogTrack> = emptyList(),
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val errorMessage: String? = null,
)

/** Reads audio files indexed by Android MediaStore and plays their content URIs directly. */
@UnstableApi
class LocalMusicBackend(context: Context) : MusicBackend {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val scanMutex = Mutex()
    private val _library = MutableStateFlow(LocalMusicLibraryState())

    val library: StateFlow<LocalMusicLibraryState> = _library.asStateFlow()

    override val id: BackendId = BackendId.LOCAL_MUSIC
    override val authState: StateFlow<BackendAuthState> = MutableStateFlow(
        BackendAuthState.LoggedIn(appContext.getString(R.string.backend_local_music)),
    )
    override val isReady: Boolean = true

    override suspend fun refreshAuth() = Unit
    override suspend fun logOut() = Unit

    /** Rescans on request; ordinary callers reuse the latest in-memory result. */
    suspend fun scan(force: Boolean = false): List<CatalogTrack> = scanMutex.withLock {
        val previous = _library.value
        if (!force && previous.hasScanned) return@withLock previous.tracks

        if (!hasReadPermission(appContext)) {
            _library.value = LocalMusicLibraryState(
                errorMessage = appContext.getString(R.string.local_music_permission_body),
            )
            return@withLock emptyList()
        }

        _library.value = previous.copy(isScanning = true, errorMessage = null)
        try {
            val found = withContext(Dispatchers.IO) { queryLocalTracks() }
            _library.value = LocalMusicLibraryState(
                tracks = found,
                isScanning = false,
                hasScanned = true,
            )
            found
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _library.value = _library.value.copy(isScanning = false)
            throw cancelled
        } catch (security: SecurityException) {
            _library.value = LocalMusicLibraryState(
                errorMessage = appContext.getString(R.string.local_music_permission_body),
            )
            emptyList()
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Local music scan failed", error)
            _library.value = _library.value.copy(
                isScanning = false,
                errorMessage = appContext.getString(R.string.local_music_scan_failed),
            )
            emptyList()
        }
    }

    /** Drop cached content if permission was revoked from Android settings. */
    fun clearIfPermissionMissing() {
        if (!hasReadPermission(appContext)) {
            _library.value = LocalMusicLibraryState(
                errorMessage = appContext.getString(R.string.local_music_permission_body),
            )
        }
    }

    private fun queryLocalTracks(): List<CatalogTrack> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val result = mutableListOf<CatalogTrack>()

        resolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: cursor.getString(displayNameColumn)?.substringBeforeLast('.')
                    ?: appContext.getString(R.string.local_music_unknown_track)
                val artist = cursor.getString(artistColumn)
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: appContext.getString(R.string.local_music_unknown_artist)
                val album = cursor.getString(albumColumn)
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    .orEmpty()
                val albumId = cursor.getLong(albumIdColumn)
                val trackUri = ContentUris.withAppendedId(collection, id).toString()
                val artwork = if (albumId > 0) {
                    ContentUris.withAppendedId(ALBUM_ART_URI, albumId).toString()
                } else {
                    null
                }

                result += CatalogTrack(
                    uri = trackUri,
                    name = title,
                    artist = artist,
                    album = album,
                    durationMs = cursor.getLong(durationColumn).coerceAtLeast(0L),
                    artworkUrl = artwork,
                )
            }
        }
        return result
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun search(query: String, labels: SearchLabels): SearchResults {
        val needle = query.trim()
        if (needle.isEmpty()) return SearchResults()
        val matches = scan().filter { track ->
            track.name.contains(needle, ignoreCase = true) ||
                track.artist.contains(needle, ignoreCase = true) ||
                track.album.contains(needle, ignoreCase = true)
        }
        return SearchResults(tracks = matches)
    }

    override suspend fun playlists(): List<CatalogPlaylist> = emptyList()

    override suspend fun homeRows(): List<dev.lelonio.square.backend.HomeRow> {
        val tracks = scan()
        return if (tracks.isEmpty()) emptyList() else listOf(
            dev.lelonio.square.backend.HomeRow(
                title = appContext.getString(R.string.local_music_title),
                tracks = tracks,
            ),
        )
    }

    override suspend fun tracksOf(uri: String): List<CatalogTrack> {
        val tracks = scan()
        return if (uri == LOCAL_LIBRARY_URI) tracks else tracks.filter { it.uri == uri }
    }

    override fun owns(uri: String): Boolean = uri.startsWith(LOCAL_TRACK_PREFIX)

    override fun createPlayer(host: PlaybackHost): Player = ExoPlayer.Builder(host.context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(host.context))
        .setHandleAudioBecomingNoisy(true)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setLooper(host.looper)
        .build()
        .apply {
            playbackParameters = PlaybackParameters(AudioEffects.speed.value, AudioEffects.pitch.value)
        }

    companion object {
        private const val TAG = "SquareLocalMusic"
        const val LOCAL_LIBRARY_URI = "square-local:library"
        private const val LOCAL_TRACK_PREFIX = "content://media/external/audio/media/"
        private val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")

        fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        fun hasReadPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }
}
