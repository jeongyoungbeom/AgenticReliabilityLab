package com.project.agenticreliabilitylab.testspec.application

import dev.cel.common.CelAbstractSyntaxTree
import dev.cel.common.CelFunctionDecl
import dev.cel.common.CelOverloadDecl
import dev.cel.common.CelValidationException
import dev.cel.common.ast.CelExpr
import dev.cel.common.ast.CelExprVisitor
import dev.cel.common.types.ListType
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelRuntime
import dev.cel.runtime.CelRuntimeFactory
import org.springframework.stereotype.Component

/**
 * Compiles and evaluates the expressions inside a specification.
 *
 * Expressions come from a model, so the property that matters is not "does it work" but "what can it do".
 * CEL is not Turing complete, so an expression cannot loop forever, and only the variables declared here exist at
 * all - an expression naming anything else fails to compile rather than failing after the Target was changed.
 *
 * Standard macros (`all`, `exists`, `map`, `filter`) are left off. CEL disables them unless they are enabled, and
 * they stay disabled here: they would let a model write conditions a reviewer cannot read on an approval screen,
 * and a human approval nobody can read is not an approval.
 */
@Component
class SpecExpressionEnvironment {
    /**
     * Compiles one expression against the identifiers a run will provide.
     *
     * Compiling during validation means a specification that misspells an observation is rejected before anything
     * is dispatched, rather than after the Target has already been changed.
     */
    fun compile(expression: String, identifiers: Set<String>): CompiledExpression {
        val builder = CelCompilerFactory.standardCelCompilerBuilder()
        builder.addFunctionDeclarations(TIME_AXIS_DECLARATIONS)
        identifiers.forEach { name -> builder.addVar(name, SimpleType.DYN) }
        return try {
            val ast = builder.build().compile(expression).ast
            CompiledExpression(
                expression,
                RUNTIME.createProgram(ast),
                IdentifierCollector().collect(ast),
            )
        } catch (exception: CelValidationException) {
            throw SpecExpressionException(
                "Expression '$expression' is not valid: ${exception.message}",
                exception,
            )
        }
    }

    private companion object {
        private val SPAN_LIST: ListType = ListType.create(SimpleType.DYN)

        /**
         * The time-axis functions, declared for the compiler and bound for the runtime.
         *
         * They are declared here rather than left to the standard environment because a specification must not be
         * able to name a function that does not exist: an invariant calling an unknown function has to fail at
         * approval time, not after the Target was already changed.
         */
        val TIME_AXIS_DECLARATIONS: List<CelFunctionDecl> = listOf(
            booleanOverTwoSpanLists(SpanTimeline.NO_OVERLAP),
            booleanOverTwoSpanLists(SpanTimeline.ORDERED),
            CelFunctionDecl.newFunctionDeclaration(
                SpanTimeline.MAX_START_LAG_MS,
                CelOverloadDecl.newGlobalOverload(
                    overloadId(SpanTimeline.MAX_START_LAG_MS),
                    SimpleType.INT,
                    SPAN_LIST,
                    SPAN_LIST,
                ),
            ),
            CelFunctionDecl.newFunctionDeclaration(
                SpanTimeline.TRACE_COUNT,
                CelOverloadDecl.newGlobalOverload(
                    singleListOverloadId(SpanTimeline.TRACE_COUNT),
                    SimpleType.INT,
                    SPAN_LIST,
                ),
            ),
        )

        val RUNTIME: CelRuntime = CelRuntimeFactory.standardCelRuntimeBuilder()
            .addFunctionBindings(
                CelRuntime.CelFunctionBinding.from(
                    overloadId(SpanTimeline.NO_OVERLAP),
                    List::class.java,
                    List::class.java,
                ) { first, second -> SpanTimeline.noOverlap(first, second) },
                CelRuntime.CelFunctionBinding.from(
                    overloadId(SpanTimeline.ORDERED),
                    List::class.java,
                    List::class.java,
                ) { first, second -> SpanTimeline.ordered(first, second) },
                CelRuntime.CelFunctionBinding.from(
                    overloadId(SpanTimeline.MAX_START_LAG_MS),
                    List::class.java,
                    List::class.java,
                ) { first, second -> SpanTimeline.maxStartLagMs(first, second) },
                CelRuntime.CelFunctionBinding.from(
                    singleListOverloadId(SpanTimeline.TRACE_COUNT),
                    List::class.java,
                ) { spans -> SpanTimeline.traceCount(spans) },
            )
            .build()

        private fun booleanOverTwoSpanLists(name: String): CelFunctionDecl =
            CelFunctionDecl.newFunctionDeclaration(
                name,
                CelOverloadDecl.newGlobalOverload(overloadId(name), SimpleType.BOOL, SPAN_LIST, SPAN_LIST),
            )

        private fun overloadId(name: String): String = "${name}_list_list"

        private fun singleListOverloadId(name: String): String = "${name}_list"
    }

