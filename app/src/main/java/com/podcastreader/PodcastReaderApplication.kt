package com.podcastreader

import android.app.Application
import com.podcastreader.data.local.ApiSettingsDataStore
import com.podcastreader.data.local.BookDatabase
import com.podcastreader.data.local.BookLibraryStore
import com.podcastreader.data.local.ReadingProgressDataStore
import com.podcastreader.data.parser.EpubBookParser
import com.podcastreader.data.parser.MobiBookParserStub
import com.podcastreader.data.rag.ConfigurableEmbeddingProvider
import com.podcastreader.data.rag.ConfigurableLlmClient
import com.podcastreader.data.tts.AndroidTtsEngine
import com.podcastreader.data.tts.AudioChunkCacheManager
import com.podcastreader.data.tts.CloudTtsEngine
import com.podcastreader.data.tts.CompositeTtsEngine
import com.podcastreader.data.tts.SherpaOnnxTtsEngine
import com.podcastreader.domain.chunking.BookSemanticChunker
import com.podcastreader.domain.parser.BookParser
import com.podcastreader.domain.podcast.PodcastDialogueGenerator
import com.podcastreader.domain.rag.AntiSpoilerRagEngine
import com.podcastreader.domain.tts.TtsEngine
import com.podcastreader.playback.PlaybackController
import com.podcastreader.voice.VoiceAssistantManager

class PodcastReaderApplication : Application() {

    lateinit var database: BookDatabase
        private set
    lateinit var progressDataStore: ReadingProgressDataStore
        private set
    lateinit var apiSettingsDataStore: ApiSettingsDataStore
        private set
    lateinit var bookLibraryStore: BookLibraryStore
        private set
    lateinit var androidTtsEngine: AndroidTtsEngine
        private set
    lateinit var sherpaTtsEngine: SherpaOnnxTtsEngine
        private set
    lateinit var ttsEngine: TtsEngine
        private set
    lateinit var chunkCacheManager: AudioChunkCacheManager
        private set
    lateinit var ragEngine: AntiSpoilerRagEngine
        private set
    lateinit var dialogueGenerator: PodcastDialogueGenerator
        private set
    lateinit var playbackController: PlaybackController
        private set
    lateinit var voiceAssistantManager: VoiceAssistantManager
        private set
    lateinit var parsers: List<BookParser>
        private set

    override fun onCreate() {
        super.onCreate()
        database = BookDatabase.getInstance(this)
        progressDataStore = ReadingProgressDataStore(this)
        apiSettingsDataStore = ApiSettingsDataStore(this)
        bookLibraryStore = BookLibraryStore(this)

        androidTtsEngine = AndroidTtsEngine(this)
        sherpaTtsEngine = SherpaOnnxTtsEngine(this)
        val cloudTtsEngine = CloudTtsEngine()
        ttsEngine = CompositeTtsEngine(
            androidTtsEngine,
            sherpaTtsEngine,
            cloudTtsEngine,
            apiSettingsDataStore
        )

        chunkCacheManager = AudioChunkCacheManager(this, ttsEngine, apiSettingsDataStore)

        val embeddingProvider = ConfigurableEmbeddingProvider(apiSettingsDataStore)
        val llmClient = ConfigurableLlmClient(apiSettingsDataStore)
        ragEngine = AntiSpoilerRagEngine(
            dao = database.bookChunkDao(),
            chunker = BookSemanticChunker(),
            embeddingProvider = embeddingProvider,
            llmClient = llmClient
        )
        dialogueGenerator = PodcastDialogueGenerator(llmClient)

        playbackController = PlaybackController(
            context = this,
            chunkCache = chunkCacheManager,
            progressDataStore = progressDataStore,
            dialogueGenerator = dialogueGenerator
        )
        voiceAssistantManager = VoiceAssistantManager(
            context = this,
            ragEngine = ragEngine,
            ttsEngine = ttsEngine
        )
        parsers = listOf(EpubBookParser(), MobiBookParserStub())
    }
}
