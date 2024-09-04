package org.wumoe.kaguya

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.wumoe.kaguya.lock.OnceLock

/**
 * Lazy-evaluated linked list of [Piece]s.
 */
class Str(
    val piece: Piece,
    val next: LazyObject? = null,
    private val collectLock: OnceLock = OnceLock()
) : Object, SelfEvalObject {
    override suspend fun getTag() = Companion

    constructor() : this(Latin1(listOf()), null, OnceLock(fused = true)) {
        collected = ""
    }

    companion object : PrimitiveTagWithConversion<Str>() {
        override suspend fun convert(callCtx: Context, arg: LazyObject) = arg.require().toStrLazy().require()

        override val name = "str"

        internal object ConcatImpl : Meta {
            override suspend fun apply(callCtx: Context, args: LazyObject) =
                when (val argsRequired = args.require()) {
                    is Nil -> Str()
                    is Pair -> {
                        val (s, rests) = argsRequired.expect(Pair)
                        val required = s.expect(Str)
                        if (required.next === null) {
                            Str(required.piece, ConcatImpl.applyMetaLazy(rests))
                        } else {
                            Str(required.piece, ConcatImpl.applyMetaLazy(Pair(required.next, rests).lazy()))
                        }
                    }
                    else -> throw typeError(Nil, argsRequired)
                }
        }

        fun concat(vararg args: LazyObject): LazyObject = ConcatImpl.applyMetaLazy(PairOfList(args.asList()).lazy())

        fun fromString(s: String): Str {
            if (s.isEmpty()) {
                return Str()
            }
            val pieces = mutableListOf<kotlin.Pair<Piece, Int>>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c <= '\u00FF') {
                    val begin = i++
                    val piece = mutableListOf(c.code.toByte())
                    while (i < s.length) {
                        if (s[i] > '\u00FF') break
                        piece.add(s[i].code.toByte())
                        ++i
                    }
                    pieces.add(Pair(Latin1(piece.toByteArray().asList()), begin))
                } else if (c.isSurrogate()) {
                    // https://www.unicode.org/faq/utf_bom.html#utf16-3
                    assert(c.isHighSurrogate())
                    val begin = i++
                    val hi = c.code
                    val lo = s[i++].code
                    val x = (hi and 0x3F) shl 10 or (lo and 0x3FF)
                    val w = (hi shr 6) and 0x1F
                    val u = w + 1
                    val piece = SingleChar((u shl 16 or x).toUInt())
                    pieces.add(Pair(piece, begin))
                } else {
                    val begin = i++
                    val piece = mutableListOf(c)
                    while (i < s.length) {
                        if (s[i] <= '\u00FF' || s[i].isSurrogate()) break
                        piece.add(s[i])
                        ++i
                    }
                    pieces.add(Pair(UCS2(piece.toCharArray().asList()), begin))
                }
            }
            val (lastPiece, lastBeginIdx) = pieces.removeLast()
            var result = Str(lastPiece, null, OnceLock(fused = true))
            result.collected = s.substring(lastBeginIdx)
            for ((piece, beginIdx) in pieces.asReversed()) {
                result = Str(piece, result.lazy(), OnceLock(fused = true))
                result.collected = s.substring(beginIdx)
            }
            return result
        }

        private object AsListImpl : Meta {
            override suspend fun apply(callCtx: Context, args: LazyObject) = with(args.expect(Str)) {
                if (piece.isEmpty()) {
                    if (next !== null) {
                        AsListImpl.applyMeta(callCtx, next)
                    } else {
                        this
                    }
                } else {
                    Pair(Str(SingleChar(piece[0])).lazy(), AsListImpl.applyMetaLazy(Str(piece.drop(1), next).lazy(), callCtx))
                }
            }
        }
    }

//    fun take(n: BigInteger): Str = if (n <= piece.size.toBigInteger() || next === null) {
//        Str(piece.take(n.toInt()), null)
//    } else {
//        val impl = Meta { _, args -> args.expect(Str).take(n - piece.size.toBigInteger()) }
//        Str(piece, impl.applyMetaLazy(this.lazy()))
//    }
//
//    suspend fun drop(n: BigInteger): Str {
//        var next = this
//        var i = n
//        while (true) {
//            val it = next
//            if (i > it.piece.size.toBigInteger() && it.next !== null) {
//                i -= it.piece.size.toBigInteger()
//                next = it.next.expect(Str)
//            } else {
//                return Str(piece.drop(i.toInt()), it.next)
//            }
//        }
//    }

    suspend fun toList() = AsListImpl.applyMeta(NoContext, this.lazy())

    override suspend fun eq(other: Object): Boolean {
        if (this === other) return true
        if (other !is Str) return false
        var nextLhs = this
        var nextRhs: Str = other
        while (true) {
            // let lhs be the shorter one
            if (nextLhs.piece.size > nextRhs.piece.size) {
                val tmp = nextLhs
                nextLhs = nextRhs
                nextRhs = tmp
            }
            val lhs = nextLhs
            val rhs = nextRhs
            for (i in lhs.piece.indices) {
                if (lhs.piece[i] != rhs.piece[i]) {
                    return false
                }
            }
            if (lhs.next !== null) {
                nextLhs = lhs.next.expect(Str)
            } else if (lhs.piece.size == rhs.piece.size) {
                return rhs.next === null || rhs.next.expect(Str).isEmpty()
            } else {
                return false
            }
        }
    }

    override suspend fun hc(): Int {
        var result = piece.hashCode()
        var next = next
        while (next !== null) {
            next.expect(Str).let {
                result *= 31
                result += it.piece.hashCode()
                next = it.next
            }
        }
        return result
    }

    override suspend fun toStrLazy() = lazy()

    suspend fun isEmpty(): Boolean {
        var next = this
        while (true) {
            val it = next
            if (it.piece.isNotEmpty()) {
                return false
            }
            if (it.next !== null) {
                next = it.next.expect(Str)
            } else {
                return true
            }
        }
    }

