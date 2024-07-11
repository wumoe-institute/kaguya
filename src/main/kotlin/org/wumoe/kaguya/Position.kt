package org.wumoe.kaguya

/**
 * Position of a token (tree) in a file.
 */
data class Position(val file: Int, val idx: Int, val len: Int) {
    companion object {
        val builtin = Position(-1, -1, -1)
        fun Anonymous(idx: Int, len: Int) = Position(-1, idx, len)
    }

    fun isBuiltin() = idx == -1

    fun isAnonymous() = file == -1

    val endIdx get() = idx + len
}

/**
 * [T] with a [Position].
 */
data class Positioned<out T>(val inner: T, val pos: Position) {
    inline fun<R> map(block: (T) -> R): Positioned<R> {
        return Positioned(block(inner), pos)
    }
}

infix fun Position.until(other: Position) = Position(file, this.idx, other.len + other.idx - this.idx).also {
    assert(this.file == other.file)
    assert(!this.isBuiltin() && !other.isBuiltin())
    assert(this.idx <= other.idx)
}

/**
 * Another way of writing `Positioned(this, pos)`
 */
fun <T> T.withPos(pos: Position) = Positioned(this, pos)