package com.podcastreader.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.podcastreader.data.local.ReadingProgressDataStore
import com.podcastreader.data.tts.AudioChunkCacheManager
import com.podcastreader.domain.model.Book
import com.podcastreader.domain.model.PlaybackUiState
import com.podcastreader.domain.model.ReadingProgress
import com.podcastreader.domain.model.SynthesisMode
import com.podcastreader.domain.podcast.PodcastDialogueGenerator
import com.podcastreader.domain.tts.VoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackController(
    private val context: Context,
    private val chunkCache: AudioChunkCacheManager,
    private val progressDataStore: ReadingProgressDataStore,
    private val dialogueGenerator: PodcastDialogueGenerator? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var mediaController: MediaController? = null
    private var currentBook: Book? = null
    private val queuedKeys = mutableSetOf<String>()
    private var lastPrefetchKey: String? = null

    suspend fun connect() {
        if (mediaController != null) return
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PodcastPlaybackService::class.java)
        )
        val controller = MediaController.Builder(context, sessionToken)
            .buildAsync()
            .await()

        controller.addListener(playerListener)
        controller.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed, 1.0f)
        mediaController = controller
    }

    fun setBook(book: Book, initialChapter: Int = 0, initialParagraph: Int = 0, speed: Float = 1.0f) {
        currentBook = book
        queuedKeys.clear()
        _uiState.update {
            it.copy(
                currentChapterIndex = initialChapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0)),
                currentParagraphIndex = initialParagraph,
                playbackSpeed = speed,
                errorMessage = null
            )
        }
        mediaController?.playbackParameters = PlaybackParameters(speed, 1.0f)
    }

    fun playFrom(chapterIndex: Int, paragraphIndex: Int) {
        val book = currentBook ?: return
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return

        if (_uiState.value.synthesisMode == SynthesisMode.PODCAST_DIALOGUE && dialogueGenerator != null) {
            playPodcastChapter(book, chapterIndex)
            return
        }

        val paragraph = chapter.paragraphs.getOrNull(paragraphIndex) ?: return

        scope.launch {
            connect()
            val controller = mediaController ?: return@launch

            chunkCache.cancelPrefetch()
            queuedKeys.clear()
            lastPrefetchKey = null
            _uiState.update {
                it.copy(
                    isBuffering = true,
                    currentChapterIndex = chapterIndex,
                    currentParagraphIndex = paragraphIndex,
                    errorMessage = null
                )
            }

            val firstFileResult = withContext(Dispatchers.IO) {
                chunkCache.getOrSynthesizeParagraph(
                    bookId = book.id,
                    chapterIndex = chapterIndex,
                    paragraphIndex = paragraphIndex,
                    text = paragraph.text
                )
            }

            firstFileResult.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = error.message ?: "Error al sintetizar audio"
                    )
                }
                return@launch
            }

            val firstFile = firstFileResult.getOrThrow()
            val firstItem = buildMediaItem(book, chapterIndex, paragraphIndex, firstFile)
            queuedKeys.add(itemKey(chapterIndex, paragraphIndex))

            // Pre-encolar el inicio del prefetch antes de setMediaItem para que
            // onMediaItemTransition no cancele ni duplique el prefetch del mismo párrafo.
            val nextStart = paragraphIndex + 1
            if (nextStart < chapter.paragraphs.size) {
                lastPrefetchKey = itemKey(chapterIndex, nextStart)
            }

            controller.setMediaItem(firstItem)
            controller.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed, 1.0f)
            controller.prepare()
            controller.play()

            _uiState.update { it.copy(isBuffering = false, isPlaying = true) }
            persistProgress(book.id, chapterIndex, paragraphIndex, 0L)

            // Pre-sintetizar los siguientes 3 párrafos en segundo plano (Chunking Buffer)
            if (nextStart < chapter.paragraphs.size) {
                chunkCache.prefetchAhead(
                    scope = scope,
                    bookId = book.id,
                    chapter = chapter,
                    fromParagraphIndex = nextStart,
                    windowSize = 3
                ) { nextPIdx, file ->
                    withContext(Dispatchers.Main.immediate) {
                        val key = itemKey(chapterIndex, nextPIdx)
                        if (queuedKeys.add(key)) {
                            controller.addMediaItem(buildMediaItem(book, chapterIndex, nextPIdx, file))
                        }
                    }
                }
            }
        }
    }

    fun pauseIfPlaying(): Boolean {
        val controller = mediaController ?: return false
        val wasPlaying = controller.isPlaying
        if (wasPlaying) {
            controller.pause()
        }
        return wasPlaying
    }

    fun resumePlayback() {
        mediaController?.play()
    }

    private fun playPodcastChapter(book: Book, chapterIndex: Int) {
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return
        val generator = dialogueGenerator ?: return

        scope.launch {
            connect()
            val controller = mediaController ?: return@launch
            chunkCache.cancelPrefetch()
            queuedKeys.clear()
            _uiState.update {
                it.copy(
                    isBuffering = true,
                    currentChapterIndex = chapterIndex,
                    currentParagraphIndex = 0,
                    errorMessage = null
                )
            }

            val turnsResult = withContext(Dispatchers.IO) {
                runCatching { generator.generateChapterDialogue(chapter) }
            }
            val turns = turnsResult.getOrElse { err ->
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        synthesisMode = SynthesisMode.LITERAL,
                        errorMessage = "Podcast Dual no disponible (${err.message}). Reproduciendo en Lectura Literal."
                    )
                }
                playFrom(chapterIndex, 0)
                return@launch
            }

            if (turns.isEmpty() || turns.first().text.startsWith("No se pudo conectar") ||
                turns.first().text.startsWith("Configura tu API Key")
            ) {
                val msg = turns.firstOrNull()?.text ?: "Podcast Dual no disponible"
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        synthesisMode = SynthesisMode.LITERAL,
                        errorMessage = "$msg Reproduciendo en Lectura Literal."
                    )
                }
                playFrom(chapterIndex, 0)
                return@launch
            }

            val firstTurn = turns.first()
            val firstConfig = voiceConfigForSpeaker(firstTurn.speaker)
            val firstFile = withContext(Dispatchers.IO) {
                chunkCache.getOrSynthesizeParagraph(
                    bookId = "${book.id}_pod",
                    chapterIndex = chapterIndex,
                    paragraphIndex = 0,
                    text = firstTurn.text,
                    voiceConfig = firstConfig
                )
            }.getOrElse { err ->
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = err.message ?: "Error al sintetizar Podcast Dual"
                    )
                }
                return@launch
            }

            controller.setMediaItem(buildMediaItem(book, chapterIndex, 0, firstFile))
            controller.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed, 1.0f)
            controller.prepare()
            controller.play()
            _uiState.update { it.copy(isBuffering = false, isPlaying = true) }

            for (idx in 1 until turns.size) {
                val turn = turns[idx]
                val file = withContext(Dispatchers.IO) {
                    chunkCache.getOrSynthesizeParagraph(
                        bookId = "${book.id}_pod",
                        chapterIndex = chapterIndex,
                        paragraphIndex = idx,
                        text = turn.text,
                        voiceConfig = voiceConfigForSpeaker(turn.speaker)
                    )
                }.getOrNull() ?: continue

                controller.addMediaItem(buildMediaItem(book, chapterIndex, idx, file))
            }
        }
    }

    private fun voiceConfigForSpeaker(speaker: String): VoiceConfig {
        val isCarlos = speaker.equals("Carlos", ignoreCase = true)
        return VoiceConfig(
            pitch = if (isCarlos) 0.90f else 1.06f,
            rate = 1.0f
        )
    }

    fun togglePlayPause() {
        val controller = mediaController
        if (controller == null || controller.mediaItemCount == 0) {
            val state = _uiState.value
            playFrom(state.currentChapterIndex, state.currentParagraphIndex)
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun nextParagraph() {
        val book = currentBook ?: return
        val state = _uiState.value
        val chapter = book.chapters.getOrNull(state.currentChapterIndex) ?: return
        if (state.currentParagraphIndex + 1 < chapter.paragraphs.size) {
            playFrom(state.currentChapterIndex, state.currentParagraphIndex + 1)
        } else if (state.currentChapterIndex + 1 < book.chapters.size) {
            playFrom(state.currentChapterIndex + 1, 0)
        }
    }

    fun previousParagraph() {
        val book = currentBook ?: return
        val state = _uiState.value
        if (state.currentParagraphIndex > 0) {
            playFrom(state.currentChapterIndex, state.currentParagraphIndex - 1)
        } else if (state.currentChapterIndex > 0) {
            val prevChapter = book.chapters[state.currentChapterIndex - 1]
            val lastPara = (prevChapter.paragraphs.size - 1).coerceAtLeast(0)
            playFrom(state.currentChapterIndex - 1, lastPara)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        mediaController?.playbackParameters = PlaybackParameters(clamped, 1.0f)
        scope.launch {
            progressDataStore.saveSpeed(clamped)
        }
    }

    fun setSynthesisMode(mode: SynthesisMode) {
        _uiState.update { it.copy(synthesisMode = mode) }
    }

    private fun buildMediaItem(
        book: Book,
        chapterIndex: Int,
        paragraphIndex: Int,
        audioFile: File
    ): MediaItem {
        val chapterTitle = book.chapters.getOrNull(chapterIndex)?.title ?: "Capítulo ${chapterIndex + 1}"
        val extras = Bundle().apply {
            putString("bookId", book.id)
            putInt("chapterIndex", chapterIndex)
            putInt("paragraphIndex", paragraphIndex)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(chapterTitle)
            .setArtist(book.author)
            .setAlbumTitle(book.title)
            .setSubtitle("Párrafo ${paragraphIndex + 1}")
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(itemKey(chapterIndex, paragraphIndex))
            .setUri(Uri.fromFile(audioFile))
            .setMediaMetadata(metadata)
            .build()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val extras = mediaItem?.mediaMetadata?.extras ?: return
            val book = currentBook ?: return
            val chapterIdx = extras.getInt("chapterIndex", _uiState.value.currentChapterIndex)
            val paraIdx = extras.getInt("paragraphIndex", _uiState.value.currentParagraphIndex)

            _uiState.update {
                it.copy(
                    currentChapterIndex = chapterIdx,
                    currentParagraphIndex = paraIdx
                )
            }
            persistProgress(book.id, chapterIdx, paraIdx, 0L)

            val chapter = book.chapters.getOrNull(chapterIdx) ?: return
            val nextStart = paraIdx + 1
            if (nextStart < chapter.paragraphs.size) {
                val prefetchKey = itemKey(chapterIdx, nextStart)
                if (lastPrefetchKey == prefetchKey) return
                lastPrefetchKey = prefetchKey

                chunkCache.prefetchAhead(
                    scope = scope,
                    bookId = book.id,
                    chapter = chapter,
                    fromParagraphIndex = nextStart,
                    windowSize = 3
                ) { nextPIdx, file ->
                    withContext(Dispatchers.Main.immediate) {
                        val key = itemKey(chapterIdx, nextPIdx)
                        if (queuedKeys.add(key)) {
                            mediaController?.addMediaItem(buildMediaItem(book, chapterIdx, nextPIdx, file))
                        }
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val book = currentBook ?: return
                val currentChapter = _uiState.value.currentChapterIndex
                val currentPara = _uiState.value.currentParagraphIndex
                val chapter = book.chapters.getOrNull(currentChapter)
                if (_uiState.value.synthesisMode == SynthesisMode.LITERAL &&
                    chapter != null &&
                    currentPara + 1 < chapter.paragraphs.size
                ) {
                    // Si el párrafo actual terminó de reproducirse antes de que el prefetch
                    // terminara de sintetizar el siguiente párrafo, continuar con el siguiente
                    // párrafo del mismo capítulo en vez de saltar de capítulo.
                    playFrom(currentChapter, currentPara + 1)
                } else if (currentChapter + 1 < book.chapters.size) {
                    playFrom(currentChapter + 1, 0)
                } else {
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _uiState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "Error de reproducción: ${error.localizedMessage ?: error.errorCodeName}"
                )
            }
        }
    }

    private fun persistProgress(bookId: String, chapterIndex: Int, paragraphIndex: Int, positionMs: Long) {
        scope.launch {
            progressDataStore.saveProgress(
                ReadingProgress(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    paragraphIndex = paragraphIndex,
                    positionMs = positionMs,
                    playbackSpeed = _uiState.value.playbackSpeed
                )
            )
        }
    }

    private fun itemKey(chapterIndex: Int, paragraphIndex: Int): String =
        "ch_${chapterIndex}_p_${paragraphIndex}"

    fun release() {
        chunkCache.cancelPrefetch()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }
}
