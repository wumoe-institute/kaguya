package org.wumoe.kaguya

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import org.wumoe.kaguya.lock.OnceLock

/**
 * Callable Kaguya object.
 */
interface Procedure : Object, SelfEvalObject {
    /**
     * Apply the given AST.
     */
    suspend fun applyMeta(callCtx: Context, args: LazyObject): Object

    override suspend fun getTag() = Companion

    companion object : PrimitiveTagWithConversion<Procedure>() {
        override suspend fun convert(callCtx: Context, arg: LazyObject) = defaultConversion(arg)

        override val name = "procedure"
    }

    override val str get() = "<INTERNAL PROCEDURE#${hashCode()}>".toStr().lazy()
}

sealed interface ProcedureImplHelper : Procedure {
    /**
     * Returns the arguments processed.
     */
    suspend fun beforeApply(callCtx: Context, args: LazyObject): LazyObject

    suspend fun apply(callCtx: Context, args: LazyObject): Object

    suspend fun afterApply(callCtx: Context, result: Object): Object

    override suspend fun applyMeta(callCtx: Context, args: LazyObject) =
        afterApply(callCtx, apply(callCtx, beforeApply(callCtx, args)))
}

fun Procedure.applyMetaLazy(args: LazyObject, callCtx: Context = NoContext): LazyObject =
    LazyObject.eval(Pair(this.lazy(args.pos), args).withPos(args.pos), callCtx)

/**
 * Passes the given AST evaluated to [apply].
 */
interface ArgumentEvaluated : ProcedureImplHelper {
    override suspend fun beforeApply(callCtx: Context, args: LazyObject) =
        MapEval.applyMeta(callCtx, args).lazy(args.pos)

    suspend fun applyEvaluated(callCtx: Context, evaluatedArgs: LazyObject) =
        afterApply(callCtx, apply(callCtx, evaluatedArgs))
}

/**
 * Passes the given AST as is to [apply].
 */
interface ArgumentAsIs : ProcedureImplHelper {
    override suspend fun beforeApply(callCtx: Context, args: LazyObject) = args
}

/**
 * Returns the result of [apply] evaluated.
 */
interface ResultEvaluated : ProcedureImplHelper {
    override suspend fun afterApply(callCtx: Context, result: Object) = result.eval(callCtx)
}

/**
 * Returns the result of [apply] as is.
 */
interface ResultAsIs : ProcedureImplHelper {
    override suspend fun afterApply(callCtx: Context, result: Object) = result
}

fun interface Function : ArgumentEvaluated, ResultAsIs

fun interface Meta : ArgumentAsIs, ResultAsIs

fun interface Macro : ArgumentAsIs, ResultEvaluated

fun interface MacroFunction : ArgumentEvaluated, ResultEvaluated

/**
 * This is a temporary procedure to map all its arguments evaluated as forms.
 * Although it can be easily implemented in Kaguya, but it's hard to define
 * the procedures like `cons`, `eval`, `apply-list` which it would use without
 * completing the definition of [Procedure] first.
 *
 * the following implementation is equivalent to this Kaguya definition:
 * ```lisp
 * (def-meta (map-eval a . b) -> (cons (eval a) (apply-list map-eval b)))
 * (def-meta (map-eval . ()) -> nil)
 * (def-meta (map-eval . obj) -> (eval obj))
 * ```
 */
internal object MapEval : Meta, SelfEvalObject {
    override suspend fun apply(callCtx: Context, args: LazyObject) =
        args.require().let {
            when (it) {
                is Pair -> {
                    val (car, cdr) = it
                    Pair(car.evalLazy(callCtx), applyMetaLazy(cdr, callCtx))
                }

                is Nil -> Nil
                else -> it.eval(callCtx)
            }
        }
}

sealed class AbstractLambda(
    val pat: LazyObject,
    val form: LazyObject,
    val ctx: Context,
    name: String
) :
    ProcedureImplHelper {
    private sealed interface ConstMemoization

    private data object NotConst : ConstMemoization

    private data class Memoized(val result: Result<Object>) : ConstMemoization

    private lateinit var memoization: ConstMemoization
    private val memoizationLock = OnceLock()

    override suspend fun apply(callCtx: Context, args: LazyObject): Object = coroutineScope {
        val requiredForm = async { form.require() }
        memoizationLock.runOnce {
            val bindResult = bindPattern(ctx, pat, args)
            if (bindResult.isConst()) {
                val result = try {
                    Result.success(requiredForm.await().eval(ctx))
                } catch (e: Panic) {
                    Result.failure(e)
                }
                memoization = Memoized(result)
                return@coroutineScope result.getOrThrow()
            } else {
                memoization = NotConst
                release()
                val newCtx = bindResult.toContext(ctx)
                return@coroutineScope requiredForm.await().eval(newCtx)
            }
        }

        when (val it = memoization) {
            is NotConst -> {
                val bindResult = bindPattern(ctx, pat, args)
                val newCtx = bindResult.toContext(ctx)
                requiredForm.await().eval(newCtx)
            }
            is Memoized -> {
                it.result.getOrThrow()
            }
        }
    }

    override suspend fun eq(other: Object) = coroutineScope {
        if (this === other) {
            true
        } else if (javaClass == other.javaClass) {
            other as AbstractLambda
            ctx === other.ctx && listOf(pat, form).eq(listOf(other.pat, other.form))
        } else {
            false
        }
    }

    override suspend fun hc() = ctx.hashCode() + listOf(pat, form).hc() * 13

    override val str = Str.concat(
        Str(Latin1("($name ")).lazy(),
        pat.toStrLazy(),
        Str(Latin1(" -> ")).lazy(),
        form.toStrLazy(),
        Str(Latin1(")")).lazy(),
    )
}

