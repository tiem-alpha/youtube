package com.example.app

import com.example.app.data.NewPipePublicSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class YouTubeAudioLiveTest {
    @Test fun resolvesStandaloneAudioAndReadsMediaBytes() = runBlocking {
        assumeTrue(System.getenv("NEWPIPE_LIVE_TESTS") == "true")
        val audio = withTimeout(60_000) { NewPipePublicSource().audio("jNQXAC9IVRw") }
        assertTrue(audio.mimeType?.startsWith("audio/") == true)
        val connection = URL(audio.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Range", "bytes=0-1023")
            assertTrue("Audio HTTP ${connection.responseCode}", connection.responseCode in 200..299)
            assertTrue(connection.inputStream.use { it.read(ByteArray(1024)) } > 0)
        } finally { connection.disconnect() }
    }
}
