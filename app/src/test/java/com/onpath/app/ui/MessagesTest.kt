package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The message system's pure rules (docs/design/ui-style.md, owner 2026-10-31). */
class MessagesTest {
    private fun m(kind: MessageKind, title: String = kind.name) = Message(kind, title, buttons = listOf(MessageButton("OK") {}))

    /** One box at a time: a FRAGE wins over a HINWEIS; the others wait (they stay in their state). */
    @Test fun oneBoxAtATimeQuestionFirst() {
        val note = m(MessageKind.HINWEIS)
        val error = m(MessageKind.HINWEIS_ERROR)
        val question = m(MessageKind.FRAGE)
        assertEquals(question, Messages.pick(listOf(note, error, question)))
        assertEquals(question, Messages.pick(listOf(null, question, m(MessageKind.FRAGE, "later"))))
        // no question: the first must-know note in the caller's order
        assertEquals(error, Messages.pick(listOf(null, error, note)))
        assertEquals(note, Messages.pick(listOf(note, m(MessageKind.HINWEIS_WARN))))
        assertNull(Messages.pick(listOf(null, null)))
        assertNull(Messages.pick(emptyList()))
    }

    @Test fun oneTextSplitsIntoTitleAndDetail() {
        assertEquals("Couldn't find this place." to "Try again when you have internet.",
            Messages.split("Couldn't find this place. Try again when you have internet."))
        assertEquals("Paired." to "What you save in the browser comes to your phone – everything is kept there.",
            Messages.split("Paired. What you save in the browser comes to your phone – everything is kept there."))
        assertEquals("End the running tour first, then this route can be loaded." to null,
            Messages.split("End the running tour first, then this route can be loaded."))
        // "z. B." is no sentence end
        assertEquals("Karte fehlt, z. B. im Tal." to "Später nochmal.", Messages.split("Karte fehlt, z. B. im Tal. Später nochmal."))
        assertEquals("Nothing to split." to null, Messages.split("Nothing to split. "))
    }

    /** One colour = one meaning: blue question, grey note, amber attention, red error. */
    @Test fun edgeColours() {
        assertEquals(OnPathColors.InfoBlue, MessageKind.FRAGE.edge())
        assertEquals(OnPathColors.Grey, MessageKind.HINWEIS.edge())
        assertEquals(OnPathColors.Warn, MessageKind.HINWEIS_WARN.edge())
        assertEquals(OnPathColors.Error, MessageKind.HINWEIS_ERROR.edge())
    }
}
