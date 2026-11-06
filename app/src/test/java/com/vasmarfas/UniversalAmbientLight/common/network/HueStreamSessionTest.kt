package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class HueStreamSessionTest {

    /** Runs scheduled work only when the test asks, so timing is deterministic. */
    private class ManualScheduler(
        private val real: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    ) : ScheduledExecutorService by real {
        val once = ArrayList<Pair<Long, Runnable>>()
        val repeating = ArrayList<Runnable>()

        private fun dummy(): ScheduledFuture<*> = real.schedule({}, 1, TimeUnit.DAYS)

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            once += unit.toMillis(delay) to command
            return dummy()
        }

        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            throw UnsupportedOperationException()

        override fun scheduleWithFixedDelay(
            command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit,
        ): ScheduledFuture<*> {
            repeating += command
            return dummy()
        }

        fun runOnce(): Long {
            val (delay, task) = once.removeAt(0)
            task.run()
            return delay
        }

        fun poll() = repeating.forEach { it.run() }
    }

    private class FakeLink(private val events: MutableList<String>, val name: String) : HueStreamSession.Link {
        var failNext = false
        var sent = 0
        var closed = false

        override fun send(data: ByteArray) {
            if (closed) throw IOException("Socket is closed")
            if (failNext) {
                failNext = false
                // Bouncy Castle closes the socket after any failed write
                closed = true
                throw IOException("sendto failed: ENOBUFS")
            }
            sent++
        }

        override fun close() {
            closed = true
            events += "close $name"
        }
    }

    private class FakeBridge : HueStreamSession.Bridge {
        val events = ArrayList<String>()
        val links = ArrayList<FakeLink>()
        var openFailures = 0
        var active: Boolean? = true

        override fun open(): HueStreamSession.Link {
            if (openFailures > 0) {
                openFailures--
                events += "open failed"
                throw IOException("bridge busy")
            }
            val link = FakeLink(events, "link${links.size}")
            links += link
            events += "start ${link.name}"
            active = true
            return link
        }

        override fun stop() {
            events += "stop"
            active = false
        }

        override fun isActive(): Boolean? = active
    }

    private val scheduler = ManualScheduler()
    private val bridge = FakeBridge()
    private var now = 0L
    private val session = HueStreamSession(bridge, scheduler) { now }

    @After
    fun tearDown() {
        scheduler.shutdownNow()
    }

    private fun started(): FakeLink {
        val link = bridge.open() as FakeLink
        session.start(link)
        bridge.events.clear()
        return link
    }

    @Test
    fun `a failed write does not escape and the stream is reopened`() {
        val first = started()
        first.failNext = true

        assertFalse(session.send(ByteArray(1)))
        // Frames during the restart are dropped quietly instead of failing again
        assertFalse(session.send(ByteArray(1)))
        assertEquals(1000L, scheduler.runOnce())

        assertTrue(session.send(ByteArray(1)))
        assertEquals(1, bridge.links[1].sent)
    }

    @Test
    fun `the old stream is stopped before the new one starts, never after`() {
        val first = started()
        first.failNext = true
        session.send(ByteArray(1))
        scheduler.runOnce()

        assertEquals(listOf("close link0", "stop", "start link1"), bridge.events)
        assertEquals(true, bridge.active)
    }

    @Test
    fun `a stream the bridge ended is noticed and restarted`() {
        val first = started()
        bridge.active = false

        scheduler.poll()
        scheduler.runOnce()

        assertTrue(first.closed)
        assertTrue(session.send(ByteArray(1)))
        assertEquals(1, bridge.links[1].sent)
    }

    @Test
    fun `an unreachable status check does not restart the stream`() {
        started()
        bridge.active = null

        scheduler.poll()

        assertTrue(scheduler.once.isEmpty())
    }

    @Test
    fun `a refusing bridge is retried with growing pauses`() {
        val first = started()
        bridge.openFailures = 3
        first.failNext = true
        session.send(ByteArray(1))

        val delays = List(4) { scheduler.runOnce() }

        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), delays)
        assertTrue(session.send(ByteArray(1)))
    }

    @Test
    fun `a stream that ran a while starts the pauses over`() {
        val first = started()
        first.failNext = true
        session.send(ByteArray(1))
        scheduler.runOnce()

        now += HueStreamSession.STABLE_MS
        bridge.links[1].failNext = true
        session.send(ByteArray(1))

        assertEquals(1000L, scheduler.runOnce())
    }

    @Test
    fun `a stream that keeps dying backs off`() {
        val first = started()
        first.failNext = true
        session.send(ByteArray(1))
        scheduler.runOnce()

        bridge.links[1].failNext = true
        session.send(ByteArray(1))

        assertEquals(2000L, scheduler.runOnce())
    }

    @Test
    fun `nothing is reopened after shutdown`() {
        val first = started()
        first.failNext = true
        session.send(ByteArray(1))

        session.shutdown(ByteArray(1))
        scheduler.runOnce()

        assertEquals(1, bridge.links.size)
        assertFalse(session.send(ByteArray(1)))
    }

    @Test
    fun `shutdown sends the last message and closes the link`() {
        val first = started()

        session.shutdown(ByteArray(1))

        assertEquals(1, first.sent)
        assertTrue(first.closed)
    }

    @Test
    fun `backoff is capped at thirty seconds`() {
        assertEquals(30_000L, HueStreamSession.backoffMs(10))
    }
}
