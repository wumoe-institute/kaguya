package org.wumoe.kaguya.lock

import kotlinx.coroutines.yield
import org.wumoe.kaguya.loop
import java.util.concurrent.atomic.AtomicInteger

class OnceLock(fused: Boolean = false) {
    @PublishedApi
    internal enum class Status {
        UNLOCKED,
        LOCKED,
        FUSED
    }

    @PublishedApi
    internal val status = AtomicInteger(if (fused) Status.FUSED.ordinal else Status.UNLOCKED.ordinal)

    suspend inline fun runOnce(block: Releaser.() -> Unit) {
        loop {
            when (status.compareAndExchange(Status.UNLOCKED.ordinal, Status.LOCKED.ordinal)) {
                Status.UNLOCKED.ordinal -> {
                    try {
                        val releaser = fusedReleaser { status.lazySet(Status.FUSED.ordinal) }
                        releaser.releaseWith { block() }
                        return
                    } catch (e: Throwable) {
                        status.lazySet(Status.UNLOCKED.ordinal)
                        throw e
                    }
                }

                Status.LOCKED.ordinal -> {
                    do {
                        yield()
                    } while (status.get() == Status.LOCKED.ordinal)
                }

                Status.FUSED.ordinal -> return
            }
        }
    }

    fun isFused() = status.get() == Status.FUSED.ordinal
}