package com.mojing.app.media

import com.mojing.app.media.newmedia.SpeechPlaybackControl
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackControlTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun replacingPausedLeaseWakesOldAwaiterAndIgnoresOldCallbacks() = runTest {
        val control = SpeechPlaybackControl()
        var closed = 0
        val first = control.bind({}, {}, { closed++ })!!
        control.pause()
        val waiting = async { control.awaitResume(first) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        val next = control.bind({}, {})!!
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertFalse(waiting.await())
        assertEquals(1, closed)
        assertTrue(control.owns(next))
        assertFalse(control.setCallbacks(first, {}, {}))
        control.close()
    }
    @Test fun closeInvalidatesOwnerAndLateStateUpdates() = runTest {
        val control = SpeechPlaybackControl()
        val lease = control.bind({}, {})!!
        control.updateIfOwned(lease) { it.copy(segmentIndex = 1, segmentCount = 2) }
        assertEquals(1, control.snapshot.value.segmentIndex)
        control.close()
        assertFalse(control.bind({}, {}).let { it != null })
        control.updateIfOwned(lease) { it.copy(segmentIndex = 2) }
        assertEquals(SpeechPlaybackControl.Snapshot(), control.snapshot.value)
    }

    @Test fun pauseAndResumeExposeStateWithoutOwningJobs() {
        val control = SpeechPlaybackControl()
        val lease = control.bind({}, {})!!
        control.updateIfOwned(lease) { it.copy(phase = SpeechPlaybackControl.Phase.PLAYING, segmentIndex = 1, segmentCount = 2) }
        control.pause()
        assertEquals(SpeechPlaybackControl.Phase.PAUSED, control.snapshot.value.phase)
        control.resume()
        assertEquals(SpeechPlaybackControl.Phase.PREPARING, control.snapshot.value.phase)
    }

    @Test fun staleLeaseCannotUnbindReplacementOrUpdateItsSnapshot() {
        val control = SpeechPlaybackControl()
        val first = control.bind({}, {})!!
        val second = control.bind({}, {})!!
        control.updateIfOwned(second) { it.copy(phase = SpeechPlaybackControl.Phase.PLAYING) }
        control.unbind(first)
        assertEquals(SpeechPlaybackControl.Phase.PLAYING, control.snapshot.value.phase)
        control.updateIfOwned(first) { it.copy(phase = SpeechPlaybackControl.Phase.IDLE) }
        assertEquals(SpeechPlaybackControl.Phase.PLAYING, control.snapshot.value.phase)
    }
}
