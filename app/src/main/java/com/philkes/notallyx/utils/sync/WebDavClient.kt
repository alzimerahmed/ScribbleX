package com.philkes.notallyx.utils.sync

import android.util.Base64
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** One entry of a WebDAV PROPFIND (Depth: 1) response. */
data class WebDavResource(val href: String, val lastModified: Long)

/**
 * Minimal WebDAV client over [HttpURLConnection] for the self-hosted sync MVP (Phase 7 F1).
 *
 * Deliberately dependency-free (no Sardine; OkHttp is not a project dependency and adding it would
 * violate the no-unapproved-deps rule). Supports exactly the operations the sync engine needs: PUT,
 * GET, DELETE, MKCOL and PROPFIND (Depth: 1, list + last-modified).
 *
 * Never logs credentials or Authorization headers.
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    init {
        // M3: refuse plaintext HTTP endpoints except explicit local/LAN allowances
        if (!isSecureBaseUrl(baseUrl)) {
            throw WebDavException(
                "Insecure WebDAV URL: HTTPS required (http only for localhost/*.local)"
            )
        }
    }

    private val authHeader: String
        get() =
            "Basic " +
                Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )

    /** Uploads [body]. Throws [WebDavException] on non-2xx (M2). */
    fun put(path: String, body: ByteArray): Int =
        open("PUT", path).use { connection ->
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (isSuccess(code)) {
                code
            } else {
                throw WebDavException("PUT $path failed", code)
            }
        }

    /** Downloads the resource, or null on 404. Throws [WebDavException] on other errors. */
    fun get(path: String): ByteArray? =
        open("GET", path).use { connection ->
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { it.readBytes() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw WebDavException("GET $path failed", connection.responseCode)
            }
        }

    /**
     * Deletes the resource. 404 counts as success (idempotent). Throws [WebDavException] on other
     * non-2xx codes (M2) — 401/403 must never look like success.
     */
    fun delete(path: String): Int =
        open("DELETE", path).use {
            val code = it.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND || isSuccess(code)) {
                code
            } else {
                throw WebDavException("DELETE $path failed", code)
            }
        }

    /** Creates a collection. 405 (already exists) counts as success. Throws otherwise (M2). */
    fun mkcol(path: String): Int =
        open("MKCOL", path).use {
            val code = it.responseCode
            if (code == HTTP_ALREADY_EXISTS || isSuccess(code)) {
                code
            } else {
                throw WebDavException("MKCOL $path failed", code)
            }
        }

    /** Lists the children of [path] (Depth: 1). */
    fun propfind(path: String): List<WebDavResource> =
        open("PROPFIND", path).use { connection ->
            when (connection.responseCode) {
                WEBDAV_MULTI_STATUS -> connection.inputStream.use { PropfindParser.parse(it) }
                HttpURLConnection.HTTP_NOT_FOUND -> emptyList()
                else -> throw WebDavException("PROPFIND $path failed", connection.responseCode)
            }
        }

    private fun open(method: String, path: String): HttpURLConnection {
        val trimmedBase = baseUrl.trimEnd('/')
        val trimmedPath = if (path.startsWith("/")) path else "/$path"
        val connection =
            URI("$trimmedBase$trimmedPath").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Authorization", authHeader)
        connection.setRequestProperty("User-Agent", "ScribbleX-Sync/1")
        if (method == "PROPFIND") {
            connection.setRequestProperty("Content-Type", "application/xml")
            connection.setRequestProperty("Depth", "1")
            connection.doOutput = true
            connection.outputStream.use { it.write(PROPFIND_BODY.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            try {
                errorStream?.close()
            } catch (_: Exception) {}
            disconnect()
        }
    }

    companion object {
        const val WEBDAV_MULTI_STATUS = 207

        // 405 Method Not Allowed: MKCOL on an already-existing collection
        private const val HTTP_ALREADY_EXISTS = 405

        fun isSuccess(code: Int): Boolean = code in 200..299

        /**
         * M3: sync servers must use HTTPS. Plain HTTP is accepted only for explicit local/LAN
         * allowances (localhost, 127.0.0.1, ::1 and `*.local` mDNS names).
         */
        fun isSecureBaseUrl(url: String): Boolean {
            val uri =
                try {
                    URI(url.trim())
                } catch (_: Exception) {
                    return false
                }
            return when (uri.scheme?.lowercase(Locale.US)) {
                "https" -> true
                "http" -> {
                    val host = uri.host?.lowercase(Locale.US) ?: return false
                    host == "localhost" ||
                        host == "127.0.0.1" ||
                        host == "::1" ||
                        host.endsWith(".local")
                }
                else -> false
            }
        }

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/></d:prop></d:propfind>"""
    }
}

class WebDavException(message: String, val statusCode: Int = -1) : Exception(message)

/** Pure PROPFIND multistatus XML parsing — unit-testable without network access. */
object PropfindParser {

    fun parse(input: InputStream): List<WebDavResource> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        return parse(parser)
    }

    fun parse(parser: XmlPullParser): List<WebDavResource> {
        val resources = ArrayList<WebDavResource>()
        var currentHref: String? = null
        var lastModified: String? = null
        var inResponse = false
        var inProp = false

        // The parser may not be namespace-aware (raw names include the prefix, e.g. "d:response"),
        // so match on the local name after the colon.
        fun localName() = parser.name.substringAfterLast(':').lowercase()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (localName()) {
                        "response" -> {
                            inResponse = true
                            currentHref = null
                            lastModified = null
                        }

                        "prop" -> inProp = true

                        "href" -> if (inResponse && !inProp) currentHref = parser.nextText()

                        "getlastmodified" -> if (inProp) lastModified = parser.nextText()
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (localName()) {
                        "prop" -> inProp = false

                        "response" -> {
                            inResponse = false
                            val href = currentHref
                            if (href != null) {
                                resources.add(WebDavResource(href, parseLastModified(lastModified)))
                            }
                            currentHref = null
                            lastModified = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return resources
    }

    /** Parses an RFC 1123 `Last-Modified` date; returns 0 when missing/unparseable. */
    fun parseLastModified(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val formats =
            arrayOf(
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
                SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
                SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US),
            )
        for (format in formats) {
            format.isLenient = true
            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
            try {
                return format.parse(value.trim())?.time ?: 0L
            } catch (_: ParseException) {
                // try next format
            }
        }
        return 0L
    }
}
