package qhybrid.android.tracker

/**
 * WP-TRACKER — narrow, injectable seam for the **loud phone ring** (the "find my phone" effect a
 * TRACKER-role long gesture / a RING_PHONE button triggers). The production impl
 * ([SystemPhoneRinger]) plays a looping ringtone on the alarm stream at max volume + a waveform
 * vibration with alarm/DND-bypass audio attributes; tests inject a fake so [ServiceTrackerDispatch]'s
 * ring path is verified without making noise.
 *
 * The actual audio/vibration is on-device-verified only; the pure volume/timeout decision is in
 * [RingPolicy] (unit-tested). Adds NO new wire bytes — this is a phone-side effect.
 */
interface PhoneRinger {
    /** Start ringing (loud, looping) until [stop], [toggle], or the auto-stop timeout. Idempotent. */
    fun start()

    /** Stop ringing + restore the user's prior alarm volume. Idempotent / safe if not ringing. */
    fun stop()

    /** True while the ring is active (between [start] and [stop]/auto-stop). */
    fun isRinging(): Boolean

    /**
     * Toggle the ring: [stop] it if it's currently ringing, else [start] it. This is what a repeated
     * trigger (a second LONG gesture / RING_PHONE press) calls so the SAME gesture both rings and
     * silences the phone. Returns true if it is ringing AFTER the toggle (i.e. it just started).
     */
    fun toggle(): Boolean {
        return if (isRinging()) {
            stop()
            false
        } else {
            start()
            true
        }
    }
}

/** A no-op [PhoneRinger] — the safe default for tests / when no ringer is wired. */
object NoopPhoneRinger : PhoneRinger {
    override fun start() {}
    override fun stop() {}
    override fun isRinging(): Boolean = false
}

/**
 * Pure policy for the ring: how loud (target alarm volume) and how long (auto-stop) the ring runs,
 * plus the vibration waveform. Split out from [SystemPhoneRinger] so the decision is unit-testable
 * off-device (the actual MediaPlayer/Vibrator calls cannot be).
 */
object RingPolicy {
    /** Auto-stop the ring after this long so a pocketed phone can't ring forever. */
    const val AUTO_STOP_MS = 30_000L

    /** Vibration waveform (ms): wait, buzz, gap, buzz… repeated. */
    val VIBRATION_PATTERN = longArrayOf(0, 1000, 1000)

    /** Repeat index into [VIBRATION_PATTERN] (0 = loop the whole pattern). */
    const val VIBRATION_REPEAT = 0

    /**
     * The alarm-stream volume to ring at: the loudest available ([maxVolume]). Returned as a pure
     * function so the "ring at max" rule is pinned by a test rather than buried in the impl.
     */
    fun ringVolume(maxVolume: Int): Int = maxVolume.coerceAtLeast(0)
}
