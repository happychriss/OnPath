package com.onpath.app.ui

import com.onpath.strings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * A plan point's numbered circle inside a text (owner 2026-11-03: "anstelle des Wortes Stopp diese Kreise mit der Zahl"):
 * "Über {3}" shows "Über ③" with the same sign as on the map – near-black stop, dark green start, dark red goal with a
 * flag. Only in buttons and the turn card (big enough to read); voice, notifications and small grey lines keep the word.
 * TalkBack reads "Stopp 3" / "Start" / "Ziel" in its place ([StopTokens.spoken]).
 */
object StopTokens {
    enum class Kind { STOP, START, GOAL }

    /** One circle in a text: [kind], its [number] (the map's number; the goal shows a flag). */
    data class Token(val kind: Kind, val number: Int)

    /** `{3}` = stop 3, `{s1}` = the start, `{g5}` = the destination (numbered 5). */
    private val TOKEN = Regex("""\{([sg]?)(\d+)\}""")

    fun stop(n: Int) = "{$n}"
    fun start(n: Int = 1) = "{s$n}"
    fun goal(n: Int) = "{g$n}"

    /** The text cut into plain parts and circles, in order (pure – unit-tested). */
    fun parts(text: String): List<Any> {
        val out = mutableListOf<Any>()
        var at = 0
        for (m in TOKEN.findAll(text)) {
            if (m.range.first > at) out += text.substring(at, m.range.first)
            val kind = when (m.groupValues[1]) { "s" -> Kind.START; "g" -> Kind.GOAL; else -> Kind.STOP }
            out += Token(kind, m.groupValues[2].toInt())
            at = m.range.last + 1
        }
        if (at < text.length) out += text.substring(at)
        return out
    }

    /** What TalkBack hears for a circle: "Stopp 3" / "Start" / "Ziel". */
    fun spoken(t: Token): String = when (t.kind) {
        Kind.STOP -> Txt.s(Res.string.nav_stop_n, t.number)
        Kind.START -> Txt.s(Res.string.nav_stop_start)
        Kind.GOAL -> Txt.s(Res.string.nav_card_goal)
    }

    /** The plain text with each circle spoken: "Über Stopp 3" – for semantics and logs. */
    fun plain(text: String): String = parts(text).joinToString("") { if (it is Token) spoken(it) else it as String }

    fun has(text: String): Boolean = TOKEN.containsMatchIn(text)
}

/**
 * [text] with its `{n}` tokens drawn as the map's plan point circles ([StopTokens]); plain text otherwise. The circle
 * is a little taller than the letters (1.25 em), so it reads at the button / card size.
 */
@Composable
fun StopText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, fontWeight: FontWeight? = null,
             textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
    if (!StopTokens.has(text)) {
        Text(text, modifier, color = color, style = style, fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines,
            overflow = overflow)
        return
    }
    val inline = mutableMapOf<String, InlineTextContent>()
    val annotated: AnnotatedString = buildAnnotatedString {
        StopTokens.parts(text).forEachIndexed { i, p ->
            if (p is StopTokens.Token) {
                val id = "stop$i"
                appendInlineContent(id, StopTokens.spoken(p))
                inline[id] = InlineTextContent(Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) { StopCircle(p) }
            } else append(p as String)
        }
    }
    Text(annotated, modifier, color = color, style = style, fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines,
        overflow = overflow, inlineContent = inline)
}

/** The circle itself: white ring (reads on green and on white), the point's colour, its number or a flag. */
@Composable
private fun StopCircle(t: StopTokens.Token) {
    val fill = when (t.kind) {
        StopTokens.Kind.STOP -> OnPathColors.PointStop
        StopTokens.Kind.START -> OnPathColors.PointStart
        StopTokens.Kind.GOAL -> OnPathColors.PointGoal
    }
    Box(Modifier.fillMaxSize().background(OnPathColors.CardWhite, CircleShape).padding(1.5.dp).background(fill, CircleShape),
        contentAlignment = Alignment.Center) {
        if (t.kind == StopTokens.Kind.GOAL) Icon(Icons.Filled.Flag, null, tint = OnPathColors.CardWhite, modifier = Modifier.fillMaxSize().padding(3.dp))
        else Text(t.number.toString(), color = OnPathColors.CardWhite, fontWeight = FontWeight.Bold,
            style = TextStyle(fontSize = 0.75.em, textAlign = TextAlign.Center))
    }
}
