# PodcastReader 🎙️📖

Lector inteligente de libros para Android con arquitectura modular, síntesis de voz estilo podcast, indexación semántica (RAG) con control antispoilers e interfaz conversacional interactiva por voz.

---

## 🌟 Características Principales

1. **Ingesta y Biblioteca Multi-Libro (EPUB / MOBI)**:
   - Extracción de metadatos, tabla de contenido (TOC) y portada del libro.
   - Limpieza y sanitización de HTML interno (Jsoup) en párrafos numerados (`ChapterId -> List<Paragraph>`).
   - Biblioteca local persistente que recuerda portada, capítulo, párrafo y porcentaje leído de cada libro.
   - Pantalla de inicio con reanudación instantánea ("Continuar escuchando", "Solo leer" o "Desde el inicio").

2. **Motor de Audio y Narración Estilo Podcast**:
   - Construido sobre **AndroidX Media3 (ExoPlayer)** + `MediaSessionService` para reproducción fluida en segundo plano y controles multimedia del sistema.
   - Control fino de velocidad sin distorsión de tono (`0.5x` a `3.0x`).
   - Cola de streaming por párrafos (*Chunking Buffer*) con pre-síntesis en segundo plano para eliminar pausas entre párrafos.
   - **Modo "Podcast Dual"**: Transforma el capítulo mediante IA en un diálogo ágil entre dos presentadores (Ana y Carlos) alternando entonaciones.

3. **Voz Natural e Hiperrealista (Local + Cloud)**:
   - **Voz Local**: Selección automática de la mejor voz en español del dispositivo (voces de muy alta calidad y neuronales) + catálogo selector de voces instaladas.
   - **Voz Cloud**: Integración con **Gemini 3.8 Flash TTS** (`gemini-3.8-flash-tts`, voces *Kore, Aoede, Charon, Puck*) y **OpenAI Audio** (`tts-1-hd`, voces *nova, onyx, alloy*).
   - Sistema de reintentos exponenciales automáticos ante saturación (503 / 429).
   - Fallback transparente al motor local si no hay conexión o falla la API.

4. **Memoria del Libro (RAG) con Filtro Antispoilers**:
   - Fragmentación inteligente respetando fronteras de párrafos (`BookSemanticChunker`).
   - Persistencia de fragmentos y vectores en **Room** con índices compuestos.
   - **Filtro Antispoilers estricto**: Si vas por el capítulo 4, párrafo 12, las consultas de IA filtran estrictamente `WHERE chapterIndex < :currentChapter OR (chapterIndex = :currentChapter AND startParagraphIndex <= :currentParagraph)`. ¡Imposible que el asistente te adelante sucesos futuros!
   - Indexación progresiva Zero-Wait mediante **WorkManager** en segundo plano.

5. **Interfaz de Voz Interactiva ("Hablar con el Libro")**:
   - Pausa inteligente del libro al pulsar el micrófono.
   - Reconocimiento de voz en español (`SpeechRecognizer`).
   - Respuestas habladas contextuales sobre tramas, personajes o resúmenes de capítulos anteriores.
   - Reanudación automática de la lectura al terminar la conversación.

6. **Selector y Actualizador Dinámico de Modelos**:
   - Consulta en vivo del catálogo de modelos asociados a tu API Key (Gemini y OpenAI-compatible) desde la pantalla de Ajustes (⚙️).

---

## 🏗️ Arquitectura Técnica

- **Lenguaje**: Kotlin 2.0
- **UI**: Jetpack Compose con Material 3
- **Patrón**: MVVM + Clean Architecture
- **Audio**: AndroidX Media3 (ExoPlayer, MediaSession, SessionToken)
- **Persistencia**: Room Database, AndroidX DataStore Preferences
- **Concurrencia**: Kotlin Coroutines, StateFlow, AndroidX WorkManager
- **Parsing**: Jsoup HTML Sanitizer, ZipInputStream OPF/NCX

---

## 🚀 Compilación e Instalación

### Requisitos
- Android Studio Ladybug / Koala o superior
- JDK 17
- Android SDK (minSdk 26, targetSdk 35)

### Compilar APK de Depuración
```bash
./gradlew assembleDebug
```

### Compilar APK Release
```bash
./gradlew assembleRelease
```
El archivo APK generado se ubicará en `app/build/outputs/apk/release/app-release.apk`.

### Ejecutar Pruebas Unitarias
```bash
./gradlew testDebugUnitTest
```

---

## 📄 Licencia

Este proyecto está bajo la Licencia **Apache 2.0**. Consulta el archivo [LICENSE](LICENSE) para más detalles.
