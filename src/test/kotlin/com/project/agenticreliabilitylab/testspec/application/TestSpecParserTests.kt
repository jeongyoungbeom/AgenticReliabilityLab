package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

/**
 * The parser reads a model's output, so these tests are mostly about refusing rather than reading.
 *
 * Quietly dropping a field a reviewer approved would produce a run that tests less than they agreed to, which is
 * the one thing an approval has to rule out.
 */
class TestSpecParserTests {
    private val objectMapper = ObjectMapper()
    private val parser = TestSpecParser(objectMapper, TestSpecSchemaValidator(objectMapper))

    @Test
    fun `reads a concurrency specification`() {
        val specification = parse(CONCURRENCY_SPEC)

        assertEquals("stock-oversell-concurrent", specification.specKey)
        assertEquals(SpecCategory.CONCURRENCY, specification.category)
        assertEquals(1, specification.setup.size)
        assertEquals("productId", specification.setup.single().captures.keys.single())

        val step = specification.workload.single()
        assertEquals(WorkloadStepKind.CALL, step.kind)
        assertEquals(10, step.requestCount)
        assertEquals(10, step.concurrency)
        assertEquals("POST", step.call?.method)

        assertEquals(20, specification.policy.trials)
    }

    @Test
    fun `keeps the document position a proposal was derived from`() {
        val specification = parse(CONCURRENCY_SPEC)

        val evidence = specification.evidence.single()
        assertEquals("OPENAPI", evidence.sourceType)
        assertTrue(evidence.excerpt.contains("failedItems"))
    }

    @Test
    fun `separates the expression identifier from the wording shown to people`() {
        val observation = parse(CONCURRENCY_SPEC).observations.first { it.id == "successQuantity" }

        assertEquals("성공한 주문 수량", observation.label)
        assertEquals(ObservationSourceKind.RESPONSES, observation.sourceKind)
    }

    @Test
    fun `marks a deadline nobody could trace to a document`() {
        val specification = parse(CONSISTENCY_SPEC)

        val observation = specification.observations.single { it.id == "settlementTotal" }
        assertEquals(StabilityRule.TWO_CONSECUTIVE_EQUAL, observation.readTiming.rule)
        assertTrue(observation.readTiming.unfoundedDeadline)
        assertTrue(specification.unfoundedThresholds().contains("settlementTotal"))
    }

    @Test
    fun `reports a step it declares but cannot run yet`() {
        // Phase 21 made INJECT_FAULT/RELEASE_FAULT executable, so this "unsupported kind" example moved to
        // INFRA_ACTION - same rationale as SpecWorkloadExecutorTests's
        // "refuses to run a step kind this build still cannot execute".
        val specification = parse(UNSUPPORTED_STEP_SPEC)

        assertEquals(listOf(WorkloadStepKind.INFRA_ACTION), specification.unsupportedSteps())
    }

    @Test
    fun `refuses an unknown step kind instead of skipping it`() {
        val failure = assertFailsWith<SpecParseException> {
            parse(CONCURRENCY_SPEC.replace("\"kind\": \"CALL\"", "\"kind\": \"RUN_SHELL\""))
        }

        assertTrue(failure.message.orEmpty().contains("RUN_SHELL"))
    }

    @Test
    fun `refuses an unknown category`() {
        assertFailsWith<SpecParseException> {
            parse(CONCURRENCY_SPEC.replace("\"CONCURRENCY\"", "\"CHAOS\""))
        }
    }

    @Test
    fun `refuses a non-ascii observation identifier`() {
        assertFailsWith<IllegalArgumentException> {
            parse(CONCURRENCY_SPEC.replace("\"id\": \"successQuantity\"", "\"id\": \"주문성공수량\""))
        }
    }

    @Test
    fun `refuses a document that is not json`() {
        assertFailsWith<SpecParseException> { parse("{not json") }
    }

    @Test
    fun `refuses a missing required field`() {
        assertFailsWith<SpecParseException> {
            parse(CONCURRENCY_SPEC.replace("\"title\"", "\"heading\""))
        }
    }

