package com.podcastreader

import com.podcastreader.data.tts.SpanishIpaPhonemizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishIpaPhonemizerTest {

    @Test
    fun `convierte frase en espanol a fonemas IPA con acentos y alofonos`() {
        val ipa = SpanishIpaPhonemizer.textToIpa("Hola, Capítulo 1: El caballero de la armadura.")
        assertTrue("Debe contener acento primario IPA", ipa.contains("ˈ"))
        assertTrue("Debe convertir números a palabras fonemizadas", ipa.contains("ˈuno"))
        val codepoints = SpanishIpaPhonemizer.textToIpaCodepoints("Hola, mundo.")
        assertTrue("Los codepoints no deben estar vacíos", codepoints.isNotEmpty())
        assertEquals("ˈola, mˈundo.", SpanishIpaPhonemizer.textToIpa("Hola, mundo."))
    }

    @Test
    fun `maneja digrafos ch ll rr qu gu y letras mudas h`() {
        val ipa = SpanishIpaPhonemizer.textToIpa("El perro chico llegó a la guerra sin hacer ruido.")
        assertTrue(ipa.contains("pˈero"))
        assertTrue(ipa.contains("tʃˈiko"))
        assertTrue(ipa.contains("ʎeɣˈo"))
        assertTrue(ipa.contains("ɣˈera"))
    }
}
