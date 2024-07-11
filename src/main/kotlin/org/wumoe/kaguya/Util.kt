package org.wumoe.kaguya

import kotlinx.coroutines.yield

inline fun loop(block: () -> Unit): Nothing {
    while (true) {
        block()
    }
}

suspend inline fun waitUntil(condition: () -> Boolean) {
    while (!condition()) {
        yield()
    }
}