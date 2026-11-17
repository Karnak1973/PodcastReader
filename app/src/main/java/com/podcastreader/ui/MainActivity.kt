package com.podcastreader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastreader.ui.theme.PodcastReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PodcastReaderTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.uiState.collectAsState()

                val openDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        viewModel.importBook(uri)
                    }
                }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.startVoiceQuery()
                    } else {
                        viewModel.openVoiceTextDialog()
                    }
                }

                ReaderScreen(
                    state = state,
                    onOpenFilePicker = {
                        openDocumentLauncher.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/x-mobipocket-ebook",
                                "application/octet-stream"
                            )
                        )
                    },
                    onSelectChapter = { idx -> viewModel.selectChapter(idx, autoPlay = true) },
                    onParagraphClick = { ch, p -> viewModel.playFromParagraph(ch, p) },
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onNextParagraph = viewModel::nextParagraph,
                    onPreviousParagraph = viewModel::previousParagraph,
                    onSpeedChange = viewModel::setPlaybackSpeed,
                    onSynthesisModeChange = viewModel::setSynthesisMode,
                    onMicClick = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) {
                            viewModel.startVoiceQuery()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onSubmitVoiceQuestion = viewModel::submitTextQuestion,
                    onCloseVoiceDialog = viewModel::closeVoiceDialog,
                    onSaveSettings = viewModel::saveSettings,
                    onFetchModels = viewModel::fetchAvailableModels,
                    onRefreshVoices = viewModel::refreshLocalVoices,
                    onContinueFromCover = viewModel::continueFromCover,
                    onRestartFromBeginning = viewModel::restartFromBeginning,
                    onOpenCoverScreen = viewModel::openCoverScreen,
                    onSelectBookFromLibrary = viewModel::selectBookFromLibrary,
                    onDismissError = viewModel::clearError
                )
            }
        }
    }
}
