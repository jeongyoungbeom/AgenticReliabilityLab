package com.project.agenticreliabilitylab.targetdiscovery.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class PilotTestTemplateFactoryTests {
    private val mapper = ObjectMapper()
    private val factory = PilotTestTemplateFactory(mapper)

    @Test
    fun `payment recovery template has a bounded fault and a fresh post-release order`() {
        val document = mapper.readTree(factory.document(PilotTestTemplateFactory.PAYMENT_FAILURE_RECOVERY, 1))
        val workload = document.path("workload")

        assertEquals("RETRY_RECOVERY", document.path("category").asString())
        assertEquals("PAYMENT_FAILURE", workload[1].path("faultType").asString())
        assertEquals("next-1", workload[1].path("scope").asString())
        assertEquals(30000, workload[1].path("ttl").asInt())
        assertEquals("RELEASE_FAULT", workload[3].path("kind").asString())
        assertEquals("createRecoveryOrder", workload[4].path("name").asString())
        assertTrue(workload[5].path("call").path("body").path("orderId").asString().contains("createRecoveryOrder"))
    }

    @Test
    fun `concurrency template keeps the required 20 by 3 bounds in its approved document`() {
        val document = mapper.readTree(factory.document(PilotTestTemplateFactory.ORDER_CONCURRENCY, 1))
        val step = document.path("workload")[0]

        assertEquals(20, step.path("requestCount").asInt())
        assertEquals(20, step.path("concurrency").asInt())
        assertEquals(3, document.path("policy").path("trials").asInt())
        assertEquals("EACH_TRIAL", document.path("policy").path("cleanupTiming").asString())
    }
}
