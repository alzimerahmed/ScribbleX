package com.philkes.notallyx.utils.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WebDAV PROPFIND multistatus parsing and Last-Modified parsing tests. Robolectric is required
 * because XmlPullParserFactory is an Android framework class (stubbed in the unit-test
 * android.jar). No network access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavParsingTest {

    private val multistatus =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/remote.php/dav/files/admin/NotallyX-Sync/</d:href>
            <d:propstat><d:prop><d:getlastmodified>Tue, 01 Apr 2025 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/admin/NotallyX-Sync/note-1.json</d:href>
            <d:propstat><d:prop><d:getlastmodified>Wed, 02 Apr 2025 11:30:00 GMT</d:getlastmodified></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/admin/NotallyX-Sync/note-2.json</d:href>
            <d:propstat><d:prop></d:prop></d:propstat>
          </d:response>
        </d:multistatus>
        """

    @Test
    fun `parses hrefs and last-modified dates`() {
        val resources = PropfindParser.parse(multistatus.byteInputStream())
        assertThat(resources).hasSize(3)
        assertThat(resources[0].href).isEqualTo("/remote.php/dav/files/admin/NotallyX-Sync/")
        assertThat(resources[1].href)
            .isEqualTo("/remote.php/dav/files/admin/NotallyX-Sync/note-1.json")
        assertThat(resources[1].lastModified).isEqualTo(1743593400000L)
    }

    @Test
    fun `missing last-modified parses to zero`() {
        val resources = PropfindParser.parse(multistatus.byteInputStream())
        assertThat(resources[2].lastModified).isEqualTo(0L)
    }

    @Test
    fun `parses RFC 1123 date`() {
        val parsed = PropfindParser.parseLastModified("Tue, 01 Apr 2025 10:00:00 GMT")
        assertThat(parsed).isGreaterThan(0L)
    }

    @Test
    fun `missing or garbage date parses to zero`() {
        assertThat(PropfindParser.parseLastModified(null)).isEqualTo(0L)
        assertThat(PropfindParser.parseLastModified("")).isEqualTo(0L)
        assertThat(PropfindParser.parseLastModified("not a date")).isEqualTo(0L)
    }

    @Test
    fun `empty collection parses to empty list`() {
        val xml =
            """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/sync/</d:href></d:response>
            </d:multistatus>
            """
        assertThat(PropfindParser.parse(xml.byteInputStream())).hasSize(1)
    }
}
