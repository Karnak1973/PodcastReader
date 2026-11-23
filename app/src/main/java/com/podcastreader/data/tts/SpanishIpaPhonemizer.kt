package com.podcastreader.data.tts

import java.util.Locale

/**
 * Fonemizador G2P (Grafema a Fonema IPA) en español de España (`es_ES`) compatible con
 * modelos VITS/Piper (`es_ES-davefx-medium.onnx`) en Sherpa-ONNX >= 2.0.0.
 *
 * A partir de Sherpa-ONNX 2.0.0 se eliminó `espeak-ng` del binario nativo (`--vits-data-dir`
 * se ignora) y el motor requiere recibir los codepoints Unicode de los fonemas IPA a través
 * de `GenerationConfig.phonemeCodepoints`.
 *
 * Todos los símbolos emitidos pertenecen al mapa `tokens.txt` del modelo `es_ES-davefx-medium`:
 * - Vocales: a, e, i, o, u (y semivocales j, w en diptongos)
 * - Acento primario: ˈ (U+02C8) inmediatamente antes de la vocal tónica
 * - Consonantes y alófonos: b, β, d, ð, f, ɡ, ɣ, k, l, ʎ, m, n, ŋ, ɲ, p, r, ɾ, s, t, tʃ, θ, x, ʝ
 * - Puntuación prosódica: espacio, ',', '.', ';', ':', '?', '!', '-'
 */
object SpanishIpaPhonemizer {

