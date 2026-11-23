package com.podcastreader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podcastreader.data.local.AiProviderType
import com.podcastreader.data.local.AppSettings
import com.podcastreader.data.local.LibraryBookEntry
import com.podcastreader.domain.model.Book
import com.podcastreader.domain.model.PlaybackUiState
import com.podcastreader.domain.model.SynthesisMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: MainScreenState,
    onOpenFilePicker: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onParagraphClick: (Int, Int) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onPreviousParagraph: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSynthesisModeChange: (SynthesisMode) -> Unit,
    onMicClick: () -> Unit,
    onSubmitVoiceQuestion: (String) -> Unit,
    onCloseVoiceDialog: (Boolean) -> Unit,
    onSaveSettings: (AppSettings) -> Unit,
    onFetchModels: (AiProviderType, String, String) -> Unit,
    onRefreshVoices: () -> Unit,
    onContinueFromCover: (Boolean) -> Unit,
    onRestartFromBeginning: () -> Unit,
    onOpenCoverScreen: () -> Unit,
    onSelectBookFromLibrary: (LibraryBookEntry) -> Unit,
    onDismissError: () -> Unit,
    sherpaTtsReady: Boolean = false,
    sherpaTtsLoading: Boolean = false,
    sherpaTtsError: String? = null,
    sherpaTestMessage: String? = null,
    onSelectSherpaTts: () -> Unit = {},
    onTestSherpaTts: () -> Unit = {},
    onClearTestMessage: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var sherpaDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            onDismissError()
        }
    }

    LaunchedEffect(sherpaTestMessage) {
        if (sherpaTestMessage != null) {
            sherpaDialogOpen = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.book != null,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Índice (TOC)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                val book = state.book
                if (book != null) {
                    LazyColumn {
                        items(book.toc) { item ->
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = item.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                selected = item.chapterIndex == state.playback.currentChapterIndex,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    onSelectChapter(item.chapterIndex)
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.book?.title ?: "PodcastReader",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            state.book?.let {
                                Text(
                                    text = it.author,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (state.book != null) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Índice")
                            }
                        }
                    },
                    actions = {
                        if (state.book != null && !state.showCoverScreen) {
                            IconButton(onClick = onOpenCoverScreen) {
                                Icon(Icons.Default.Home, contentDescription = "Volver a portada y biblioteca")
                            }
                        }
                        IconButton(
                            onClick = {
                                onRefreshVoices()
                                showSettings = true
                            }
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Ajustes de voz e IA")
                        }
                        IconButton(onClick = onOpenFilePicker) {
                            Icon(Icons.Default.Add, contentDescription = "Importar EPUB")
                        }
                    }
                )
            },
            bottomBar = {
                if (state.book != null && !state.showCoverScreen) {
                    PodcastPlayerBar(
                        book = state.book,
                        playback = state.playback,
                        indexingProgress = state.indexingProgress,
                        onTogglePlayPause = onTogglePlayPause,
                        onNextParagraph = onNextParagraph,
                        onPreviousParagraph = onPreviousParagraph,
                        onSpeedChange = onSpeedChange,
                        onSynthesisModeChange = onSynthesisModeChange,
                        onMicClick = onMicClick
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    state.book == null -> {
                        EmptyLibraryView(
                            onOpenFilePicker = onOpenFilePicker,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    state.showCoverScreen -> {
                        BookCoverResumeView(
                            book = state.book,
                            playback = state.playback,
                            library = state.library,
                            onContinuePlay = { onContinueFromCover(true) },
                            onContinueReadOnly = { onContinueFromCover(false) },
                            onRestartFromBeginning = onRestartFromBeginning,
                            onSelectBookFromLibrary = onSelectBookFromLibrary,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        ChapterReaderContent(
                            book = state.book,
                            playback = state.playback,
                            onParagraphClick = onParagraphClick
                        )
                    }
                }
            }
        }
    }

    if (state.voiceState.isDialogOpen) {
        VoiceAssistantDialog(
            voiceState = state.voiceState,
            currentChapterNumber = state.playback.currentChapterIndex + 1,
            onStartListening = onMicClick,
            onSubmitQuestion = onSubmitVoiceQuestion,
            onClose = onCloseVoiceDialog
        )
    }

    if (showSettings) {
        SettingsDialog(
            currentSettings = state.settings,
            localVoices = state.localVoices,
            modelsState = state.modelsState,
            onFetchModels = onFetchModels,
            onSave = onSaveSettings,
            onDismiss = { showSettings = false },
            sherpaTtsReady = sherpaTtsReady,
            sherpaTtsLoading = sherpaTtsLoading,
            sherpaTtsError = sherpaTtsError,
            onSelectSherpaTts = onSelectSherpaTts,
            onTestSherpaTts = onTestSherpaTts,
            onClearTestMessage = onClearTestMessage
        )
    }

    if (sherpaDialogOpen && sherpaTestMessage != null) {
        AlertDialog(
            onDismissRequest = {
                sherpaDialogOpen = false
                onClearTestMessage()
            },
            title = {
                Text("Sherpa-ONNX")
            },
            text = {
                Text(sherpaTestMessage ?: "")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sherpaDialogOpen = false
                        onClearTestMessage()
                    }
                ) {
                    Text("Aceptar")
                }
            }
        )
    }
}

@Composable
private fun BookCoverResumeView(
    book: Book,
    playback: PlaybackUiState,
    library: List<LibraryBookEntry>,
    onContinuePlay: () -> Unit,
    onContinueReadOnly: () -> Unit,
    onRestartFromBeginning: () -> Unit,
    onSelectBookFromLibrary: (LibraryBookEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverBitmap = remember(book.id, book.coverImageBytes) {
        book.coverImageBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                .getOrNull()
        }
    }
    val progressFraction = book.progressFraction(
        chapterIndex = playback.currentChapterIndex,
        paragraphIndex = playback.currentParagraphIndex
    )
    val percentInt = (progressFraction * 100).toInt()
    val currentChapterTitle = book.chapters.getOrNull(playback.currentChapterIndex)?.title
        ?: "Capítulo ${playback.currentChapterIndex + 1}"

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .widthIn(max = 220.dp)
                .fillMaxWidth(0.58f)
                .aspectRatio(0.68f)
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap,
                    contentDescription = "Portada de ${book.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = book.author,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(18.dp))

        Text(
            text = "$currentChapterTitle · $percentInt% leído",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onContinuePlay,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Continuar escuchando ($percentInt%)")
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onContinueReadOnly) {
                Text("Solo leer")
            }
            OutlinedButton(onClick = onRestartFromBeginning) {
                Text("Desde el inicio")
            }
        }

        val otherBooks = library.filter { it.bookId != book.id }
        if (otherBooks.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Tu biblioteca",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(otherBooks, key = { it.bookId }) { entry ->
                    val thumb = remember(entry.bookId, entry.coverPath) {
                        entry.coverPath?.let { path ->
                            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
                        }
                    }
                    Surface(
                        tonalElevation = 4.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .width(120.dp)
                            .clickable { onSelectBookFromLibrary(entry) }
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.7f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb,
                                        contentDescription = entry.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${entry.progressPercent}% leído",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryView(
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Tu lector estilo podcast",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Importa un libro en formato .epub para escucharlo con control de velocidad sin distorsión.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onOpenFilePicker) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Importar libro (.epub)")
        }
    }
}

