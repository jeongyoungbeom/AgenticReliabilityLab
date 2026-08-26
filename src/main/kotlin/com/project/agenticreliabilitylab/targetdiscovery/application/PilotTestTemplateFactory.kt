package com.project.agenticreliabilitylab.targetdiscovery.application

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Fixed templates for the P2–P4 pilot.
 *
 * This is intentionally data-only: Swagger proves the operation exists, while the human-reviewed template below
 * supplies the workflow and verdict. No model output and no Swagger request body becomes execution authority.
 */
@Component
@Suppress("TooManyFunctions", "MagicNumber", "MaxLineLength") // One fixed template catalogue keeps the approved P2–P4 scope auditable.
class PilotTestTemplateFactory(
    private val objectMapper: ObjectMapper,
) {
    fun document(candidateId: String, version: Int): String = objectMapper.writeValueAsString(
        when (candidateId) {
            AVAILABILITY -> availability(version)
            PRODUCT_CREATE -> productCreate(version)
            ORDER_WORKFLOW -> orderWorkflow(version)
            PAYMENT_SUCCESS -> paymentSuccess(version)
            ORDER_IDEMPOTENCY -> orderIdempotency(version)
            ORDER_CONCURRENCY -> orderConcurrency(version)
            PAYMENT_FAILURE_RECOVERY -> paymentFailureRecovery(version)
            else -> throw IllegalArgumentException("Unknown pilot candidate '$candidateId'")
        },
    )

    private fun availability(version: Int) = spec(
        key = AVAILABILITY, version = version, title = "Health와 상품 카탈로그 가용성", category = "AVAILABILITY", risk = "SAFE",
        workload = listOf(
            call("health", "GET", HEALTH_PATH),
            call("catalog", "GET", PRODUCT_PATH),
        ),
        observations = listOf(response("healthStatus", "health[*].status"), response("catalogStatus", "catalog[*].status")),
        invariants = listOf(
            invariant("healthAvailable", "health endpoint는 2xx여야 합니다", "healthStatus >= 200 && healthStatus < 300"),
            invariant("catalogAvailable", "상품 카탈로그는 2xx여야 합니다", "catalogStatus >= 200 && catalogStatus < 300"),
        ),
        cleanup = "NOT_REQUIRED",
    )

    private fun productCreate(version: Int) = spec(
        key = PRODUCT_CREATE, version = version, title = "판매자 상품 생성", category = "WORKFLOW", risk = "MODERATE",
        workload = listOf(call("createProduct", "POST", PRODUCT_PATH, SELLER, body = productBody(20))),
        observations = listOf(
            response("createStatus", "createProduct[*].status"),
            harness("productCount"),
        ),
        invariants = listOf(
            invariant("productCreated", "상품 생성은 2xx여야 합니다", "createStatus >= 200 && createStatus < 300"),
            invariant("productState", "Harness run 범위 상품 수는 하나여야 합니다", "productCount == 1"),
        ),
    )

    private fun orderWorkflow(version: Int) = spec(
        key = ORDER_WORKFLOW, version = version, title = "상품 생성 → 구매자 주문", category = "WORKFLOW", risk = "MODERATE",
        setup = listOf(productSetup()),
        workload = listOf(call("createOrder", "POST", ORDER_PATH, BUYER, orderBody("{{setup.product.productId}}"), headers = idempotency("workflow"))),
        observations = listOf(response("orderStatus", "createOrder[*].status"), harness("orderCount")),
        invariants = listOf(
            invariant("orderCreated", "주문 요청은 2xx여야 합니다", "orderStatus >= 200 && orderStatus < 300"),
            invariant("orderState", "Harness run 범위 주문 수는 하나여야 합니다", "orderCount == 1"),
        ),
    )

    private fun paymentSuccess(version: Int) = spec(
        key = PAYMENT_SUCCESS, version = version, title = "결제 성공 workflow", category = "WORKFLOW", risk = "MODERATE",
        setup = listOf(productSetup(), orderSetup("order")),
        workload = listOf(call("completePayment", "POST", PAYMENT_PATH, body = paymentBody("{{setup.order.orderId}}", "SUCCESS"))),
        observations = listOf(response("paymentStatus", "completePayment[*].status"), harness("completedPaymentCount")),
        invariants = listOf(
            invariant("paymentAccepted", "결제 callback은 2xx여야 합니다", "paymentStatus >= 200 && paymentStatus < 300"),
            invariant("paymentCompleted", "Harness가 완료 결제 하나를 관측해야 합니다", "completedPaymentCount == 1"),
        ),
    )

    private fun orderIdempotency(version: Int) = spec(
        key = ORDER_IDEMPOTENCY, version = version, title = "동일 주문 idempotency", category = "IDEMPOTENCY", risk = "MODERATE",
        setup = listOf(productSetup()),
        workload = listOf(
            call(
                "duplicateOrder", "POST", ORDER_PATH, BUYER, orderBody("{{setup.product.productId}}"),
                headers = idempotency("same-intention"), requestCount = 2,
            ),
        ),
        observations = listOf(harness("orderCount")),
        invariants = listOf(invariant("oneOrderOnly", "동일 body와 key는 주문 하나만 만들어야 합니다", "orderCount == 1")),
    )

    private fun orderConcurrency(version: Int) = spec(
        key = ORDER_CONCURRENCY, version = version, title = "주문 동시성 20 × 3", category = "CONCURRENCY", risk = "MODERATE",
        setup = listOf(productSetup(stock = 40)),
        workload = listOf(
            call(
                "parallelOrders", "POST", ORDER_PATH, BUYER, orderBody("{{setup.product.productId}}"),
                headers = mapOf(IDEMPOTENCY_KEY to "arl-concurrency-{{runId}}-{{requestNumber}}"),
                requestCount = 20, concurrency = 20,
            ),
        ),
        observations = listOf(harness("orderCount")),
        invariants = listOf(invariant("allConcurrentOrdersRecorded", "20개 병렬 주문이 모두 run 범위에서 관측되어야 합니다", "orderCount == 20")),
        trials = 3,
        cleanupTiming = "EACH_TRIAL",
        stopPolicy = "RUN_ALL",
    )

    private fun paymentFailureRecovery(version: Int) = spec(
        key = PAYMENT_FAILURE_RECOVERY, version = version, title = "결제 장애 주입과 복구", category = "RETRY_RECOVERY", risk = "MODERATE",
        setup = listOf(productSetup(stock = 2)),
        workload = listOf(
            call(
                "createFailedOrder", "POST", ORDER_PATH, BUYER, orderBody("{{setup.product.productId}}"),
                headers = idempotency("failure"), captures = mapOf("orderId" to "response.body.orderId"),
            ),
            linkedMapOf("kind" to "INJECT_FAULT", "name" to "paymentFailure", "faultType" to "PAYMENT_FAILURE", "scope" to "next-1", "ttl" to 30000),
            call("forcedFailure", "POST", PAYMENT_PATH, body = paymentBody("{{workload.createFailedOrder.orderId}}", "SUCCESS")),
            linkedMapOf("kind" to "RELEASE_FAULT", "name" to "releasePaymentFailure", "handle" to "{{workload.paymentFailure.faultId}}"),
            call(
                "createRecoveryOrder", "POST", ORDER_PATH, BUYER, orderBody("{{setup.product.productId}}"),
                headers = idempotency("recovery"), captures = mapOf("orderId" to "response.body.orderId"),
            ),
            call("recoveredPayment", "POST", PAYMENT_PATH, body = paymentBody("{{workload.createRecoveryOrder.orderId}}", "SUCCESS")),
        ),
        observations = listOf(
            response("forcedFailureStatus", "forcedFailure[*].status"),
            response("recoveredPaymentStatus", "recoveredPayment[*].status"),
            harness("paymentCount"),
            harness("failedPaymentCount"),
            harness("completedPaymentCount"),
            harness("activeFaultCount"),
        ),
        invariants = listOf(
            invariant("failureDelivered", "장애가 걸린 callback은 transport 2xx로 수락되어야 합니다", "forcedFailureStatus >= 200 && forcedFailureStatus < 300"),
            invariant("recoveryDelivered", "fault 해제 뒤 callback은 transport 2xx로 수락되어야 합니다", "recoveredPaymentStatus >= 200 && recoveredPaymentStatus < 300"),
            invariant("failureAndRecoveryObserved", "실패 하나와 완료 하나가 각각 관측되어야 합니다", "paymentCount == 2 && failedPaymentCount == 1 && completedPaymentCount == 1"),
            invariant("faultReleased", "다음 쓰기 전에 active fault가 없어야 합니다", "activeFaultCount == 0"),
        ),
    )

    private fun spec(
        key: String,
        version: Int,
        title: String,
        category: String,
        risk: String,
        setup: List<Map<String, Any>> = emptyList(),
        workload: List<Map<String, Any>>,
        observations: List<Map<String, Any>>,
        invariants: List<Map<String, Any>>,
        trials: Int = 1,
        cleanupTiming: String = "AFTER_ALL",
        stopPolicy: String = "STOP_ON_FIRST_VIOLATION",
        cleanup: String = "ENVIRONMENT_RESET",
    ): Map<String, Any> = linkedMapOf(
        "specKey" to "pilot-$key",
        "version" to version,
        "title" to title,
        "category" to category,
        "risk" to risk,
        "evidence" to listOf(linkedMapOf("sourceType" to "PILOT_TEMPLATE", "location" to "DESIGN4 P2-P4", "excerpt" to "Allowlist-filtered fixed SideProject pilot template")),
        "setup" to setup,
        "workload" to workload,
        "observations" to observations,
        "invariants" to invariants,
        "policy" to linkedMapOf("trials" to trials, "aggregation" to "ANY_VIOLATION_FAILS", "stopPolicy" to stopPolicy, "cleanupTiming" to cleanupTiming),
        "cleanup" to linkedMapOf("method" to cleanup),
    )

    private fun productSetup(stock: Int = 20) = linkedMapOf(
        "name" to "product",
        "call" to callValue("POST", PRODUCT_PATH, SELLER, productBody(stock)),
        "captures" to mapOf("productId" to "response.body.productId"),
    )

    private fun orderSetup(name: String) = linkedMapOf(
        "name" to name,
        "call" to callValue("POST", ORDER_PATH, BUYER, orderBody("{{setup.product.productId}}"), idempotency(name)),
        "captures" to mapOf("orderId" to "response.body.orderId"),
    )

    private fun call(
        name: String, method: String, path: String, authProfile: String? = null, body: Map<String, Any>? = null,
        headers: Map<String, String> = emptyMap(), requestCount: Int = 1, concurrency: Int = 1,
        captures: Map<String, String> = emptyMap(),
    ): Map<String, Any> = linkedMapOf<String, Any>("kind" to "CALL", "name" to name, "call" to callValue(method, path, authProfile, body, headers)).apply {
        if (requestCount != 1) put("requestCount", requestCount)
        if (concurrency != 1) put("concurrency", concurrency)
        if (captures.isNotEmpty()) put("captures", captures)
    }

    private fun callValue(
        method: String, path: String, authProfile: String? = null, body: Map<String, Any>? = null,
        headers: Map<String, String> = emptyMap(),
    ): Map<String, Any> = linkedMapOf<String, Any>("method" to method, "path" to path).apply {
        if (authProfile != null) put("authProfile", authProfile)
        if (headers.isNotEmpty()) put("headers", headers)
        if (body != null) put("body", body)
    }

    private fun response(id: String, expression: String): Map<String, Any> =
        linkedMapOf("id" to id, "source" to "RESPONSES", "expr" to expression)

    private fun harness(field: String): Map<String, Any> =
        linkedMapOf("id" to field, "source" to "DECLARED_SOURCE", "sourceName" to "harness", "expr" to field)

    private fun invariant(id: String, description: String, condition: String): Map<String, Any> =
        linkedMapOf("id" to id, "description" to description, "condition" to condition)

    private fun productBody(stock: Int) = linkedMapOf<String, Any>(
        "name" to "ARL pilot {{runId}}",
        "description" to "Agentic Reliability Lab fixed pilot fixture",
        "price" to 1000,
        "stock" to stock,
        "category" to "FLOWERS",
        "labels" to listOf("NEW"),
    )

    private fun orderBody(productId: String) = linkedMapOf<String, Any>("items" to listOf(linkedMapOf("productId" to productId, "quantity" to 1)))

    private fun paymentBody(orderId: String, result: String) = linkedMapOf<String, Any>(
        "orderId" to orderId,
        "result" to result,
        "pgTxId" to "ARL-{{runId}}",
        "amount" to 1000,
    )

    private fun idempotency(intent: String): Map<String, String> = mapOf(IDEMPOTENCY_KEY to "arl-$intent-{{runId}}")

    companion object {
        const val AVAILABILITY = "availability"
        const val PRODUCT_CREATE = "product-create"
        const val ORDER_WORKFLOW = "order-workflow"
        const val PAYMENT_SUCCESS = "payment-success"
        const val ORDER_IDEMPOTENCY = "order-idempotency"
        const val ORDER_CONCURRENCY = "order-concurrency"
        const val PAYMENT_FAILURE_RECOVERY = "payment-failure-recovery"
        const val SELLER = "seller"
        const val BUYER = "buyer"
        const val HARNESS = "harness"
        const val HEALTH_PATH = "/actuator/health"
        const val PRODUCT_PATH = "/api/products"
        const val ORDER_PATH = "/api/orders"
        const val PAYMENT_PATH = "/api/payments/webhook"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"

        /**
         * READY is a promise that a template can be validated and executed, not just that Swagger happened to
         * mention one endpoint. Keep its Profile/Harness dependencies beside the fixed template catalogue.
         */
        fun requirements(candidateId: String): PilotTemplateRequirements = when (candidateId) {
            AVAILABILITY -> PilotTemplateRequirements(
                requiredAuthProfiles = setOf(HARNESS),
                requiredHarnessFields = emptySet(),
                stateChanging = false,
            )
            PRODUCT_CREATE -> PilotTemplateRequirements(
                requiredAuthProfiles = setOf(SELLER, HARNESS),
                requiredHarnessFields = setOf("productCount"),
                stateChanging = true,
            )
            ORDER_WORKFLOW -> PilotTemplateRequirements(
                requiredAuthProfiles = setOf(SELLER, BUYER, HARNESS),
                requiredHarnessFields = setOf("orderCount"),
                stateChanging = true,
            )
            PAYMENT_SUCCESS -> PilotTemplateRequirements(
                requiredAuthProfiles = setOf(SELLER, BUYER, HARNESS),
                requiredHarnessFields = setOf("completedPaymentCount"),
                stateChanging = true,
            )
            ORDER_IDEMPOTENCY -> PilotTemplateRequirements(
                requiredAuthProfiles = setOf(SELLER, BUYER, HARNESS),
                requiredHarnessFields = setOf("orderCount"),
                stateChanging = true,
            )
            ORDER_CONCURRENCY -> PilotTemplateRequirements(
                requiredAuthProfiles = setOf(SELLER, BUYER, HARNESS),
                requiredHarnessFields = setOf("orderCount"),
                stateChanging = true,
            )
            PAYMENT_FAILURE_RECOVERY -> PilotTemplateRequirements(
                requiredAuthProfiles = setOf(SELLER, BUYER, HARNESS),
                requiredHarnessFields = setOf("paymentCount", "failedPaymentCount", "completedPaymentCount", "activeFaultCount"),
                stateChanging = true,
                requiredFaultType = "PAYMENT_FAILURE",
            )
            else -> throw IllegalArgumentException("Unknown pilot candidate '$candidateId'")
        }

        fun requiredAuthProfiles(candidateId: String): Set<String> = requirements(candidateId).requiredAuthProfiles
    }
}

data class PilotTemplateRequirements(
    val requiredAuthProfiles: Set<String>,
    val requiredHarnessFields: Set<String>,
    val stateChanging: Boolean,
    val requiredFaultType: String? = null,
)