/**
 * Created by `fn`-forms in Kaguya.
 */
class FunctionLambda(pat: LazyObject, form: LazyObject, ctx: Context) :
    AbstractLambda(pat, form, ctx, "fn"), Function

/**
 * Created by `meta`-forms in Kaguya.
 */
class MetaLambda(pat: LazyObject, form: LazyObject, ctx: Context) :
    AbstractLambda(pat, form, ctx, "meta"), Meta

/**
 * Created by `macro`-forms in Kaguya.
 */
class MacroLambda(pat: LazyObject, form: LazyObject, ctx: Context) :
    AbstractLambda(pat, form, ctx, "macro"), Macro

/**
 * Created by `macro-fn`-forms in Kaguya.
 */
class MacroFunctionLambda(pat: LazyObject, form: LazyObject, ctx: Context) :
    AbstractLambda(pat, form, ctx, "macro-fn"), MacroFunction

/**
 * A poly-procedure is an anonymous procedure containing a list of [Procedure]s.
 * When being applied, it tries to forward the arguments to the first procedure.
 * If the procedure didn't throw a [BindFailure], then its result (or [Panic]) is forwarded.
 * Otherwise, it tries the next one.
 * It is created by `poly-*`-forms in Kaguya.
 */
class PolyProcedure(val definitions: LazyObject) : Procedure, SelfEvalObject {
    override suspend fun applyMeta(callCtx: Context, args: LazyObject) = coroutineScope {
        val mappedEvalArgs = MapEval.applyMetaLazy(args, callCtx)
        val channel = definitions.asFlow(unlimited).map {
            async {
                val proc = it.expect(Procedure)
                runCatching {
                    if (proc is ArgumentEvaluated) {
                        proc.applyEvaluated(callCtx, mappedEvalArgs)
                    } else {
                        proc.applyMeta(callCtx, args)
                    }
                }
            }
        }.produceIn(this)
        val failures = mutableListOf<BindFailure>()
        for (future in channel) {
            future.await().fold(
                onSuccess = {
                    channel.cancel()
                    return@coroutineScope it
                },
                onFailure = {
                    if (it is BindFailure) {
                        failures.add(it)
                    } else {
                        throw it
                    }
                }
            )
        }
        throw PolyBindFailure(failures)
    }

    override suspend fun eq(other: Object) =
        this === other || other is PolyProcedure && definitions.eq(other.definitions)

    override suspend fun hc() = definitions.hc()

    override val str = Pair(Symbol("poly").lazy(), definitions).str
}

private suspend fun bindPattern(ctx: Context, pat: LazyObject, arg: LazyObject): BindResult = coroutineScope {
    val requiredArgFuture = async { arg.require() }
    when (val requiredPat = pat.require()) {
        is Pair -> {
            val (patCar, patCdr) = requiredPat
            val (argCar, argCdr) = when (val requiredArg = requiredArgFuture.await()) {
                is Pair -> requiredArg

                is Str -> when (val l = requiredArg.toList()) {
                    is Pair -> l
                    is Nil -> throw BindFailureLeaf(Pair.withPos(pat.pos), requiredArg.getTag().withPos(arg.pos))
                    else -> error("unreachable")
                }

                else -> throw BindFailureLeaf(Pair.withPos(pat.pos), requiredArg.getTag().withPos(arg.pos))
            }
            val carResult = async { bindPattern(ctx, patCar, argCar) }
            val cdrResult = async { bindPattern(ctx, patCdr, argCdr) }
            carResult.await() + cdrResult.await()
        }

        is Symbol -> {
            requiredArgFuture.cancel()
            if (requiredPat.isIgnore()) {
                BindResult()
            } else {
                BindResult(requiredPat, arg)
            }
        }

        is Nil -> when (val requiredArg = requiredArgFuture.await()) {
            is Nil -> BindResult()

            is Str ->
                if (requiredArg.isEmpty()) {
                    BindResult()
                } else {
                    throw BindFailureLeaf(pat.requirePositioned(), arg.requirePositioned())
                }

            else -> throw BindFailureLeaf(Pair.withPos(pat.pos), requiredArg.getTag().withPos(arg.pos))
        }

        else ->  {
            val requiredArg = requiredArgFuture.await()
            if (requiredPat.eq(requiredArg)) {
                BindResult()
            } else {
                throw BindFailureLeaf(pat.requirePositioned(), arg.requirePositioned())
            }
        }
    }
}

private data class BindResult(val result: List<kotlin.Pair<Symbol, LazyObject>> = listOf()) {
    constructor(name: Symbol, obj: LazyObject) : this(listOf(Pair(name, obj)))

    fun isConst() = result.isEmpty()

    fun toContext(parent: Context): Context {
        val ctx = mutableMapOf<Symbol, LazyObject>()
        for ((k, v) in result) {
            if (ctx.contains(k)) {
                throw redefiningSymbol(k)
            }
            ctx[k] = v
        }
        return ChildContext(ctx, parent)
    }
}

private operator fun BindResult.plus(other: BindResult) = BindResult(result + other.result)

sealed class BindFailure : Panic("No pattern matches the call.")

data class BindFailureLeaf(
    val expect: Positioned<Object>,
    val found: Positioned<Object>,
) : BindFailure()

data class PolyBindFailure(val failures: List<BindFailure>) : BindFailure()
