package org.wumoe.kaguya.lock

fun interface Releaser {
    fun release()
}

inline fun fusedReleaser(crossinline impl: () -> Unit) =
    object : Releaser {
        var released = false
        override fun release() {
            if (!released) {
                impl()
                released = true
            }
        }
    }

inline fun <T> Releaser.releaseWith(block: Releaser.() -> T) = block().also { release() }

inline fun <T> Releaser.releaseWithCatching(block: Releaser.() -> T) = try { block() } finally { release() }