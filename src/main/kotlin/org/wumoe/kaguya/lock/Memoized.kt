package org.wumoe.kaguya.lock

class Memoized<T> {
    var inner: T? = null
    val lock = OnceLock()

    suspend inline fun getOrInit(init: () -> T): T {
        lock.runOnce { inner = init() }
        return inner!!
    }
}