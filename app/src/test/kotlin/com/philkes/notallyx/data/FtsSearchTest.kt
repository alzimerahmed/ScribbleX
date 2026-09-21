package com.philkes.notallyx.data

import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.NoteSearchHit
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.data.model.rankFtsSearchResults
import com.philkes.notallyx.data.model.scoreFtsOffsets
import com.philkes.notallyx.data.model.toFtsMatchQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Pure unit tests for FTS query sanitization and ranking (no Robolectric needed). */
class FtsSearchTest {

    @Test
    fun toFtsMatchQuery_quotesTokensAndAddsPrefix() {
        assertThat("hello world".toFtsMatchQuery()).isEqualTo("hello* world*")
    }

    @Test
    fun toFtsMatchQuery_stripsQuotesAndFtsSyntax() {
        // Quotes and FTS operators must never reach MATCH raw
        assertThat("\"foo\" OR bar NEAR baz".toFtsMatchQuery())
            .isEqualTo("foo* OR* bar* NEAR* baz*")
        // Quoted tokens are inert: the whole expression is only prefix terms
        assertThat("foo* bar: ^baz (qux)".toFtsMatchQuery()).isEqualTo("foo* bar* baz* qux*")
    }

    @Test
    fun toFtsMatchQuery_blankInputReturnsNull() {
        assertThat("   ".toFtsMatchQuery()).isNull()
        assertThat("".toFtsMatchQuery()).isNull()
        assertThat("\"\"".toFtsMatchQuery()).isNull()
    }

    @Test
    fun scoreFtsOffsets_weightsTitleOverBody() {
        // one title hit (column 0) vs one body hit (column 1)
        val titleOnly = "0 0 0 3"
        val bodyOnly = "1 0 0 3"
        assertThat(scoreFtsOffsets(titleOnly)).isGreaterThan(scoreFtsOffsets(bodyOnly))
    }

    @Test
    fun scoreFtsOffsets_countsMultipleHits() {
        // two body hits
        assertThat(scoreFtsOffsets("1 0 0 3 1 1 5 3")).isEqualTo(2 * 1)
    }

    @Test
    fun scoreFtsOffsets_handlesNullAndGarbage() {
        assertThat(scoreFtsOffsets(null)).isEqualTo(0)
        assertThat(scoreFtsOffsets("")).isEqualTo(0)
        assertThat(scoreFtsOffsets("garbage")).isEqualTo(0)
    }

    private fun note(
        id: Long,
        title: String = "",
        body: String = "",
        folder: Folder = Folder.NOTES,
        pinned: Boolean = false,
        modifiedTimestamp: Long = 0,
        labels: List<String> = emptyList(),
    ) =
        BaseNote(
            id = id,
            type = Type.NOTE,
            folder = folder,
            color = "DEFAULT",
            title = title,
            pinned = pinned,
            timestamp = 0,
            modifiedTimestamp = modifiedTimestamp,
            labels = labels,
            body = body,
            spans = emptyList(),
            items = emptyList(),
            images = emptyList(),
            files = emptyList(),
            audios = emptyList(),
            reminders = emptyList(),
            viewMode = com.philkes.notallyx.data.model.NoteViewMode.EDIT,
            isPinnedToStatus = false,
        )

    @Test
    fun rankFtsSearchResults_titleMatchRanksAboveBodyMatch() {
        val bodyMatch = note(id = 1, body = "needle in body", modifiedTimestamp = 99)
        val titleMatch = note(id = 2, title = "needle", modifiedTimestamp = 1)
        val hits = listOf(NoteSearchHit(bodyMatch, "1 0 0 5"), NoteSearchHit(titleMatch, "0 0 0 6"))
        val ranked = rankFtsSearchResults(hits, emptyList(), Folder.NOTES, "")
        assertThat(ranked.map { it.id }).containsExactly(2L, 1L)
    }

    @Test
    fun rankFtsSearchResults_filtersByFolder() {
        val inFolder = note(id = 1, body = "needle")
        val deleted = note(id = 2, body = "needle", folder = Folder.DELETED)
        val hits = listOf(NoteSearchHit(inFolder, null), NoteSearchHit(deleted, null))
        val ranked = rankFtsSearchResults(hits, emptyList(), Folder.NOTES, "")
        assertThat(ranked.map { it.id }).containsExactly(1L)
    }

    @Test
    fun rankFtsSearchResults_labelSemanticsMatchLegacySearch() {
        val labeled = note(id = 1, body = "x", labels = listOf("Important"))
        val unlabeled = note(id = 2, body = "x")

        // label = null -> only unlabeled notes
        assertThat(
                rankFtsSearchResults(
                    listOf(NoteSearchHit(labeled, null), NoteSearchHit(unlabeled, null)),
                    emptyList(),
                    Folder.NOTES,
                    null,
                )
            )
            .extracting<Long> { it.id }
            .containsExactly(2L)

        // label = "" -> all notes
        assertThat(
                rankFtsSearchResults(
                    listOf(NoteSearchHit(labeled, null), NoteSearchHit(unlabeled, null)),
                    emptyList(),
                    Folder.NOTES,
                    "",
                )
            )
            .hasSize(2)

        // label = "Important" -> only notes carrying the label
        assertThat(
                rankFtsSearchResults(
                    listOf(NoteSearchHit(labeled, null), NoteSearchHit(unlabeled, null)),
                    emptyList(),
                    Folder.NOTES,
                    "Important",
                )
            )
            .extracting<Long> { it.id }
            .containsExactly(1L)
    }

    @Test
    fun rankFtsSearchResults_mergesLabelOnlyMatchesWithoutDuplicates() {
        val ftsHit = note(id = 1, body = "needle")
        val labelOnly = note(id = 2, labels = listOf("needle"))
        val ranked =
            rankFtsSearchResults(
                listOf(NoteSearchHit(ftsHit, null)),
                listOf(ftsNoteCopy(), labelOnly),
                Folder.NOTES,
                "",
            )
        assertThat(ranked).hasSize(2)
    }

    private fun ftsNoteCopy() = note(id = 1, body = "needle")
}
