package dev.ninecontroller.android.input

import dev.ninecontroller.protocol.InputState

/**
 * Keeps fast taps from being lost on a link that sends one report at a time (Bluetooth HID). Presses
 * that happened since the last report are OR-ed into the next one; the following report then carries
 * the real (released) state. Not thread-safe: callers hold one lock around [down], [take] and [restore].
 */
class TapLatch {
    private var buttons = 0
    private var lt = 0
    private var rt = 0

    /** Record what went down between [before] and [now]. */
    fun down(before: InputState, now: InputState) {
        buttons = buttons or (now.buttons and before.buttons.inv())
        if (now.lt > before.lt) lt = maxOf(lt, now.lt)
        if (now.rt > before.rt) rt = maxOf(rt, now.rt)
    }

    /** The report to send now: [latest] plus any press not yet sent. Clears the latch. */
    fun take(latest: InputState): InputState {
        val r = latest.copy(buttons = latest.buttons or buttons, lt = maxOf(latest.lt, lt), rt = maxOf(latest.rt, rt))
        buttons = 0; lt = 0; rt = 0
        return r
    }

    /** The report in [sent] did not go out: keep its presses for the next one. */
    fun restore(sent: InputState, latest: InputState) {
        buttons = buttons or (sent.buttons and latest.buttons.inv())
        if (sent.lt > latest.lt) lt = maxOf(lt, sent.lt)
        if (sent.rt > latest.rt) rt = maxOf(rt, sent.rt)
    }
}
