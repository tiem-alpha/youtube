package com.example.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.playback.PlaybackManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    @Test fun realMediaServicePlaysSeeksAndStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "playback-test.wav")
        // Six seconds of silence is valid PCM media; no external network or copyrighted fixture.
        val samples = 8000 * 6
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()); bytes.putInt(36 + samples * 2); bytes.put("WAVEfmt ".toByteArray())
        bytes.putInt(16); bytes.putShort(1); bytes.putShort(1); bytes.putInt(8000); bytes.putInt(16000); bytes.putShort(2); bytes.putShort(16)
        bytes.put("data".toByteArray()); bytes.putInt(samples * 2)
        file.writeBytes(bytes.array())
        lateinit var manager: PlaybackManager
        instrumentation.runOnMainSync { manager = PlaybackManager(context); manager.playAuthorizedUri(Uri.fromFile(file)) }
        fun waitUntil(predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 12000
            while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Playback condition timed out: ${manager.state.value}", predicate())
        }
        try {
            waitUntil { manager.state.value.isPlaying && manager.state.value.positionMs > 500 }
            assertEquals(6000, manager.state.value.durationMs)
            instrumentation.runOnMainSync { manager.seekTo(3000) }
            waitUntil { manager.state.value.positionMs >= 3000 }
            instrumentation.runOnMainSync { manager.startSleepTimer(15) }
            waitUntil { manager.sleepTimerSeconds.value > 0 }
            instrumentation.runOnMainSync { manager.cancelSleepTimer(); manager.pause() }
            waitUntil { !manager.state.value.isPlaying && manager.sleepTimerSeconds.value == 0 }
        } finally {
            instrumentation.runOnMainSync { manager.stop(); manager.release() }
            file.delete()
        }
    }
}