    private val UNSTRESSED_WORDS = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas",
        "de", "del", "a", "al", "en", "con", "por", "para", "sin", "sobre", "tras", "ante", "bajo", "hacia", "hasta",
        "que", "como", "cuando", "donde", "si", "mas", "pero", "sino", "aunque", "porque", "pues",
        "y", "e", "o", "u", "ni",
        "me", "te", "se", "nos", "os", "lo", "le", "les",
        "mi", "mis", "tu", "tus", "su", "sus"
    )

    private val ALLOWED_CODEPOINTS = setOf(
        ' '.code, '!'.code, '\''.code, '('.code, ')'.code, ','.code, '-'.code, '.'.code, ':'.code, ';'.code, '?'.code,
        'a'.code, 'b'.code, 'c'.code, 'd'.code, 'e'.code, 'f'.code, 'h'.code, 'i'.code, 'j'.code, 'k'.code, 'l'.code,
        'm'.code, 'n'.code, 'o'.code, 'p'.code, 'q'.code, 'r'.code, 's'.code, 't'.code, 'u'.code, 'v'.code, 'w'.code,
        'x'.code, 'y'.code, 'z'.code,
        'ð'.code, 'ŋ'.code, 'ɛ'.code, 'ɡ'.code, 'ɣ'.code, 'ɲ'.code, 'ɾ'.code, 'ʃ'.code, 'ʎ'.code, 'ʒ'.code, 'ʝ'.code,
        'ˈ'.code, 'ˌ'.code, 'ː'.code, 'β'.code, 'θ'.code, 'χ'.code
    )

    /**
     * Convierte una frase en español a un arreglo de codepoints Unicode IPA para
     * `GenerationConfig.phonemeCodepoints`.
     */
    fun textToIpaCodepoints(text: String): IntArray {
        val ipa = textToIpa(text)
        if (ipa.isBlank()) return IntArray(0)
        return ipa.codePoints()
            .filter { ALLOWED_CODEPOINTS.contains(it) }
            .toArray()
    }

    fun textToIpa(text: String): String {
        val normalized = normalizeInput(text)
        if (normalized.isBlank()) return ""

        val sb = StringBuilder()
        var i = 0
        val n = normalized.length
        var afterPause = true

        while (i < n) {
            val ch = normalized[i]
            when {
                ch.isLetter() -> {
                    val start = i
                    while (i < n && normalized[i].isLetter()) {
                        i++
                    }
                    val word = normalized.substring(start, i)
                    val wordIpa = phonemizeWord(word, afterPause)
                    sb.append(wordIpa)
                    afterPause = false
                }
                ch.isDigit() -> {
                    val start = i
                    while (i < n && normalized[i].isDigit()) {
                        i++
                    }
                    val numStr = normalized.substring(start, i)
                    val numWords = numberToSpanishWords(numStr.toLongOrNull() ?: 0L)
                    for ((idx, w) in numWords.split(" ").filter { it.isNotBlank() }.withIndex()) {
                        if (idx > 0 || (sb.isNotEmpty() && sb.last() != ' ')) {
                            sb.append(' ')
                        }
                        sb.append(phonemizeWord(w, afterPause && idx == 0))
                    }
                    afterPause = false
                }
                ch in ",.;:?!" -> {
                    sb.append(ch)
                    afterPause = true
                    i++
                }
                ch == '-' -> {
                    sb.append(", ")
                    afterPause = true
                    i++
                }
                ch.isWhitespace() -> {
                    if (sb.isNotEmpty() && sb.last() != ' ') {
                        sb.append(' ')
                    }
                    i++
                }
                else -> {
                    i++
                }
            }
        }

        val result = sb.toString().replace(Regex("\\s+"), " ").trim()
        return if (result.isNotEmpty() && result.last() !in ".,;:?!") {
            "$result."
        } else {
            result
        }
    }

    private fun normalizeInput(text: String): String {
        return text
            .lowercase(Locale("es", "ES"))
            .replace("¿", "")
            .replace("¡", "")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("…", ".")
            .replace("«", "")
            .replace("»", "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("'", "")
    }

    private fun phonemizeWord(rawWord: String, afterPauseInitial: Boolean): String {
        if (rawWord.isEmpty()) return ""
        if (rawWord == "y") return "i"

        val isUnstressedWord = rawWord in UNSTRESSED_WORDS && !rawWord.any { it in "áéíóú" }
        val stressedVowelCharIndex = if (isUnstressedWord) -1 else findStressedVowelIndex(rawWord)

        val out = StringBuilder()
        var i = 0
        val len = rawWord.length
        var prevChar: Char? = if (afterPauseInitial) null else 'a' // 'a' simula contexto intervocálico previo

        while (i < len) {
            val c = rawWord[i]
            val next = if (i + 1 < len) rawWord[i + 1] else null
            val next2 = if (i + 2 < len) rawWord[i + 2] else null

            // Si estamos en la vocal tónica, insertar marca de acento primario ˈ
            if (i == stressedVowelCharIndex) {
                out.append('ˈ')
            }

            when (c) {
                'a', 'á' -> {
                    out.append('a')
                    prevChar = 'a'
                    i++
                }
                'e', 'é' -> {
                    out.append('e')
                    prevChar = 'e'
                    i++
                }
                'o', 'ó' -> {
                    out.append('o')
                    prevChar = 'o'
                    i++
                }
                'i', 'í' -> {
                    val isStressedHere = (i == stressedVowelCharIndex) || c == 'í'
                    val adjacentToVowel = (next != null && isVowel(next)) || (prevChar != null && isVowel(prevChar))
                    if (!isStressedHere && adjacentToVowel) {
                        out.append('j')
                    } else {
                        out.append('i')
                    }
                    prevChar = 'i'
                    i++
                }
                'u', 'ú', 'ü' -> {
                    val isStressedHere = (i == stressedVowelCharIndex) || c == 'ú'
                    val adjacentToVowel = (next != null && isVowel(next)) || (prevChar != null && isVowel(prevChar))
                    if (!isStressedHere && adjacentToVowel) {
                        out.append('w')
                    } else {
                        out.append('u')
                    }
                    prevChar = 'u'
                    i++
                }
                'y' -> {
                    if (i == len - 1) {
                        out.append('i')
                        prevChar = 'i'
                    } else {
                        out.append('ʝ')
                        prevChar = 'y'
                    }
                    i++
                }
                'b', 'v' -> {
                    if (prevChar == null || prevChar == 'm' || prevChar == 'n') {
                        out.append('b')
                    } else {
                        out.append('β')
                    }
                    prevChar = 'b'
                    i++
                }
                'c' -> {
                    when {
                        next == 'h' -> {
                            out.append("tʃ")
                            prevChar = 'c'
                            i += 2
                        }
                        next != null && next in "eiéí" -> {
                            out.append('θ')
                            prevChar = 'z'
                            i++
                        }
                        else -> {
                            out.append('k')
                            prevChar = 'k'
                            i++
                        }
                    }
                }
                'd' -> {
                    if (prevChar == null || prevChar == 'n' || prevChar == 'l') {
                        out.append('d')
                    } else {
                        out.append('ð')
                    }
                    prevChar = 'd'
                    i++
                }
                'f' -> {
                    out.append('f')
                    prevChar = 'f'
                    i++
                }
                'g' -> {
                    val stopAllophone = (prevChar == null || prevChar == 'n' || prevChar == 'm')
                    when {
                        next == 'u' && next2 != null && next2 in "eiéí" -> {
                            out.append(if (stopAllophone) 'ɡ' else 'ɣ')
                            prevChar = 'g'
                            i += 2 // la 'u' es muda en gue/gui
                        }
                        next == 'ü' && next2 != null && next2 in "eiéí" -> {
                            out.append(if (stopAllophone) 'ɡ' else 'ɣ')
                            out.append('w')
                            prevChar = 'u'
                            i += 2
                        }
                        next != null && next in "eiéí" -> {
                            out.append('x')
                            prevChar = 'x'
                            i++
                        }
                        else -> {
                            out.append(if (stopAllophone) 'ɡ' else 'ɣ')
                            prevChar = 'g'
                            i++
                        }
                    }
                }
                'h' -> {
                    // 'h' muda en español
                    i++
                }
                'j' -> {
                    out.append('x')
                    prevChar = 'x'
                    i++
                }
                'k' -> {
                    out.append('k')
                    prevChar = 'k'
                    i++
                }
                'l' -> {
                    if (next == 'l') {
                        out.append('ʎ')
                        prevChar = 'l'
                        i += 2
                    } else {
                        out.append('l')
                        prevChar = 'l'
                        i++
                    }
                }
                'm' -> {
                    out.append('m')
                    prevChar = 'm'
                    i++
                }
                'n' -> {
                    val beforeVelar = next != null && (
                        next in "kgjxq" ||
                            (next == 'c' && (next2 == null || next2 !in "eiéíh"))
                        )
                    out.append(if (beforeVelar) 'ŋ' else 'n')
                    prevChar = 'n'
                    i++
                }
                'ñ' -> {
                    out.append('ɲ')
                    prevChar = 'ñ'
                    i++
                }
                'p' -> {
                    if (i == 0 && next == 's') {
                        // 'psicología' -> 'sikoloxˈia'
                        i++
                    } else {
                        out.append('p')
                        prevChar = 'p'
                        i++
                    }
                }
                'q' -> {
                    if (next == 'u' && next2 != null && next2 in "eiéí") {
                        out.append('k')
                        prevChar = 'k'
                        i += 2
                    } else {
                        out.append('k')
                        prevChar = 'k'
                        i++
                    }
                }
                'r' -> {
                    when {
                        next == 'r' -> {
                            out.append('r')
                            prevChar = 'r'
                            i += 2
                        }
                        i == 0 || prevChar == 'n' || prevChar == 'l' || prevChar == 's' -> {
                            out.append('r')
                            prevChar = 'r'
                            i++
                        }
                        else -> {
                            out.append('ɾ')
                            prevChar = 'r'
                            i++
                        }
                    }
                }
                's' -> {
                    out.append('s')
                    prevChar = 's'
                    i++
                }
                't' -> {
                    out.append('t')
                    prevChar = 't'
                    i++
                }
                'w' -> {
                    out.append('w')
                    prevChar = 'w'
                    i++
                }
                'x' -> {
                    if (i == 0) {
                        out.append('s')
                    } else {
                        out.append("ks")
                    }
                    prevChar = 's'
                    i++
                }
                'z' -> {
                    out.append('θ')
                    prevChar = 'z'
                    i++
                }
                else -> {
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Determina el índice del carácter de la vocal tónica según las reglas ortográficas del español:
     * 1. Si hay tilde explícita (á, é, í, ó, ú), esa vocal lleva el acento.
     * 2. Si no hay tilde, agrupamos núcleos vocálicos (sílabas):
     *    - Si la palabra termina en vocal, 'n' o 's' (llana), el acento recae en el penúltimo núcleo.
     *    - Si termina en otra consonante (aguda), recae en el último núcleo.
     */
    private fun findStressedVowelIndex(word: String): Int {
        val explicitIdx = word.indexOfFirst { it in "áéíóú" }
        if (explicitIdx >= 0) return explicitIdx

        // Encontrar los grupos vocálicos reales (excluyendo la 'u' muda de que/qui/gue/gui)
        val nuclei = mutableListOf<Int>()
        var i = 0
        while (i < word.length) {
            if (isEffectiveVowelAt(word, i)) {
                val start = i
                var end = i
                while (end + 1 < word.length && isEffectiveVowelAt(word, end + 1) &&
                    !isHiatus(word[end], word[end + 1])
                ) {
                    end++
                }
                // Dentro de un diptongo, la vocal tónica es la abierta (a, e, o) o la segunda si ambas son cerradas (iu, ui)
                var chosen = start
                for (k in start..end) {
                    if (word[k] in "aeoáéó") {
                        chosen = k
                        break
                    } else {
                        chosen = k
                    }
                }
                nuclei.add(chosen)
                i = end + 1
            } else {
                i++
            }
        }

        if (nuclei.isEmpty()) return -1
        if (nuclei.size == 1) return nuclei[0]

        val lastChar = word.last()
        val isLlana = isVowel(lastChar) || lastChar == 'n' || lastChar == 's'
        return if (isLlana) {
            nuclei[nuclei.size - 2]
        } else {
            nuclei.last()
        }
    }

    private fun isEffectiveVowelAt(word: String, idx: Int): Boolean {
        val c = word[idx]
        if (c == 'u' && idx > 0 && idx + 1 < word.length) {
            val prev = word[idx - 1]
            val next = word[idx + 1]
            if ((prev == 'q' || prev == 'g') && next in "eiéí") {
                return false // 'u' muda
            }
        }
        if (c == 'y' && idx == word.length - 1) return true
        return isVowel(c)
    }

    private fun isVowel(c: Char): Boolean = c in "aeiouáéíóúü"

    private fun isStrongVowel(c: Char): Boolean = c in "aeoáéóíú"

    private fun isHiatus(v1: Char, v2: Char): Boolean = isStrongVowel(v1) && isStrongVowel(v2)

    private fun numberToSpanishWords(n: Long): String {
        if (n == 0L) return "cero"
        if (n < 0L) return "menos ${numberToSpanishWords(-n)}"

        val unidades = arrayOf(
            "", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
            "diez", "once", "doce", "trece", "catorce", "quince",
            "dieciséis", "diecisiete", "dieciocho", "diecinueve",
            "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro",
            "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"
        )
        val decenas = arrayOf(
            "", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"
        )
        val centenas = arrayOf(
            "", "ciento", "doscientos", "trescientos", "cuatrocientos",
            "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos"
        )

        return when {
            n < 30L -> unidades[n.toInt()]
            n < 100L -> {
                val d = (n / 10L).toInt()
                val r = (n % 10L).toInt()
                if (r == 0) decenas[d] else "${decenas[d]} y ${unidades[r]}"
            }
            n == 100L -> "cien"
            n < 1000L -> {
                val c = (n / 100L).toInt()
                val r = n % 100L
                if (r == 0L) centenas[c] else "${centenas[c]} ${numberToSpanishWords(r)}"
            }
            n == 1000L -> "mil"
            n < 2000L -> "mil ${numberToSpanishWords(n % 1000L)}"
            n < 1_000_000L -> {
                val miles = n / 1000L
                val r = n % 1000L
                val milesStr = if (miles == 1L) "mil" else "${numberToSpanishWords(miles)} mil"
                if (r == 0L) milesStr else "$milesStr ${numberToSpanishWords(r)}"
            }
            else -> n.toString()
        }
    }
}
