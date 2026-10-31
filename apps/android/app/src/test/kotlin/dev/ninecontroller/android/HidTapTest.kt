package dev.ninecontroller.android

import dev.ninecontroller.android.input.TapLatch
import dev.ninecontroller.protocol.InputState
import org.junit.Assert.assertEquals
import org.junit.Test

class HidTapTest {
    private val a = 1; private val b = 2

    @Test fun tapReleasedBeforeTheRadioSendsIsStillReported() {
        val l = TapLatch()
        val pressed = InputState(buttons = a)
        l.down(InputState.NEUTRAL, pressed)
        l.down(pressed, InputState.NEUTRAL) // released before any report left
        assertEquals(a, l.take(InputState.NEUTRAL).buttons)      // the press goes out…
        assertEquals(0, l.take(InputState.NEUTRAL).buttons)      // …then the release
    }

    @Test fun heldButtonsAndOtherInputPassThrough() {
        val l = TapLatch()
        val now = InputState(buttons = b, lx = 1000)
        l.down(InputState.NEUTRAL, now)
        assertEquals(now, l.take(now))
    }

    @Test fun quickTriggerPullIsReported() {
        val l = TapLatch()
        l.down(InputState.NEUTRAL, InputState(rt = 65535))
        l.down(InputState(rt = 65535), InputState.NEUTRAL)
        assertEquals(65535, l.take(InputState.NEUTRAL).rt)
        assertEquals(0, l.take(InputState.NEUTRAL).rt)
    }

    @Test fun reportThatFailedToSendKeepsItsPress() {
        val l = TapLatch()
        l.down(InputState.NEUTRAL, InputState(buttons = a)); l.down(InputState(buttons = a), InputState.NEUTRAL)
        val r = l.take(InputState.NEUTRAL)
        l.restore(r, InputState.NEUTRAL)
        assertEquals(a, l.take(InputState.NEUTRAL).buttons)
    }
}
