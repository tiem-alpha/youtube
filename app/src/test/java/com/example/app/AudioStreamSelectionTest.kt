package com.example.app

import com.example.app.data.selectAudioStream
import com.example.app.data.PublicBrowseException
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod

class AudioStreamSelectionTest {
    private fun stream(id: String, bitrate: Int, type: AudioTrackType? = AudioTrackType.ORIGINAL,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP) = AudioStream.Builder()
        .setId(id).setContent("https://example.com/$id", true).setMediaFormat(MediaFormat.M4A)
        .setAverageBitrate(bitrate).setAudioTrackType(type).setDeliveryMethod(delivery).build()

    @Test fun prefersOriginalAudioAtModerateBitrate() {
        val chosen = selectAudioStream(listOf(stream("high", 256), stream("low", 48), stream("normal", 128),
            stream("unknown-track", 128, null)))
        assertEquals("https://example.com/normal", chosen.url)
        assertTrue(chosen.mimeType!!.startsWith("audio/"))
    }

    @Test fun doesNotTreatDashSegmentBaseAsAProgressiveAudioFile() {
        try { selectAudioStream(listOf(stream("dash", 128, delivery = DeliveryMethod.DASH))); fail("Expected unsupported audio") }
        catch (_: PublicBrowseException) { }
    }

    @Test fun missingAudioFailsInsteadOfFallingBackToVideo() {
        try { selectAudioStream(emptyList()); fail("Expected unsupported audio") }
        catch (_: PublicBrowseException) { }
    }
}
