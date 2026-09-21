package com.philkes.notallyx.data.imports.markdown

import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.SpanRepresentation
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.data.model.toMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip tests for the Markdown editing mode (Phase 7 F4): markdown → spans → markdown must be
 * identical for supported constructs.
 */
class MarkdownRoundTripTest {

    private fun note(body: String, spans: List<SpanRepresentation> = emptyList()): BaseNote {
        val now = System.currentTimeMillis()
        return BaseNote(
            id = 0L,
            type = Type.NOTE,
            folder = Folder.NOTES,
            color = BaseNote.COLOR_DEFAULT,
            title = "",
            pinned = false,
            timestamp = now,
            modifiedTimestamp = now,
            labels = emptyList(),
            body = body,
            spans = spans,
            items = emptyList(),
            images = emptyList(),
            files = emptyList(),
            audios = emptyList(),
            reminders = emptyList(),
            viewMode = NoteViewMode.EDIT,
            isPinnedToStatus = false,
        )
    }

    private fun roundTrip(markdown: String): String {
        val (body, spans) = parseMarkdownToBodyAndSpans(markdown)
        return note(body, spans).toMarkdown()
    }

    @Test
    fun roundTrip_plain_text() {
        val md = "Just some plain text"
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun roundTrip_bold_italic_code_strike_link() {
        val md =
            "Hello **world** and _italic_ text with `code`, ~~struck~~ and a " +
                "[link](https://example.com) here"
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun roundTrip_nested_bold_italic() {
        val md = "**_bold italic_** stays"
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun roundTrip_link_with_nested_bold() {
        val md = "See [**docs**](https://example.com/docs) now"
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun roundTrip_headers_lists_checklists_preserved_verbatim() {
        val md =
            "# Title\n## Subtitle\n\n- bullet one\n- bullet two\n1. first\n2. second\n\n" +
                "- [ ] todo\n- [x] done\n    - [ ] sub task\n\n> quote\n```\nfenced code\n```\n---"
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun roundTrip_multiline_with_inline_styles() {
        val md = "First **bold** line\nSecond _italic_ line\nThird `code` line"
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun roundTrip_empty_and_whitespace() {
        assertEquals("", roundTrip(""))
        assertEquals(" ", roundTrip(" "))
    }

    @Test
    fun roundTrip_special_characters_untouched() {
        val md = "Prices 5$ & <tags> ~not~struck~ 3 * 4 = 12"
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun parse_spans_are_correct() {
        val md = "**bold** plain _it_ `mono` ~~gone~~ [site](https://x.org)"
        val (body, spans) = parseMarkdownToBodyAndSpans(md)
        assertEquals("bold plain it mono gone site", body)
        assertEquals(
            "bold",
            body.substring(spans.first { it.bold }.start, spans.first { it.bold }.end),
        )
        assertEquals(
            "it",
            body.substring(spans.first { it.italic }.start, spans.first { it.italic }.end),
        )
        assertEquals(
            "mono",
            body.substring(spans.first { it.monospace }.start, spans.first { it.monospace }.end),
        )
        assertEquals(
            "gone",
            body.substring(
                spans.first { it.strikethrough }.start,
                spans.first { it.strikethrough }.end,
            ),
        )
        val link = spans.first { it.link }
        assertEquals("site", body.substring(link.start, link.end))
        assertEquals("https://x.org", link.linkData)
    }

    @Test
    fun parse_unclosed_markers_are_literal() {
        val md = "a ** b _ c ~~ d ` e"
        val (body, spans) = parseMarkdownToBodyAndSpans(md)
        assertEquals(md, body)
        assertEquals(0, spans.size)
        assertEquals(md, roundTrip(md))
    }

    @Test
    fun parse_non_canonical_markers_normalize() {
        // __bold__ and *italic* are accepted but normalize to canonical ** / _
        val (body, spans) = parseMarkdownToBodyAndSpans("__b__ and *i*")
        assertEquals("b and i", body)
        assertEquals(1, spans.count { it.bold })
        assertEquals(1, spans.count { it.italic })
        assertEquals("**b** and _i_", bodyAndSpansToMarkdown(body, spans))
    }

    @Test
    fun parse_escaped_marker_keeps_character() {
        val (body, spans) = parseMarkdownToBodyAndSpans("\\*not italic\\*")
        assertEquals("*not italic*", body)
        assertEquals(0, spans.size)
    }

    @Test
    fun serialize_is_inverse_of_parse_for_generated_spans() {
        val body = "zero one two three four"
        val spans =
            listOf(
                SpanRepresentation(0, 4, bold = true),
                SpanRepresentation(5, 8, italic = true),
                SpanRepresentation(9, 12, monospace = true),
                SpanRepresentation(13, 18, strikethrough = true),
                SpanRepresentation(19, 23, link = true, linkData = "https://ex.com"),
            )
        val md = bodyAndSpansToMarkdown(body, spans)
        val (parsedBody, parsedSpans) = parseMarkdownToBodyAndSpans(md)
        assertEquals(body, parsedBody)
        assertEquals(spans.sortedBy { it.start }, parsedSpans.sortedBy { it.start })
    }

    @Test
    fun list_note_round_trip() {
        val now = System.currentTimeMillis()
        val items =
            listOf(
                ListItem("Task 1", false, false, 0, mutableListOf()),
                ListItem("Task 2", true, false, 1, mutableListOf()),
                ListItem("Sub", false, true, 2, mutableListOf()),
            )
        val note =
            BaseNote(
                id = 0L,
                type = Type.LIST,
                folder = Folder.NOTES,
                color = BaseNote.COLOR_DEFAULT,
                title = "",
                pinned = false,
                timestamp = now,
                modifiedTimestamp = now,
                labels = emptyList(),
                body = "",
                spans = emptyList(),
                items = items,
                images = emptyList(),
                files = emptyList(),
                audios = emptyList(),
                reminders = emptyList(),
                viewMode = NoteViewMode.EDIT,
                isPinnedToStatus = false,
            )
        val md = note.toMarkdown().trimEnd()
        val parsed = parseMarkdownToListItems(md)
        assertEquals(items.map { it.body }, parsed.map { it.body })
        assertEquals(items.map { it.checked }, parsed.map { it.checked })
        assertEquals(items.map { it.isChild }, parsed.map { it.isChild })
    }

    @Test
    fun list_round_trip_with_markdown_source() {
        val md = "- [ ] buy **milk**\n- [x] done\n    - [ ] sub\n- plain item"
        val items = parseMarkdownToListItems(md)
        assertEquals(listOf("buy **milk**", "done", "sub", "plain item"), items.map { it.body })
        assertEquals(listOf(false, true, false, false), items.map { it.checked })
        assertEquals(listOf(false, false, true, false), items.map { it.isChild })
    }

    @Test
    fun full_edit_save_export_round_trip() {
        // Simulates: user enters markdown source -> save (parse) -> export (toMarkdown)
        val source =
            "# Heading kept verbatim\n\nIntro **bold**, _italic_, `code`, ~~strike~~, " +
                "[link](https://ex.io)\n\n- [ ] open\n- [x] closed\n"
        val (body, spans) = parseMarkdownToBodyAndSpans(source)
        val exported = note(body, spans).toMarkdown()
        assertEquals(source, exported)
    }
}
