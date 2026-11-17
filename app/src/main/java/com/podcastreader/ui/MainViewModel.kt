package com.podcastreader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podcastreader.PodcastReaderApplication
import com.podcastreader.data.local.AiProviderType
import com.podcastreader.data.local.AppSettings
import com.podcastreader.data.local.LibraryBookEntry
import com.podcastreader.data.rag.BookIndexingRegistry
import com.podcastreader.data.rag.FetchedModels
import com.podcastreader.data.rag.IndexingProgress
import com.podcastreader.data.rag.ModelCatalogService
import com.podcastreader.domain.chunking.RawChapter
import com.podcastreader.domain.model.Book
import com.podcastreader.domain.model.PlaybackUiState
import com.podcastreader.domain.model.ReadingProgress
import com.podcastreader.domain.model.SynthesisMode
import com.podcastreader.domain.parser.UnsupportedBookFormatException
import com.podcastreader.domain.rag.PlaybackBookmark
import com.podcastreader.domain.tts.TtsVoiceOption
import com.podcastreader.voice.VoiceAssistantUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class ModelsFetchState(
    val isFetching: Boolean = false,
    val models: FetchedModels = FetchedModels(),
    val error: String? = null
)

data class MainScreenState(
    val book: Book? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val showCoverScreen: Boolean = false,
    val library: List<LibraryBookEntry> = emptyList(),
    val playback: PlaybackUiState = PlaybackUiState(),
    val indexingProgress: IndexingProgress? = null,
    val voiceState: VoiceAssistantUiState = VoiceAssistantUiState(),
    val settings: AppSettings = AppSettings(),
    val localVoices: List<TtsVoiceOption> = emptyList(),
    val modelsState: ModelsFetchState = ModelsFetchState()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PodcastReaderApplication
    private val playbackController = app.playbackController
    private val progressStore = app.progressDataStore
    private val voiceManager = app.voiceAssistantManager

    private val _bookState = MutableStateFlow(BookLoadState())
    private val _localVoices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    private val _library = MutableStateFlow<List<LibraryBookEntry>>(emptyList())
    private val _modelsState = MutableStateFlow(ModelsFetchState())
    private var wasPlayingBeforeVoice = false

    private data class BookLoadState(
        val book: Book? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val showCoverScreen: Boolean = false
    )

    val uiState: StateFlow<MainScreenState> = combine(
        combine(_bookState, _modelsState) { bookLoad, models -> bookLoad to models },
        playbackController.uiState,
        BookIndexingRegistry.progress,
        voiceManager.state,
        app.apiSettingsDataStore.settingsFlow
    ) { (bookLoad, modelsState), playback, indexing, voice, settings ->
        MainScreenState(
            book = bookLoad.book,
            isLoading = bookLoad.isLoading,
            errorMessage = bookLoad.errorMessage ?: playback.errorMessage,
            showCoverScreen = bookLoad.showCoverScreen,
            library = _library.value,
            playback = playback,
            indexingProgress = indexing,
            voiceState = voice,
            settings = settings,
            localVoices = _localVoices.value,
            modelsState = modelsState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainScreenState()
    )

    init {
        refreshLibrary()
        restoreLastBook()
        refreshLocalVoices()
        observePlaybackForLibrary()
    }

    private fun refreshLibrary() {
        viewModelScope.launch {
            _library.value = app.bookLibraryStore.loadAll()
        }
    }

    private fun observePlaybackForLibrary() {
        viewModelScope.launch {
            playbackController.uiState.collect { playback ->
                val book = _bookState.value.book ?: return@collect
                val percent = (book.progressFraction(playback.currentChapterIndex, playback.currentParagraphIndex) * 100).toInt()
                app.bookLibraryStore.updateProgress(
                    bookId = book.id,
                    chapterIndex = playback.currentChapterIndex,
                    paragraphIndex = playback.currentParagraphIndex,
                    progressPercent = percent
                )
                _library.value = app.bookLibraryStore.loadAll()
            }
        }
    }

    fun refreshLocalVoices() {
        viewModelScope.launch {
            app.androidTtsEngine.initialize()
            _localVoices.value = app.androidTtsEngine.getAvailableSpanishVoices()
        }
    }

    private fun restoreLastBook() {
        viewModelScope.launch {
            val savedProgress = progressStore.progressFlow.firstOrNull()
            val savedLocalPath = progressStore.lastBookLocalPathFlow.firstOrNull()
            val savedUriString = progressStore.lastBookUriFlow.firstOrNull()

            val localFile = savedLocalPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            if (localFile != null && savedProgress != null) {
                loadBookFromLocalFile(
                    file = localFile,
                    bookId = savedProgress.bookId,
                    restoreProgress = savedProgress,
                    showCover = true
                )
            } else if (savedUriString != null) {
                loadBookFromUri(
                    uri = Uri.parse(savedUriString),
                    restoreProgress = savedProgress,
                    persistUri = false,
                    showCover = true
                )
            }
        }
    }

    fun importBook(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Algunos proveedores no soportan permisos persistentes
        }
        viewModelScope.launch {
            loadBookFromUri(uri = uri, restoreProgress = null, persistUri = true, showCover = true)
        }
    }

    private suspend fun loadBookFromLocalFile(
        file: File,
        bookId: String,
        restoreProgress: ReadingProgress,
        showCover: Boolean
    ) {
        _bookState.update { it.copy(isLoading = true, errorMessage = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val parser = app.parsers.firstOrNull { it.supports(file.name, null) }
                    ?: throw UnsupportedBookFormatException("Formato no soportado (${file.name})")
                file.inputStream().use { stream -> parser.parse(bookId, stream) }
            }
        }
        applyLoadedBook(result, restoreProgress, persistUri = false, uri = null, localPath = file.absolutePath, showCover = showCover)
    }

    private suspend fun loadBookFromUri(
        uri: Uri,
        restoreProgress: ReadingProgress?,
        persistUri: Boolean,
        showCover: Boolean
    ) {
        _bookState.update { it.copy(isLoading = true, errorMessage = null) }

        var copiedLocalPath: String? = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val fileName = resolveFileName(uri)
                val mimeType = app.contentResolver.getType(uri)
                val parser = app.parsers.firstOrNull { it.supports(fileName, mimeType) }
                    ?: throw UnsupportedBookFormatException(
                        "Formato no soportado ($fileName). Selecciona un archivo .epub"
                    )

                val bookId = sha256Short(uri.toString())
                val booksDir = File(app.filesDir, "books").apply { mkdirs() }
                val ext = fileName.substringAfterLast('.', "epub")
                val localFile = File(booksDir, "$bookId.$ext")

                app.contentResolver.openInputStream(uri)?.use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("No se pudo abrir el archivo seleccionado")

                copiedLocalPath = localFile.absolutePath
                localFile.inputStream().use { stream ->
                    parser.parse(bookId, stream)
                }
            }
        }

        applyLoadedBook(result, restoreProgress, persistUri, uri, copiedLocalPath, showCover)
    }

    private suspend fun applyLoadedBook(
        result: Result<Book>,
        restoreProgress: ReadingProgress?,
        persistUri: Boolean,
        uri: Uri?,
        localPath: String?,
        showCover: Boolean
    ) {
        result.onSuccess { book ->
            val chapter = restoreProgress?.takeIf { it.bookId == book.id }?.chapterIndex ?: 0
            val paragraph = restoreProgress?.takeIf { it.bookId == book.id }?.paragraphIndex ?: 0
            val speed = restoreProgress?.playbackSpeed ?: 1.0f

            playbackController.setBook(
                book = book,
                initialChapter = chapter,
                initialParagraph = paragraph,
                speed = speed
            )
            if (localPath != null) {
                val coverPath = app.bookLibraryStore.saveCoverIfPresent(book.id, book.coverImageBytes)
                val percent = (book.progressFraction(chapter, paragraph) * 100).toInt()
                app.bookLibraryStore.upsertBook(
                    LibraryBookEntry(
                        bookId = book.id,
                        title = book.title,
                        author = book.author,
                        localPath = localPath,
                        coverPath = coverPath,
                        chapterIndex = chapter,
                        paragraphIndex = paragraph,
                        progressPercent = percent
                    )
                )
                _library.value = app.bookLibraryStore.loadAll()
            }
            if (persistUri || localPath != null) {
                progressStore.saveProgress(
                    ReadingProgress(
                        bookId = book.id,
                        chapterIndex = chapter,
                        paragraphIndex = paragraph,
                        playbackSpeed = speed
                    ),
                    bookUri = uri?.toString(),
                    localPath = localPath
                )
            }
            _bookState.update {
                BookLoadState(
                    book = book,
                    isLoading = false,
                    errorMessage = null,
                    showCoverScreen = showCover
                )
            }
            startProgressiveIndexing(book, priorityChapter = chapter)
        }.onFailure { error ->
            _bookState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Error al abrir el libro"
                )
            }
        }
    }

    fun openCoverScreen() {
        refreshLibrary()
        _bookState.update { it.copy(showCoverScreen = true) }
    }

    fun selectBookFromLibrary(entry: LibraryBookEntry) {
        val file = File(entry.localPath)
        if (!file.exists()) {
            _bookState.update { it.copy(errorMessage = "No se encontró el archivo del libro.") }
            return
        }
        viewModelScope.launch {
            val speed = playbackController.uiState.value.playbackSpeed
            loadBookFromLocalFile(
                file = file,
                bookId = entry.bookId,
                restoreProgress = ReadingProgress(
                    bookId = entry.bookId,
                    chapterIndex = entry.chapterIndex,
                    paragraphIndex = entry.paragraphIndex,
                    playbackSpeed = speed
                ),
                showCover = true
            )
        }
    }

    fun continueFromCover(playImmediately: Boolean) {
        _bookState.update { it.copy(showCoverScreen = false) }
        if (playImmediately) {
            val state = playbackController.uiState.value
            playbackController.playFrom(state.currentChapterIndex, state.currentParagraphIndex)
        }
    }

    fun restartFromBeginning() {
        _bookState.update { it.copy(showCoverScreen = false) }
        playbackController.playFrom(0, 0)
    }

    private fun startProgressiveIndexing(book: Book, priorityChapter: Int) {
        BookIndexingRegistry.registerBook(book)
        viewModelScope.launch(Dispatchers.IO) {
            book.chapters.getOrNull(priorityChapter)?.let { ch ->
                app.ragEngine.indexBookChapters(
                    book.id,
                    listOf(RawChapter(ch.index, ch.title, ch.paragraphs.map { it.text }))
                )
            }
            BookIndexingRegistry.enqueueRemainingIndexing(app, book.id, priorityChapter)
        }
    }

    fun selectChapter(chapterIndex: Int, autoPlay: Boolean = true) {
        _bookState.update { it.copy(showCoverScreen = false) }
        if (autoPlay) {
            playbackController.playFrom(chapterIndex, 0)
        } else {
            val book = _bookState.value.book ?: return
            playbackController.setBook(
                book = book,
                initialChapter = chapterIndex,
                initialParagraph = 0,
                speed = playbackController.uiState.value.playbackSpeed
            )
        }
    }

    fun playFromParagraph(chapterIndex: Int, paragraphIndex: Int) {
        playbackController.playFrom(chapterIndex, paragraphIndex)
    }

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun nextParagraph() = playbackController.nextParagraph()

    fun previousParagraph() = playbackController.previousParagraph()

    fun setPlaybackSpeed(speed: Float) = playbackController.setPlaybackSpeed(speed)

    fun setSynthesisMode(mode: SynthesisMode) = playbackController.setSynthesisMode(mode)

    fun startVoiceQuery() {
        val bookmark = currentBookmark() ?: return
        wasPlayingBeforeVoice = playbackController.pauseIfPlaying()
        voiceManager.startListening(bookmark)
    }

    fun openVoiceTextDialog() {
        val bookmark = currentBookmark() ?: return
        wasPlayingBeforeVoice = playbackController.pauseIfPlaying()
        voiceManager.openDialog(bookmark)
    }

    fun submitTextQuestion(question: String) {
        val bookmark = currentBookmark() ?: return
        voiceManager.submitQuestion(question, bookmark)
    }

    fun closeVoiceDialog(resumeBook: Boolean) {
        voiceManager.closeDialog()
        if (resumeBook && wasPlayingBeforeVoice) {
            playbackController.resumePlayback()
        }
        wasPlayingBeforeVoice = false
    }

    fun saveSettings(settings: AppSettings) {
        viewModelScope.launch {
            app.apiSettingsDataStore.saveSettings(settings)
        }
    }

    fun fetchAvailableModels(aiProvider: AiProviderType, apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            _modelsState.update { it.copy(isFetching = true, error = null) }
            val result = ModelCatalogService.fetchModels(aiProvider, apiKey, baseUrl)
            result.onSuccess { fetched ->
                _modelsState.value = ModelsFetchState(isFetching = false, models = fetched, error = null)
            }.onFailure { err ->
                _modelsState.update {
                    it.copy(isFetching = false, error = err.message ?: "Error al obtener modelos")
                }
            }
        }
    }

    fun clearError() {
        _bookState.update { it.copy(errorMessage = null) }
    }

    private fun currentBookmark(): PlaybackBookmark? {
        val book = _bookState.value.book ?: return null
        val playback = playbackController.uiState.value
        return PlaybackBookmark(
            bookId = book.id,
            currentChapterIndex = playback.currentChapterIndex,
            currentParagraphIndex = playback.currentParagraphIndex
        )
    }

    private fun resolveFileName(uri: Uri): String {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIdx) ?: "book.epub"
            }
        }
        return uri.lastPathSegment ?: "book.epub"
    }

    private fun sha256Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