    private class IdentifierCollector : CelExprVisitor() {
        private val identifiers = linkedSetOf<String>()

        fun collect(ast: CelAbstractSyntaxTree): Set<String> {
            visit(ast)
            return identifiers.toSet()
        }

        override fun visit(expr: CelExpr, ident: CelExpr.CelIdent) {
            identifiers.add(ident.name())
        }
    }
}

/** A compiled expression, ready to evaluate against one run's observed values. */
class CompiledExpression(
    private val expression: String,
    private val program: CelRuntime.Program,
    val referencedIdentifiers: Set<String>,
) {
    /**
     * Evaluates against the values a run observed.
     *
     * A missing binding is not silently treated as null: the caller decides whether an unobserved value makes the
     * invariant unjudgeable, and that decision must not be hidden inside expression evaluation.
     */
    @Suppress("TooGenericExceptionCaught") // Any evaluation failure means the same thing: this cannot be judged.
    fun evaluate(bindings: Map<String, Any>): Any = try {
        program.eval(bindings)
    } catch (exception: Exception) {
        throw refusal(exception)
            ?.let { refused -> UnjudgeableObservationException(refused.message.orEmpty(), exception) }
            ?: SpecExpressionException(
                "Expression '$expression' could not be evaluated: ${exception.javaClass.simpleName}",
                exception,
            )
    }

    /**
     * The refusal our own time-axis functions raised, if this failure was one.
     *
     * Its message is a reason an operator can act on - "no trace carries both spans" sends someone to the
     * collector, "3 traces started the first span without ever reaching the second" sends them to the code. That
     * sentence is the entire point of refusing rather than passing, and the expression runtime wraps it, so it is
     * recovered from the cause chain along with its type.
     *
     * Anything else is named by type only. A fault raised inside the runtime may quote the values it was handed,
     * and observed values are not ours to copy into a stored verdict.
     */
    private fun refusal(exception: Throwable): UnjudgeableObservationException? =
        generateSequence(exception) { thrown -> thrown.cause.takeIf { it !== thrown } }
            .take(MAX_CAUSE_DEPTH)
            .filterIsInstance<UnjudgeableObservationException>()
            .firstOrNull()

    fun evaluateBoolean(bindings: Map<String, Any>): Boolean {
        val result = evaluate(bindings)
        return result as? Boolean ?: throw SpecExpressionException(
            "Expression '$expression' must produce a boolean but produced ${result.javaClass.simpleName}",
        )
    }

    private companion object {
        /** Deep enough for any wrapping the expression runtime does, bounded so a cyclic cause cannot hang a run. */
        const val MAX_CAUSE_DEPTH = 16
    }
}

open class SpecExpressionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The observation was read, but it does not support a judgement either way.
 *
 * Distinct from [SpecExpressionException] because the two send an operator to opposite places. A failed
 * expression means the specification is wrong and should be rewritten; an unjudgeable observation means the
 * specification is fine and the *evidence* is thin - a collector that is not running, a trace store that has not
 * caught up, a reservation that never reached its deduction. Reporting the second as the first sends someone to
 * edit a correct specification, which is worse than saying nothing.
 */
class UnjudgeableObservationException(message: String, cause: Throwable? = null) :
    SpecExpressionException(message, cause)