//    suspend fun len(): BigInteger {
//        var result = piece.size.toBigInteger()
//        var next = next
//        while (next !== null) {
//            next.expect(Str).let {
//                result += it.piece.size.toBigInteger()
//                next = it.next
//            }
//        }
//        return result
//    }

    override fun equals(other: Any?) = runBlocking { this === other || other is Object && eq(other) }
    override fun hashCode() = runBlocking { hc() }

    private lateinit var collected: String

    suspend fun collect(): String {
        collectLock.runOnce {
            val builder = StringBuilder(piece.toString())
            var next = next
            while (next !== null) {
                val str = next.expect(Str)
                builder.append(str.piece.toString())
                next = str.next
            }
            collected = builder.toString()
        }
        return collected
    }

    override fun toString() = runBlocking { collect() }

    fun asCharFlow() = asPieceFlow().transform {
        emitAll(it.asCharSeq().asFlow())
    }

    fun asPieceFlow() = flow {
        var next = this@Str
        while (true) {
            emit(next.piece)
            next = (next.next ?: break).expect(Str)
        }
    }
}

/**
 * A memory-contiguous string, encoded by either Latin-1, UCS-2, or a single Unicode codepoint.
 */
sealed class Piece : AbstractList<UInt>() {
    final override fun equals(other: Any?) = super.equals(other)

    final override fun hashCode() = super.hashCode()

    abstract fun take(n: Int): Piece

    abstract fun drop(n: Int): Piece

    abstract fun asCharSeq(): Sequence<Char>
}

data class Latin1(val inner: List<Byte>) : Piece() {
    constructor(array: ByteArray) : this(array.asList())

    constructor(s: String) : this(s.toByteArray(Charsets.ISO_8859_1))

    override val size get() = inner.size

    override fun get(index: Int) = inner[index].toUInt()

    override fun take(n: Int) = Latin1(inner.subList(0, n))

    override fun drop(n: Int) = Latin1(inner.subList(n, inner.size))

    override fun toString() = String(inner.map { it.toInt().toChar() }.toCharArray())

    override fun asCharSeq() = inner.asSequence().map { it.toInt().toChar() }
}

data class UCS2(val inner: List<Char>) : Piece() {
    constructor(array: CharArray) : this(array.asList())

    constructor(s: String) : this(s.toCharArray())

    override val size get() = inner.size

    override fun get(index: Int) = inner[index].code.toUInt()

    override fun take(n: Int) = UCS2(inner.subList(0, n))

    override fun drop(n: Int) = UCS2(inner.subList(n, inner.size))

    override fun toString() = String(inner.toCharArray())

    override fun asCharSeq() = inner.asSequence()
}

data class SingleChar(val inner: UInt) : Piece() {
    override val size get() = 1

    constructor(s: String) : this(s.codePoints().iterator().let {
        require(it.hasNext())
        val c = it.next()
        require(!it.hasNext())
        c.toUInt()
    })

    override fun get(index: Int) = if (index == 0) inner else throw IndexOutOfBoundsException(index)

    override fun take(n: Int) = if (n <= 0) Latin1(listOf()) else this

    override fun drop(n: Int) = if (n > 0) Latin1(listOf()) else this

    override fun toString() = toCharArray().concatToString()

    private fun toCharArray() =
        if (inner <= 0xFFFFu) {
            charArrayOf(inner.toInt().toChar())
        } else {
            // https://www.unicode.org/faq/utf_bom.html#utf16-3
            val x = inner and 0xFFFFu
            val u = (inner shr 16) and 0x1Fu
            val w = u - 1u
            val hi = 0xD800u or (w shl 6) or (x shr 10)
            val lo = 0xDC00u or (x and 0x3FFu)
            charArrayOf(hi.toInt().toChar(), lo.toInt().toChar())
        }

    override fun asCharSeq() = toCharArray().asSequence()
}

fun String.toStr() = Str.fromString(this)