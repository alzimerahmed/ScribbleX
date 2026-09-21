package com.philkes.notallyx.data.imports.markdown

import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.SpanRepresentation

/**
 * Lossless Markdown round-trip support for the Markdown editing mode (Phase 7 F4).
 *
 * Unlike [parseBodyAndSpansFromMarkdown] (which flattens block constructs into raw text for
 * import), this parser is the exact inverse of [bodyAndSpansToMarkdown] / `BaseNote.toMarkdown()`:
 * - Inline constructs are parsed into [SpanRepresentation]s and their markers stripped: `**bold**`,
 *   `_italic_`, `` `code` ``, `~~strikethrough~~`, `[text](url)`.
 * - Block constructs (headings `#`, bullet/ordered lists, checklists `- [ ]`/`- [x]`, block quotes,
 *   fenced code blocks, horizontal rules) have no representation in the app's span-based data
 *   model, so their syntax is kept verbatim in the note body. This makes the round-trip edit → save
 *   → export byte-identical for them.
 *
 * Round-trip guarantee (`bodyAndSpansToMarkdown(parseMarkdownToBodyAndSpans(md)) == md`) holds for
 * canonical marker syntax. Normalizations/limitations (documented, lossy):
 * - Non-canonical emphasis markers are normalized: `*italic*`/`__bold__` → `_italic_`/`**bold**`.
 * - Escaped markers (`\*`) lose their backslash (the character itself is preserved).
 * - Partially overlapping spans (not producible by parsing) may serialize with unexpected marker
 *   order; links are always emitted outermost.
 * - Link URLs containing `)` are not supported.
 */

/** Parses a Markdown string into a plain-text body and the corresponding [SpanRepresentation]s. */
fun parseMarkdownToBodyAndSpans(markdown: String): Pair<String, List<SpanRepresentation>> {
    val out = StringBuilder()
    val spans = mutableListOf<SpanRepresentation>()
    parseInlineRange(markdown, 0, markdown.length, out, spans)
    return Pair(out.toString(), spans)
}

/** Builds a Markdown string from a body and spans, emitting only canonical markers. */
fun bodyAndSpansToMarkdown(body: String, spans: List<SpanRepresentation>): String {
    if (spans.isEmpty() || body.isEmpty()) return body
    val before = Array(body.length + 1) { StringBuilder() }
    val after = Array(body.length + 1) { StringBuilder() }
    // Links first so their markers wrap any nested emphasis markers
    for (s in spans.filter { it.link }) {
        val start = s.start.coerceIn(0, body.length)
        val end = s.end.coerceIn(0, body.length)
        if (start >= end) continue
        before[start].append('[')
        after[end].insert(0, "](${s.linkData ?: ""})")
    }
    // Outer spans first so nested markers are emitted inside-out; bold outermost of equal ranges
    for (s in
        spans
            .filter { !it.link }
            .sortedWith(compareBy({ it.start }, { -it.end }, { if (it.bold) 0 else 1 }))) {
        val start = s.start.coerceIn(0, body.length)
        val end = s.end.coerceIn(0, body.length)
        if (start >= end) continue
        if (s.monospace) {
            before[start].append('`')
            after[end].insert(0, "`")
        }
        if (s.bold) {
            before[start].append("**")
            after[end].insert(0, "**")
        }
        if (s.italic) {
            before[start].append('_')
            after[end].insert(0, "_")
        }
        if (s.strikethrough) {
            before[start].append("~~")
            after[end].insert(0, "~~")
        }
    }
    return buildString {
        for (i in body.indices) {
            append(before[i])
            append(body[i])
            append(after[i + 1])
        }
    }
}

/**
 * Inverse of `BaseNote.toMarkdown()` for `Type.LIST` notes: parses GFM task list syntax (`- [ ]` /
 * `- [x]`, 4-space indent for children) into flat [ListItem]s. Lines that are not list items become
 * unchecked items with their raw text. Inline markdown markers are kept literally in item bodies
 * (list items do not carry spans).
 */
fun parseMarkdownToListItems(markdown: String): List<ListItem> {
    val items = mutableListOf<ListItem>()
    val pattern = Regex("^(\\s*)- \\[([ xX])\\] (.*)$")
    for (line in markdown.lines()) {
        if (line.isBlank()) continue
        val match = pattern.matchEntire(line.trimEnd())
        if (match != null) {
            val indent = match.groupValues[1].length
            val checked = match.groupValues[2].lowercase() == "x"
            items.add(
                ListItem(
                    body = match.groupValues[3],
                    checked = checked,
                    isChild = indent >= 2,
                    order = items.size,
                    children = mutableListOf(),
                )
            )
        } else {
            val text = line.trim().removePrefix("- ").removePrefix("* ")
            items.add(
                ListItem(
                    body = text,
                    checked = false,
                    isChild = line.startsWith("    "),
                    order = items.size,
                    children = mutableListOf(),
                )
            )
        }
    }
    return items
}

