package org.wumoe.kaguya.parser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import org.wumoe.kaguya.Positioned

interface Stream<out T> : ChannelIterator<T> {
    fun peek(): T

    operator fun iterator() = this
}

data class PeekableChannelIterator<out T>(
    private val inner: ChannelIterator<T>,
    private var peeked: T? = null
) :
    Stream<T> {
    override operator fun next() = peek().also { peeked = null }

    override fun peek() = checkNotNull(peeked) { "`hasNext()` has not been invoked" }

    override suspend fun hasNext() = peeked !== null || inner.hasNext() && true.also { peeked = inner.next() }
}

fun <T> ChannelIterator<T>.asStream() =
    if (this is Stream<T>) {
        this
    } else {
        PeekableChannelIterator(this)
    }

typealias CodeStream = Stream<IndexedValue<Char>>
typealias TokenStream = Stream<Positioned<Token>>

fun <T> Flow<T>.streamIn(scope: CoroutineScope) = produceIn(scope).asStream()

fun <T> ReceiveChannel<T>.asStream() = iterator().asStream()

fun <T> Stream<T>.withIndex() = object : Stream<IndexedValue<T>> {
    var idx = 0

    override fun peek() = IndexedValue(idx, this@withIndex.peek())

    override suspend fun hasNext() = this@withIndex.hasNext()

    override fun next() = IndexedValue(idx++, this@withIndex.next())
}