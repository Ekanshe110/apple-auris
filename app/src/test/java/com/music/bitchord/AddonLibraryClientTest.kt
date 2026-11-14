package com.music.bitchord

import com.music.bitchord.data.sources.addon.AddonAuthenticationException
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonUnavailable
import com.music.bitchord.data.sources.addon.AddonUnsupportedException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Focused contract tests for the authenticated Apple Music addon library protocol. */
class AddonLibraryClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `manifest parses optional Apple Music library capability`() = runBlocking {
        server.enqueue(json(manifestJson))

        val manifest = AddonClient(server.url("/").toString(), libraryCredential = "secret-token").manifest().getOrThrow()

        val library = manifest.library!!
        assertTrue(library.isAppleMusic)
        assertEquals(
            listOf("playlists", "albums", "songs"),
            library.collections,
        )
        val authentication = library.authentication!!
        assertEquals("bearer", authentication.type)
        assertTrue(authentication.required)
    }

    @Test
    fun `library collection uses offset and limit pagination`() = runBlocking {
        server.enqueue(json(manifestJson))
        server.enqueue(
            json(
                """
                {"items":[{"id":"library-id","catalogId":"catalog-id","streamId":"stream-id",
                 "title":"Playlist"}],"offset":25,"limit":10,"total":41,"hasMore":true}
                """.trimIndent(),
            ),
        )

        val page = AddonClient(server.url("/").toString(), libraryCredential = "secret-token")
            .libraryPlaylists(offset = 25, limit = 10)
            .getOrThrow()

        assertEquals(25, page.offset)
        assertEquals(10, page.limit)
        assertEquals(41, page.total)
        assertEquals(true, page.hasMore)
        assertEquals("library-id", page.items.single().id)
        assertEquals("catalog-id", page.items.single().catalogId)
        assertEquals("stream-id", page.items.single().streamId)
        val request = server.takeRequest()
        val libraryRequest = server.takeRequest()
        assertEquals("/manifest.json", request.requestUrl!!.encodedPath)
        assertEquals("/library/playlists", libraryRequest.requestUrl!!.encodedPath)
        assertEquals("25", libraryRequest.requestUrl!!.queryParameter("offset"))
        assertEquals("10", libraryRequest.requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun `collection track id is URL encoded as one path segment`() = runBlocking {
        server.enqueue(json(manifestJson))
        server.enqueue(json("""{"items":[]}"""))

        AddonClient(server.url("/").toString(), libraryCredential = "secret-token")
            .libraryPlaylistTracks("playlist/with spaces?&")
            .getOrThrow()

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals(
            "/library/playlists/playlist%2Fwith%20spaces%3F%26/tracks",
            request.requestUrl!!.encodedPath,
        )
    }

    @Test
    fun `empty successful library body is an empty page`() = runBlocking {
        server.enqueue(json(manifestJson))
        server.enqueue(MockResponse().setResponseCode(200))

        val page = AddonClient(server.url("/").toString(), libraryCredential = "secret-token")
            .librarySongs()
            .getOrThrow()

        assertTrue(page.items.isEmpty())
        assertNull(page.total)
    }

    @Test
    fun `signed out authentication error preserves machine readable code`() = runBlocking {
        server.enqueue(json(manifestJson))
        server.enqueue(
            json("""{"error":{"code":"signed_out","message":"Apple Music sign-in required"}}""")
                .setResponseCode(401),
        )

        val failure = AddonClient(server.url("/").toString(), libraryCredential = "secret")
            .libraryAlbums()
            .exceptionOrNull()

        assertTrue(failure is AddonAuthenticationException)
        assertEquals("signed_out", (failure as AddonAuthenticationException).code)
        assertEquals("Apple Music sign-in required", failure.message)
    }

    @Test
    fun `credential is sent in header and never in query`() = runBlocking {
        server.enqueue(json(manifestJson))
        server.enqueue(json("""{"items":[]}"""))

        AddonClient(server.url("/").toString(), libraryCredential = "secret-token")
            .libraryPlaylists()
            .getOrThrow()

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertNull(request.requestUrl!!.queryParameter("secret-token"))
        assertFalse(request.requestUrl!!.queryParameterNames.any {
            it.equals("token", ignoreCase = true) || it.equals("authorization", ignoreCase = true)
        })
    }

    @Test
    fun `unknown authentication type never sends the credential raw`() = runBlocking {
        server.enqueue(
            json(
                """
                {"id":"apple-addon","name":"Apple Addon","resources":["search","stream"],
                 "library":{"provider":"apple-music","collections":["songs"],
                 "authentication":{"type":"bearer ","required":true}}}
                """.trimIndent(),
            ),
        )

        val failure = AddonClient(server.url("/").toString(), libraryCredential = "secret-token")
            .librarySongs()
            .exceptionOrNull()

        assertTrue(failure is AddonUnsupportedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unsupported library is distinct from addon server error`() = runBlocking {
        server.enqueue(json("""{"id":"catalog","name":"Catalog","resources":["search"]}"""))

        val unsupported = AddonClient(server.url("/").toString(), libraryCredential = "secret-token")
            .libraryPlaylists()
            .exceptionOrNull()

        assertTrue(unsupported is AddonUnsupportedException)
        assertEquals(1, server.requestCount)

        val otherClient = AddonClient(server.url("/").toString(), libraryCredential = "secret-token")
        server.enqueue(json(manifestJson))
        server.enqueue(MockResponse().setResponseCode(503))
        val unavailable = otherClient.libraryPlaylists().exceptionOrNull()
        assertTrue(unavailable is AddonUnavailable)
    }

    private fun json(text: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(text)

    private companion object {
        const val manifestJson = """
            {
              "id":"apple-addon",
              "name":"Apple Addon",
              "version":"1.0.0",
              "resources":["search","stream"],
              "library":{
                "provider":"apple-music",
                "collections":["playlists","albums","songs"],
                "authentication":{"type":"bearer","required":true}
              }
            }
        """
    }
}