@Composable
private fun ChapterReaderContent(
    book: Book,
    playback: PlaybackUiState,
    onParagraphClick: (Int, Int) -> Unit
) {
    val chapter = book.chapters.getOrNull(playback.currentChapterIndex) ?: return
    val listState = rememberLazyListState()

    LaunchedEffect(playback.currentChapterIndex, playback.currentParagraphIndex) {
        val targetIdx = (playback.currentParagraphIndex + 1).coerceIn(0, chapter.paragraphs.size)
        listState.animateScrollToItem(targetIdx)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "chapter_header_${chapter.index}") {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        items(
            items = chapter.paragraphs,
            key = { p -> "${chapter.index}_${p.index}" }
        ) { paragraph ->
            val isActive = paragraph.index == playback.currentParagraphIndex
            val bgColor = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .clickable { onParagraphClick(chapter.index, paragraph.index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = paragraph.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 26.sp,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                    }
                )
            }
        }
    }
}

@Composable
private fun PodcastPlayerBar(
    book: Book,
    playback: PlaybackUiState,
    indexingProgress: com.podcastreader.data.rag.IndexingProgress?,
    onTogglePlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onPreviousParagraph: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSynthesisModeChange: (SynthesisMode) -> Unit,
    onMicClick: () -> Unit
) {
    val currentChapter = book.chapters.getOrNull(playback.currentChapterIndex)
    val totalParagraphs = currentChapter?.paragraphs?.size ?: 0
    val progressFraction = book.progressFraction(
        chapterIndex = playback.currentChapterIndex,
        paragraphIndex = playback.currentParagraphIndex
    )
    val percentInt = (progressFraction * 100).toInt()
    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    var showSpeedMenu by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .padding(bottom = 0.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Cap. ${playback.currentChapterIndex + 1}/${book.chapters.size} · Párrafo ${playback.currentParagraphIndex + 1}/$totalParagraphs · $percentInt%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (indexingProgress != null && indexingProgress.bookId == book.id &&
                        indexingProgress.indexedChapters < indexingProgress.totalChapters
                    ) {
                        Text(
                            text = "Indexando RAG: ${indexingProgress.indexedChapters}/${indexingProgress.totalChapters} cap.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                FilterChip(
                    selected = playback.synthesisMode == SynthesisMode.PODCAST_DIALOGUE,
                    onClick = {
                        val nextMode = if (playback.synthesisMode == SynthesisMode.LITERAL) {
                            SynthesisMode.PODCAST_DIALOGUE
                        } else {
                            SynthesisMode.LITERAL
                        }
                        onSynthesisModeChange(nextMode)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            text = if (playback.synthesisMode == SynthesisMode.LITERAL) {
                                "Lectura Literal"
                            } else {
                                "Podcast Dual"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(onClick = { showSpeedMenu = true }) {
                        Text("${playback.playbackSpeed}x")
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        speedOptions.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed}x") },
                                onClick = {
                                    showSpeedMenu = false
                                    onSpeedChange(speed)
                                }
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = onPreviousParagraph) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Párrafo anterior",
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Button(
                        onClick = onTogglePlayPause,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        if (playback.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (playback.isPlaying) {
                                    Icons.Default.Pause
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = if (playback.isPlaying) "Pausar" else "Reproducir",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    IconButton(onClick = onNextParagraph) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Siguiente párrafo",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                FilledTonalIconButton(onClick = onMicClick) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Hablar con el libro"
                    )
                }
            }
        }
    }
}
