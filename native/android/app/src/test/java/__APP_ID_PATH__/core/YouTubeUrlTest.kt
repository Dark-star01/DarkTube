package __APP_ID__.core

import __APP_ID__.core.extraction.YouTubeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun watchUrl() = assertEquals(id, YouTubeUrl.parseVideoId("https://www.youtube.com/watch?v=$id"))
    @Test fun watchWithExtraParams() = assertEquals(id, YouTubeUrl.parseVideoId("https://m.youtube.com/watch?feature=share&v=$id&t=42s"))
    @Test fun shortLink() = assertEquals(id, YouTubeUrl.parseVideoId("https://youtu.be/$id?si=abc"))
    @Test fun shorts() = assertEquals(id, YouTubeUrl.parseVideoId("https://www.youtube.com/shorts/$id"))
    @Test fun embed() = assertEquals(id, YouTubeUrl.parseVideoId("https://www.youtube-nocookie.com/embed/$id"))
    @Test fun live() = assertEquals(id, YouTubeUrl.parseVideoId("https://youtube.com/live/$id"))
    @Test fun music() = assertEquals(id, YouTubeUrl.parseVideoId("https://music.youtube.com/watch?v=$id"))
    @Test fun bareId() = assertEquals(id, YouTubeUrl.parseVideoId("  $id "))
    @Test fun sharedTextWithUrlInside() =
        assertEquals(id, YouTubeUrl.parseVideoId("Check this out https://youtu.be/$id it's great"))
    @Test fun idWithDashAndUnderscore() = assertEquals("a-b_c-d_e-f", YouTubeUrl.parseVideoId("a-b_c-d_e-f"))

    @Test fun rejectsEmpty() = assertNull(YouTubeUrl.parseVideoId("  "))
    @Test fun rejectsWrongLength() = assertNull(YouTubeUrl.parseVideoId("short"))
    @Test fun rejectsOtherHosts() = assertNull(YouTubeUrl.parseVideoId("https://example.com/watch?v=$id"))
    @Test fun rejectsLookalikeHost() = assertNull(YouTubeUrl.parseVideoId("https://notyoutube.com/watch?v=$id"))
    @Test fun rejectsPlaylistOnly() = assertNull(YouTubeUrl.parseVideoId("https://www.youtube.com/playlist?list=PL1234567890A"))
    @Test fun rejectsMalformedId() = assertNull(YouTubeUrl.parseVideoId("https://www.youtube.com/watch?v=tooShort"))
}
