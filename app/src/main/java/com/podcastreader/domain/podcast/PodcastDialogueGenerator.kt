package com.podcastreader.domain.podcast

import com.podcastreader.domain.model.Chapter
import com.podcastreader.domain.rag.LlmClient

data class DialogueTurn(
    val speaker: String,
    val text: String
)

class PodcastDialogueGenerator(
    private val llmClient: LlmClient
) {
    suspend fun generateChapterDialogue(chapter: Chapter): List<DialogueTurn> {
        val chapterText = chapter.paragraphs.joinToString("\n\n") { it.text }.take(12_000)

        val systemPrompt = """
            Eres el guionista de un podcast literario en español con dos presentadores: Ana y Carlos.
            Transforma el capítulo en una conversación amena, natural y expresiva donde comentan y narran lo que ocurre sin inventar hechos.
            FORMATO ESTRICTO: cada línea debe empezar exactamente con "[Ana]: " o "[Carlos]: ".
            Genera entre 6 y 10 intervenciones cortas ideales para ser leídas en voz alta.
        """.trimIndent()

        val raw = llmClient.generateResponse(
            systemPrompt = systemPrompt,
            userPrompt = "Capítulo: ${chapter.title}\n\n$chapterText",
            temperature = 0.4f
        )

        val parsed = raw.lines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("[Ana]:") ->
                        DialogueTurn("Ana", trimmed.removePrefix("[Ana]:").trim())
                    trimmed.startsWith("[Carlos]:") ->
                        DialogueTurn("Carlos", trimmed.removePrefix("[Carlos]:").trim())
                    else -> null
                }
            }
            .filter { it.text.isNotBlank() }

        return parsed.ifEmpty {
            listOf(DialogueTurn("Ana", raw))
        }
    }
}
