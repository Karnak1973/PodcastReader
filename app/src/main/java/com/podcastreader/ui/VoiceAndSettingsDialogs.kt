package com.podcastreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.podcastreader.data.local.AiProviderType
import com.podcastreader.data.local.ApiSettingsDataStore
import com.podcastreader.data.local.AppSettings
import com.podcastreader.data.local.TtsProviderType
import com.podcastreader.domain.tts.TtsVoiceOption
import com.podcastreader.voice.VoiceAssistantUiState
import com.podcastreader.voice.VoicePhase

@Composable
fun VoiceAssistantDialog(
    voiceState: VoiceAssistantUiState,
    currentChapterNumber: Int,
    onStartListening: () -> Unit,
    onSubmitQuestion: (String) -> Unit,
    onClose: (resumeBook: Boolean) -> Unit
) {
    var typedQuestion by remember(voiceState.transcript) {
        mutableStateOf(voiceState.transcript)
    }

    AlertDialog(
        onDismissRequest = { onClose(true) },
        title = {
            Text(
                text = "Hablar con el libro (Cap. $currentChapterNumber)",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (voiceState.phase) {
                        VoicePhase.LISTENING -> {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Escuchando tu pregunta...", color = MaterialTheme.colorScheme.primary)
                        }
                        VoicePhase.THINKING -> {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Buscando en lo ya leído (sin spoilers)...")
                        }
                        VoicePhase.SPEAKING -> {
                            Text("Leyendo respuesta...", color = MaterialTheme.colorScheme.primary)
                        }
                        VoicePhase.ERROR -> {
                            Text(
                                text = voiceState.errorMessage ?: "Error",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        VoicePhase.IDLE -> {
                            Text(
                                text = "Pregunta por personajes, trama o pide un resumen de capítulos anteriores.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = typedQuestion,
                        onValueChange = { typedQuestion = it },
                        label = { Text("Tu pregunta") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onStartListening) {
                        Icon(Icons.Default.Mic, contentDescription = "Hablar")
                    }
                    IconButton(
                        onClick = { onSubmitQuestion(typedQuestion) },
                        enabled = typedQuestion.isNotBlank() && voiceState.phase != VoicePhase.THINKING
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                    }
                }

                if (voiceState.answer.isNotBlank()) {
                    Text(
                        text = "Respuesta:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = voiceState.answer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onClose(true) }) {
                Text("Reanudar libro")
            }
        },
        dismissButton = {
            TextButton(onClick = { onClose(false) }) {
                Text("Cerrar")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    localVoices: List<TtsVoiceOption>,
    modelsState: ModelsFetchState,
    onFetchModels: (AiProviderType, String, String) -> Unit,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var ttsProvider by remember { mutableStateOf(currentSettings.ttsProvider) }
    var androidVoiceName by remember { mutableStateOf(currentSettings.androidVoiceName) }
    var cloudVoiceName by remember { mutableStateOf(currentSettings.cloudVoiceName) }
    var aiProvider by remember { mutableStateOf(currentSettings.aiProvider) }
    var apiKey by remember { mutableStateOf(currentSettings.apiKey) }
    var baseUrl by remember { mutableStateOf(currentSettings.baseUrl) }
    var llmModel by remember { mutableStateOf(currentSettings.llmModel) }
    var embeddingModel by remember { mutableStateOf(currentSettings.embeddingModel) }

    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var llmMenuExpanded by remember { mutableStateOf(false) }
    var embMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voz Natural e IA") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Motor de Voz (TTS)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = ttsProvider == TtsProviderType.ANDROID_LOCAL,
                        onClick = { ttsProvider = TtsProviderType.ANDROID_LOCAL },
                        label = { Text("Local (Android)") }
                    )
                    FilterChip(
                        selected = ttsProvider == TtsProviderType.SHERPA_ONNX_LOCAL,
                        onClick = { ttsProvider = TtsProviderType.SHERPA_ONNX_LOCAL },
                        label = { Text("Sherpa-ONNX (español offline)" ) }
                    )
                    FilterChip(
                        selected = ttsProvider == TtsProviderType.GEMINI_TTS,
                        onClick = {
                            ttsProvider = TtsProviderType.GEMINI_TTS
                            if (cloudVoiceName.isBlank() || cloudVoiceName == "nova") {
                                cloudVoiceName = "Kore"
                            }
                        },
                        label = { Text("Gemini TTS") }
                    )
                    FilterChip(
                        selected = ttsProvider == TtsProviderType.OPENAI_TTS,
                        onClick = {
                            ttsProvider = TtsProviderType.OPENAI_TTS
                            if (cloudVoiceName.isBlank() || cloudVoiceName == "Kore") {
                                cloudVoiceName = "nova"
                            }
                        },
                        label = { Text("OpenAI") }
                    )
                }

                if (ttsProvider == TtsProviderType.ANDROID_LOCAL) {
                    val selectedLabel = localVoices.firstOrNull { it.id == androidVoiceName }?.label
                        ?: "Automática (mejor calidad)"
                    ExposedDropdownMenuBox(
                        expanded = voiceMenuExpanded,
                        onExpandedChange = { voiceMenuExpanded = !voiceMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voz española instalada") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Automática (mejor calidad)") },
                                onClick = {
                                    androidVoiceName = ""
                                    voiceMenuExpanded = false
                                }
                            )
                            localVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text(voice.label) },
                                    onClick = {
                                        androidVoiceName = voice.id
                                        voiceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = cloudVoiceName,
                        onValueChange = { cloudVoiceName = it },
                        label = {
                            Text(
                                if (ttsProvider == TtsProviderType.GEMINI_TTS) {
                                    "Voz Gemini (Kore, Aoede, Charon, Puck)"
                                } else {
                                    "Voz OpenAI (nova, onyx, alloy, shimmer)"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("Proveedor IA (RAG + Podcast)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = aiProvider == AiProviderType.GEMINI,
                        onClick = {
                            aiProvider = AiProviderType.GEMINI
                            baseUrl = ApiSettingsDataStore.defaultBaseUrl(AiProviderType.GEMINI)
                            llmModel = ApiSettingsDataStore.defaultLlmModel(AiProviderType.GEMINI)
                            embeddingModel = ApiSettingsDataStore.defaultEmbeddingModel(AiProviderType.GEMINI)
                        },
                        label = { Text("Gemini") }
                    )
                    FilterChip(
                        selected = aiProvider == AiProviderType.OPENAI_COMPATIBLE,
                        onClick = {
                            aiProvider = AiProviderType.OPENAI_COMPATIBLE
                            baseUrl = ApiSettingsDataStore.defaultBaseUrl(AiProviderType.OPENAI_COMPATIBLE)
                            llmModel = ApiSettingsDataStore.defaultLlmModel(AiProviderType.OPENAI_COMPATIBLE)
                            embeddingModel = ApiSettingsDataStore.defaultEmbeddingModel(AiProviderType.OPENAI_COMPATIBLE)
                        },
                        label = { Text("OpenAI / Compat") }
                    )
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = { onFetchModels(aiProvider, apiKey, baseUrl) },
                    enabled = !modelsState.isFetching && apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (modelsState.isFetching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Consultando modelos...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Actualizar modelos disponibles")
                    }
                }

                modelsState.error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = llmMenuExpanded && modelsState.models.llmModels.isNotEmpty(),
                    onExpandedChange = {
                        if (modelsState.models.llmModels.isNotEmpty()) {
                            llmMenuExpanded = !llmMenuExpanded
                        }
                    }
                ) {
                    OutlinedTextField(
                        value = llmModel,
                        onValueChange = { llmModel = it },
                        label = {
                            Text(
                                if (modelsState.models.llmModels.isNotEmpty()) {
                                    "Modelo LLM (${modelsState.models.llmModels.size} disponibles)"
                                } else {
                                    "Modelo LLM"
                                }
                            )
                        },
                        trailingIcon = {
                            if (modelsState.models.llmModels.isNotEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = llmMenuExpanded)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = llmMenuExpanded && modelsState.models.llmModels.isNotEmpty(),
                        onDismissRequest = { llmMenuExpanded = false }
                    ) {
                        modelsState.models.llmModels.forEach { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = {
                                    llmModel = modelId
                                    llmMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = embMenuExpanded && modelsState.models.embeddingModels.isNotEmpty(),
                    onExpandedChange = {
                        if (modelsState.models.embeddingModels.isNotEmpty()) {
                            embMenuExpanded = !embMenuExpanded
                        }
                    }
                ) {
                    OutlinedTextField(
                        value = embeddingModel,
                        onValueChange = { embeddingModel = it },
                        label = {
                            Text(
                                if (modelsState.models.embeddingModels.isNotEmpty()) {
                                    "Modelo Embeddings (${modelsState.models.embeddingModels.size} disponibles)"
                                } else {
                                    "Modelo Embeddings"
                                }
                            )
                        },
                        trailingIcon = {
                            if (modelsState.models.embeddingModels.isNotEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = embMenuExpanded)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = embMenuExpanded && modelsState.models.embeddingModels.isNotEmpty(),
                        onDismissRequest = { embMenuExpanded = false }
                    ) {
                        modelsState.models.embeddingModels.forEach { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = {
                                    embeddingModel = modelId
                                    embMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        AppSettings(
                            ttsProvider = ttsProvider,
                            androidVoiceName = androidVoiceName,
                            cloudVoiceName = cloudVoiceName,
                            aiProvider = aiProvider,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            llmModel = llmModel,
                            embeddingModel = embeddingModel
                        )
                    )
                    onDismiss()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
