package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps one Hue entertainment stream alive and restarts it in place when it dies.
 *
 * Bouncy Castle closes the DTLS socket after the first failed write (a transient ENOBUFS or
 * EPERM on TV Wi-Fi is enough), so every later frame would fail too. Throwing that out to
 * HyperionThread made it build a new client and only then disconnect the old one, and the old
 * client's "stop" switched off the stream the new client had just started: the lights went
 * back to their own state while the new client kept writing into a stopped stream. The bridge
 * also ends a stream on its own (ten seconds without packets, another app taking over), and
 * UDP gives no error for that.
 *
 * Here a failed write or a stream the bridge reports as inactive closes the link, stops the
 * stream and opens it again, with growing pauses while the bridge keeps refusing.
 */
internal class HueStreamSession(
    private val bridge: Bridge,
    private val scheduler: ScheduledExecutorService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    interface Link {
        @Throws(IOException::class)
        fun send(data: ByteArray)

        fun close()
    }

    interface Bridge {
        /** Starts the entertainment stream and completes the DTLS handshake. */
        @Throws(IOException::class)
        fun open(): Link

        /** Stops the entertainment stream; must not throw. */
        fun stop()

        /** Whether the bridge still streams this area; null when it cannot tell. */
        @Throws(IOException::class)
        fun isActive(): Boolean?
    }

    private var mLink: Link? = null
    private var mStopped = false
    private var mRestarting = false
    private var mFailures = 0
    private var mStartedMs = 0L

    /** Takes over a link opened by the caller and starts watching the stream. */
    fun start(link: Link) {
        synchronized(this) {
            mLink = link
            mStartedMs = clock()
        }
        try {
            scheduler.scheduleWithFixedDelay({ checkActive() }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Sends one message; false when it did not go out (the stream is being restarted). */
    fun send(data: ByteArray): Boolean {
        val link = synchronized(this) { if (mStopped || mRestarting) null else mLink } ?: return false
        return try {
            link.send(data)
            true
        } catch (e: IOException) {
            fail(link, "send failed: ${e.message}")
            false
        }
    }

    /** Sends [last] if it can and closes the link for good; the caller stops the stream. */
    fun shutdown(last: ByteArray?) {
        val link = synchronized(this) {
            mStopped = true
            mLink.also { mLink = null }
        } ?: return
        if (last != null) {
            try {
                link.send(last)
            } catch (e: IOException) {
                Log.w(TAG, "Final Hue message failed: ${e.message}")
            }
        }
        link.close()
    }

    private fun checkActive() {
        val link = synchronized(this) { if (mStopped || mRestarting) null else mLink } ?: return
        val active = try {
            bridge.isActive()
        } catch (e: IOException) {
            // Bridge out of reach: a dead link shows up as a failed write instead
            return
        }
        if (active == false) fail(link, "the bridge ended the stream")
    }

    private fun fail(link: Link, reason: String) {
        val delay = synchronized(this) {
            // A second report about the same dead link, or one that arrives after shutdown
            if (mStopped || mRestarting || mLink !== link) return
            mRestarting = true
            mLink = null
            // A stream that ran a while was fine; start counting failures over
            if (clock() - mStartedMs >= STABLE_MS) mFailures = 0
            nextDelay()
        }
        Log.w(TAG, "Hue stream lost ($reason), restarting in $delay ms")
        link.close()
        schedule(delay)
    }

    /** Pause before the next restart: 1 s, 2 s, 4 s and so on up to 30 s. */
    private fun nextDelay(): Long {
        val delay = backoffMs(mFailures)
        mFailures++
        return delay
    }

    private fun schedule(delay: Long) {
        try {
            scheduler.schedule({ restart() }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Shut down meanwhile
        }
    }

    private fun restart() {
        if (synchronized(this) { mStopped }) return
        // The bridge may still hold the old session; stop it so the new handshake is accepted
        bridge.stop()
        val link = try {
            bridge.open()
        } catch (e: IOException) {
            val delay = synchronized(this) {
                if (mStopped) return
                nextDelay()
            }
            Log.w(TAG, "Hue stream restart failed (${e.message}), retrying in $delay ms")
            schedule(delay)
            return
        }
        val stopped = synchronized(this) {
            if (!mStopped) {
                mLink = link
                mRestarting = false
                mStartedMs = clock()
            }
            mStopped
        }
        if (stopped) {
            // Shut down while we were connecting: the fresh stream must not outlive the client
            link.close()
            bridge.stop()
            return
        }
        Log.i(TAG, "Hue stream restarted")
    }

    companion object {
        private const val TAG = "HueStreamSession"
        internal const val POLL_MS = 5_000L
        internal const val STABLE_MS = 60_000L
        private const val FIRST_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L

        internal fun backoffMs(failures: Int): Long =
            (FIRST_DELAY_MS shl failures.coerceIn(0, 5)).coerceAtMost(MAX_DELAY_MS)
    }
}
