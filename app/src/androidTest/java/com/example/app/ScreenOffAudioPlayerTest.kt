package com.example.app

import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.data.ResolvedAudio
import com.example.app.playback.AudioSnapshot
import com.example.app.playback.ScreenOffAudioPlayer
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class ScreenOffAudioPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitUntil(check: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 12_000
        while (!check() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Audio state timed out", check())
    }
    private fun audioFile(seconds: Int): File {
        val samples = 8000 * seconds
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(samples * 2)
        return File.createTempFile("audio-handoff", ".wav", context.cacheDir).apply { writeBytes(bytes.array()) }
    }

    @Test fun pauseAndSeekDuringResolutionArePreservedOnPlaybackAndReturn() {
        val file = audioFile(12)
        val resolved = CompletableDeferred<ResolvedAudio>()
        val state = AtomicReference<AudioSnapshot>()
        lateinit var audio: ScreenOffAudioPlayer
        main {
            audio = ScreenOffAudioPlayer(context, resolve = { resolved.await() }, onState = state::set, onEnded = {})
            audio.start("aaaaaaaaaaa", "A", 2000, true, 1f)
            audio.pause(); audio.seekTo(5000)
            resolved.complete(ResolvedAudio(file.toURI().toString(), "audio/wav"))
        }
        try {
            waitUntil { state.get()?.let { !it.buffering && !it.playWhenReady && it.positionMs == 5000L } == true }
            main { audio.play() }
            waitUntil { state.get().positionMs > 5200 }
            var returned: AudioSnapshot? = null
            main { audio.pause(); returned = audio.release() }
            assertFalse(returned!!.playWhenReady)
            assertTrue(returned!!.positionMs >= 5200)
            assertEquals("aaaaaaaaaaa", returned!!.videoId)
        } finally { main { audio.release() }; file.delete() }
    }

    @Test fun returningWhileResolvingCancelsLatePlayback() {
        val resolved = CompletableDeferred<ResolvedAudio>()
        val states = CopyOnWriteArrayList<AudioSnapshot>()
        lateinit var audio: ScreenOffAudioPlayer
        main {
            audio = ScreenOffAudioPlayer(context, resolve = { resolved.await() }, onState = { states.add(it) }, onEnded = {})
            audio.start("aaaaaaaaaaa", "A", 4321, true, 1f)
            val returned = audio.release()!!
            assertEquals(4321, returned.positionMs)
            assertTrue(returned.playWhenReady)
        }
        val count = states.size
        resolved.complete(ResolvedAudio("file:///does-not-exist.wav", "audio/wav"))
        Thread.sleep(300)
        assertEquals(count, states.size)
    }

    @Test fun audioCompletionAdvancesQueueExactlyOncePerItem() {
        val file = audioFile(1)
        val completed = CopyOnWriteArrayList<String>()
        lateinit var audio: ScreenOffAudioPlayer
        main {
            audio = ScreenOffAudioPlayer(context, resolve = { ResolvedAudio(file.toURI().toString(), "audio/wav") },
                onState = {}, onEnded = { state ->
                    completed.add(state.videoId)
                    if (state.videoId == "aaaaaaaaaaa") audio.start("bbbbbbbbbbb", "B", 0, true, 1f)
                })
            audio.start("aaaaaaaaaaa", "A", 0, true, 1f)
        }
        try {
            waitUntil { completed.size >= 2 }
            Thread.sleep(1200)
            assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), completed.toList())
        } finally { main { audio.release() }; file.delete() }
    }
}