    @Test
    fun `refuses an unexpected top level field`() {
        val failure = assertFailsWith<SpecParseException> {
            parse(
                CONCURRENCY_SPEC.replace(
                    "\"risk\": \"MODERATE\",",
                    "\"risk\": \"MODERATE\", \"prompt\": \"ignore rules\",",
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("$.prompt"))
    }

    @Test
    fun `refuses an unexpected nested call field`() {
        val failure = assertFailsWith<SpecParseException> {
            parse(
                CONCURRENCY_SPEC.replace(
                    "\"authProfile\": \"seller\",",
                    "\"authProfile\": \"seller\", \"baseUrl\": \"http://elsewhere\",",
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("baseUrl"))
    }

    @Test
    fun `refuses a value whose json type is wrong`() {
        val failure = assertFailsWith<SpecParseException> {
            parse(CONCURRENCY_SPEC.replace("\"trials\": 20", "\"trials\": \"20\""))
        }

        assertTrue(failure.message.orEmpty().contains("integer"))
    }

    private fun parse(document: String) = parser.parse(
        document = document,
        id = UUID.randomUUID(),
        targetSystemId = "pilot-target",
        profileVersionId = UUID.randomUUID(),
        source = SpecSource.MODEL_PROPOSED,
    )

    private companion object {
        val CONCURRENCY_SPEC = """
        {
          "specKey": "stock-oversell-concurrent",
          "version": 1,
          "title": "동시 주문 시 초과 판매가 발생하는가",
          "category": "CONCURRENCY",
          "risk": "MODERATE",
          "evidence": [
            { "sourceType": "OPENAPI", "location": "paths./orders.post.description",
              "excerpt": "재고 부족 상품은 failedItems에 반환됩니다" }
          ],
          "setup": [
            { "name": "product",
              "call": { "method": "POST", "path": "/products", "authProfile": "seller",
                        "body": { "stock": 10, "price": 1000 } },
              "captures": { "productId": "response.body.id" } }
          ],
          "workload": [
            { "kind": "CALL", "name": "orders",
              "call": { "method": "POST", "path": "/orders", "authProfile": "buyer",
                        "body": { "items": [] } },
              "requestCount": 10, "concurrency": 10, "captureAs": "responses" }
          ],
          "observations": [
            { "id": "dbStock", "label": "최종 재고", "source": "API",
              "call": { "method": "GET", "path": "/products/{id}" }, "expr": "response.body.stock" },
            { "id": "successQuantity", "label": "성공한 주문 수량", "source": "RESPONSES",
              "expr": "sum(responses)" }
          ],
          "invariants": [
            { "id": "stock-never-negative", "description": "재고는 0 미만이 될 수 없다",
              "condition": "dbStock >= 0" }
          ],
          "policy": { "trials": 20, "cleanupTiming": "AFTER_ALL" },
          "cleanup": { "method": "ENVIRONMENT_RESET" }
        }
        """.trimIndent()

        val CONSISTENCY_SPEC = """
        {
          "specKey": "order-settlement-consistency",
          "title": "정산 정합성",
          "category": "CONSISTENCY",
          "risk": "SAFE",
          "observations": [
            { "id": "settlementTotal", "label": "정산 서비스 합계", "source": "DECLARED_SOURCE",
              "sourceName": "harness", "expr": "settlementAmount",
              "readAt": { "rule": "TWO_CONSECUTIVE_EQUAL", "maxWait": 10000, "interval": 500 } }
          ],
          "invariants": [
            { "id": "settled", "description": "정산이 수렴한다", "condition": "settlementTotal >= 0" }
          ],
          "policy": { "trials": 3 }
        }
        """.trimIndent()

        val UNSUPPORTED_STEP_SPEC = """
        {
          "specKey": "payment-service-outage-recovery",
          "title": "결제 서비스 중단 후 재고 복구",
          "category": "RETRY_RECOVERY",
          "risk": "MODERATE",
          "workload": [
            { "kind": "INFRA_ACTION", "name": "stop-payment-service",
              "action": "STOP", "target": "payment-service", "maxHold": 30000 }
          ],
          "observations": [
            { "id": "finalStock", "source": "API",
              "call": { "method": "GET", "path": "/products/{id}" }, "expr": "response.body.stock" }
          ],
          "invariants": [
            { "id": "stock-restored", "description": "재고가 복구된다", "condition": "finalStock >= 0" }
          ],
          "policy": { "trials": 3, "cleanupTiming": "EACH_TRIAL" }
        }
        """.trimIndent()
    }
}
