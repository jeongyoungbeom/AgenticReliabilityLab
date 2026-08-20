package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.RecordedResponse
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Getting a value out of a response is where a reliability tool most easily starts lying: a path that silently
 * reads nothing looks exactly like a value of zero. Each test here pins one of those cases.
 */
class ResponsePathEvaluatorTests {
    private val evaluator = ResponsePathEvaluator(ObjectMapper())

    @Test
    fun `reads a field out of a single response body`() {
        val scope = evaluator.responseScope(response(1, 200, """{"stock":7}"""))

        assertEquals(7L, evaluator.evaluate("response.body.stock", scope))
    }

    @Test
    fun `reads http metadata apart from body fields of the same name`() {
        val scope = evaluator.responseScope(response(1, 201, """{"status":"CREATED"}"""))

        assertEquals(201L, evaluator.evaluate("response.status", scope))
        assertEquals("CREATED", evaluator.evaluate("response.body.status", scope))
    }

    @Test
    fun `sums a nested value across every response`() {
        val scope = evaluator.responsesScope(mapOf("responses" to ordersWithQuantities(2, 3)))

        assertEquals(5L, evaluator.evaluate("sum(responses[*].body.items[*].quantity)", scope))
    }

    @Test
    fun `counts the responses that carry a field`() {
        val responses = listOf(
            response(1, 200, """{"failedItems":[{"productId":"p"},{"productId":"q"}]}"""),
            response(2, 200, """{"failedItems":[]}"""),
        )
        val scope = evaluator.responsesScope(mapOf("responses" to responses))

        assertEquals(2L, evaluator.evaluate("count(responses[*].body.failedItems[*])", scope))
    }

    @Test
    fun `treats a missing nested field as none of that thing`() {
        val responses = listOf(response(1, 500, """{"error":"boom"}"""))
        val scope = evaluator.responsesScope(mapOf("responses" to responses))

        assertEquals(0L, evaluator.evaluate("sum(responses[*].body.items[*].quantity)", scope))
        assertEquals(0L, evaluator.evaluate("count(responses[*].body.items[*])", scope))
    }

    @Test
    fun `refuses to read a collection the run never produced`() {
        val scope = evaluator.responsesScope(mapOf("orders" to ordersWithQuantities(1)))

        val failure = assertFailsWith<ObservationExpressionException> {
            evaluator.evaluate("sum(responses[*].body.items[*].quantity)", scope)
        }
        assertTrue(failure.message!!.contains("responses"))
    }

    @Test
    fun `has no answer for an average of nothing`() {
        val scope = evaluator.responsesScope(mapOf("responses" to emptyList()))

        assertFailsWith<ObservationExpressionException> {
            evaluator.evaluate("avg(responses[*].durationMs)", scope)
        }
    }

    @Test
    fun `reports the slowest request`() {
        val responses = listOf(response(1, 200, "{}", 40), response(2, 200, "{}", 130))
        val scope = evaluator.responsesScope(mapOf("responses" to responses))

        assertEquals(130.0, evaluator.evaluate("max(responses[*].durationMs)", scope))
    }

    @Test
    fun `keeps whole numbers whole so a threshold compares equal`() {
        val scope = evaluator.responsesScope(mapOf("responses" to ordersWithQuantities(1, 1)))

        assertEquals(2L, evaluator.evaluate("sum(responses[*].body.items[*].quantity)", scope))
    }

    @Test
    fun `refuses to flatten something that is not an array`() {
        val scope = evaluator.responseScope(response(1, 200, """{"stock":7}"""))

        assertFailsWith<ObservationExpressionException> { evaluator.evaluate("response.body.stock[*]", scope) }
    }

    @Test
    fun `reports a body that is not json as unreadable`() {
        val scope = evaluator.responseScope(response(1, 502, "<html>bad gateway</html>"))

        assertFailsWith<ObservationExpressionException> { evaluator.evaluate("response.body.stock", scope) }
    }

    @Test
    fun `says so when the path matched nothing at all`() {
        val scope = evaluator.responseScope(response(1, 200, """{"stock":7}"""))

        assertFailsWith<ObservationExpressionException> { evaluator.evaluate("response.body.missing", scope) }
    }

    private fun ordersWithQuantities(vararg quantities: Int): List<RecordedResponse> =
        quantities.mapIndexed { index, quantity ->
            response(index + 1, 201, """{"items":[{"quantity":$quantity}]}""")
        }

    private fun response(number: Int, status: Int, body: String, durationMs: Long = 10) =
        RecordedResponse(requestNumber = number, statusCode = status, durationMs = durationMs, body = body)
}
