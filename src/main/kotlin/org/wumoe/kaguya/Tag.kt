package org.wumoe.kaguya

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.single
import org.wumoe.kaguya.lock.Memoized
import java.lang.System.identityHashCode

// TODO: i wanna replace these with a trait system

/**
 * Kaguya tag. It contains the type information of an [Object].
 */
sealed interface Tag : Object, SelfEvalObject {
    val name: String

    override suspend fun toStrLazy() = name.toStr().lazy()
}

class TaggedObject(val tag: LazyObject, val inner: LazyObject) : Object {
    override suspend fun getTag() = tag.require()

    override suspend fun eval(ctx: Context) = throw syntaxError("Attempt to evaluate a tagged object.")

    override suspend fun eq(other: Object) =
        this === other || other is TaggedObject && listOf(tag, inner).eq(listOf(other.tag, other.inner))

    override suspend fun hc() = listOf(tag, inner).hc()

    private val str = Memoized<LazyObject>()

    override suspend fun toStrLazy() = str.getOrInit {
        Str.concat(
            inner.toStrLazy(),
            Str(Latin1(": ")).lazy(),
            tag.toStrLazy()
        )
    }
}

/**
 * Tags generated by users in Kaguya programs.
 */
class GeneratedTag : Tag, Function {
    override var name: String

    override suspend fun toStrLazy() = super<Tag>.toStrLazy()

    constructor(name: String) {
        this.name = name
    }

    constructor() {
        this.name = "<GENERATED TAG#${hashCode()}>"
    }

    override fun toString() = name

    override fun equals(other: Any?) = this === other

    override fun hashCode() = identityHashCode(this)

    override suspend fun apply(callCtx: Context, args: LazyObject) = coroutineScope {
        val inner = args.asFlow(1).single()
        TaggedObject(lazy(inner.pos), inner)
    }
}

abstract class PrimitiveTag<T: Object> : Tag {
    override fun toString() = name
}

abstract class PrimitiveTagWithConversion<T: Object> : PrimitiveTag<T>(), Function {
    override suspend fun apply(callCtx: Context, args: LazyObject) =
        convert(callCtx, args.asFlow(1).single())

    override suspend fun toStrLazy() = super<PrimitiveTag>.toStrLazy()

    abstract suspend fun convert(callCtx: Context, arg: LazyObject): Object
}

internal suspend inline fun <reified T: Object> PrimitiveTagWithConversion<T>.defaultConversion(arg: LazyObject) =
    arg.expect { if (it is T) it else throw noConversion(this, it) }