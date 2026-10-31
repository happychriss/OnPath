package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's English is US English (owner 2026-10-31, A-019 add-on: "Centre on my position" on an en-US phone):
 * no British spellings in the English texts. Keys are not checked – they keep their old names.
 */
class AmericanEnglishTest {
    private val british = Regex(
        "\\b(centre|centred|centres|metre|metres|kilometre|kilometres|litre|licence|licences|colour|colours|favourite|" +
            "behaviour|neighbour|neighbourhood|grey|cancelled|cancelling|travelled|travelling|organise|organised|" +
            "recognise|recognised|realise|optimise|optimised|customise|minimise|maximise|prioritise|synchronise|" +
            "synchronised|summarise|authorise|initialise|analyse|analysed|catalogue|dialogue|whilst|programme)\\b",
        RegexOption.IGNORE_CASE)

    @Test fun englishTextsUseAmericanSpelling() {
        val dir = File("../strings/src/commonMain/composeResources/values")
        val files = dir.listFiles { f -> f.name.startsWith("strings_") && f.name.endsWith(".xml") }.orEmpty()
        assertTrue("no English string files found in $dir", files.isNotEmpty())
        val value = Regex("<(?:string|item)[^>]*>([^<]*)<")
        val found = files.flatMap { f ->
            value.findAll(f.readText()).flatMap { m -> british.findAll(m.groupValues[1]).map { "${f.name}: ${it.value}" } }.toList()
        }
        assertEquals("British spellings in the English texts", emptyList<String>(), found)
    }
}