private fun findMarker(source: String, marker: String, from: Int, end: Int): Int {
    var idx = source.indexOf(marker, from)
    while (idx in from until end) {
        if (idx > 0 && source[idx - 1] == '\\') {
            idx = source.indexOf(marker, idx + marker.length)
            continue
        }
        return idx
    }
    return -1
}

/**
 * Parses `[start, end)` of `source` as inline markdown, appending the plain text to `out` and the
 * resulting (absolute) spans to `spans`.
 */
private fun parseInlineRange(
    source: String,
    start: Int,
    end: Int,
    out: StringBuilder,
    spans: MutableList<SpanRepresentation>,
) {
    var i = start
    while (i < end) {
        val c = source[i]
        if (c == '\\' && i + 1 < end) {
            out.append(source[i + 1])
            i += 2
            continue
        }
        if (c == '`') {
            // Fenced code block (```): keep verbatim, no spans
            if (i + 2 < end && source[i + 1] == '`' && source[i + 2] == '`') {
                val close = findMarker(source, "```", i + 3, end)
                if (close != -1) {
                    out.append(source, i, close + 3)
                    i = close + 3
                    continue
                }
            }
            val close = findMarker(source, "`", i + 1, end)
            if (close != -1 && close > i + 1) {
                val spanStart = out.length
                out.append(source, i + 1, close)
                if (out.length > spanStart) {
                    spans.add(SpanRepresentation(spanStart, out.length, monospace = true))
                }
                i = close + 1
                continue
            }
        }
        if (c == '~' && i + 1 < end && source[i + 1] == '~') {
            val close = findMarker(source, "~~", i + 2, end)
            if (close != -1) {
                val spanStart = out.length
                parseInlineRange(source, i + 2, close, out, spans)
                if (out.length > spanStart) {
                    spans.add(SpanRepresentation(spanStart, out.length, strikethrough = true))
                }
                i = close + 2
                continue
            }
        }
        if (c == '*' && i + 1 < end && source[i + 1] == '*') {
            val close = findMarker(source, "**", i + 2, end)
            if (close != -1) {
                val spanStart = out.length
                parseInlineRange(source, i + 2, close, out, spans)
                if (out.length > spanStart) {
                    spans.add(SpanRepresentation(spanStart, out.length, bold = true))
                }
                i = close + 2
                continue
            }
        }
        if (c == '_' && i + 1 < end && source[i + 1] == '_') {
            val close = findMarker(source, "__", i + 2, end)
            if (close != -1) {
                val spanStart = out.length
                parseInlineRange(source, i + 2, close, out, spans)
                if (out.length > spanStart) {
                    spans.add(SpanRepresentation(spanStart, out.length, bold = true))
                }
                i = close + 2
                continue
            }
        }
        if (c == '*') {
            val close = findMarker(source, "*", i + 1, end)
            // close == i+1 would be the second star of an unclosed '**' pair -> literal
            if (close != -1 && close > i + 1) {
                val spanStart = out.length
                parseInlineRange(source, i + 1, close, out, spans)
                if (out.length > spanStart) {
                    spans.add(SpanRepresentation(spanStart, out.length, italic = true))
                }
                i = close + 1
                continue
            }
        }
        if (c == '_' && (i == start || source[i - 1] == ' ' || source[i - 1] == '\n')) {
            val close = findMarker(source, "_", i + 1, end)
            if (close != -1 && close > i + 1) {
                val spanStart = out.length
                parseInlineRange(source, i + 1, close, out, spans)
                if (out.length > spanStart) {
                    spans.add(SpanRepresentation(spanStart, out.length, italic = true))
                }
                i = close + 1
                continue
            }
        }
        if (c == '[') {
            val closeBracket = findMarker(source, "]", i + 1, end)
            if (closeBracket != -1 && closeBracket + 1 < end && source[closeBracket + 1] == '(') {
                val closeParen = source.indexOf(')', closeBracket + 2)
                if (closeParen != -1 && closeParen < end) {
                    val spanStart = out.length
                    parseInlineRange(source, i + 1, closeBracket, out, spans)
                    if (out.length > spanStart) {
                        spans.add(
                            SpanRepresentation(
                                spanStart,
                                out.length,
                                link = true,
                                linkData = source.substring(closeBracket + 2, closeParen),
                            )
                        )
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
}
