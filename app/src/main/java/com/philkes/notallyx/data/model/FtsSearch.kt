package com.philkes.notallyx.data.model

import androidx.room.Embedded

/** One FTS match: the note plus the raw `offsets()` string used for ranking. */
data class NoteSearchHit(@Embedded val note: BaseNote, val offsets: String?)

/**
 * Merges FTS matches with label-only matches, applies folder/label filters, and ranks by pinned >
 * relevance score > modifiedTimestamp.
 *
 * Label semantics follow the legacy search: null = unlabeled notes only, empty string = all notes,
 * otherwise notes carrying that label.
 */
fun rankFtsSearchResults(
    hits: List<NoteSearchHit>,
    labelMatches: List<BaseNote>,
    folder: Folder,
    label: String?,
): List<BaseNote> {
    val results = LinkedHashMap<Long, BaseNote>()
    hits.map { it.note }.filter { it.folder == folder }.forEach { results[it.id] = it }
    labelMatches
        .filter { it.folder == folder }
        .forEach { if (it.id !in results) results[it.id] = it }
    // m7: pre-build the score lookup once instead of an O(n²) firstOrNull scan in the comparator
    val scoreById = hits.associate { it.note.id to scoreFtsOffsets(it.offsets) }
    return results.values
        .filter { baseNote ->
            when (label) {
                null -> baseNote.labels.isEmpty()
                "" -> true
                else -> baseNote.labels.contains(label)
            }
        }
        .sortedWith(
            compareByDescending<BaseNote> { it.pinned }
                .thenByDescending { baseNote -> scoreById[baseNote.id] ?: 0 }
                .thenByDescending { it.modifiedTimestamp }
        )
}

/**
 * Pure helpers for the FTS keyword search: query sanitization and offset-based ranking. Kept free
 * of Android dependencies so the ranking logic is unit-testable without Robolectric.
 */

/** Relative weight of a hit in the note title vs. body vs. list items when ranking results. */
const val FTS_WEIGHT_TITLE = 8
const val FTS_WEIGHT_ITEMS = 2
const val FTS_WEIGHT_BODY = 1

/**
 * Converts raw user input into a safe FTS4 MATCH expression: every whitespace-separated token is
 * stripped of FTS syntax characters (quotes, NEAR/OR operators, column filters, etc.) and turned
 * into a prefix query (`tok*`), so user input can never inject MATCH grammar and partial words
 * still match.
 *
 * Returns null when no usable token remains (blank input) — callers fall back to the legacy LIKE
 * search, which for a blank keyword simply lists all notes in the folder.
 */
fun String.toFtsMatchQuery(): String? {
    val tokens =
        trim().split(Regex("\\s+")).mapNotNull { token ->
            token.filter { it.isLetterOrDigit() || it == '_' }.takeIf { it.isNotEmpty() }
        }
    if (tokens.isEmpty()) {
        return null
    }
    return tokens.joinToString(" ") { token -> "$token*" }
}

/**
 * Computes a relevance score from an FTS4 `offsets()` string (space-separated ints, groups of 4:
 * column, termOffset, byteOffset, length).
 */
fun scoreFtsOffsets(offsets: String?): Int {
    if (offsets.isNullOrBlank()) {
        return 0
    }
    val hitsPerColumn = IntArray(3)
    val tokens = offsets.split(' ').filter { it.isNotBlank() }
    var i = 0
    while (i + 3 < tokens.size) {
        val column = tokens[i].toIntOrNull()
        if (column != null && column in 0..2) {
            hitsPerColumn[column]++
        }
        i += 4
    }
    return hitsPerColumn[NoteFts.COLUMN_TITLE] * FTS_WEIGHT_TITLE +
        hitsPerColumn[NoteFts.COLUMN_ITEMS] * FTS_WEIGHT_ITEMS +
        hitsPerColumn[NoteFts.COLUMN_BODY] * FTS_WEIGHT_BODY
}
