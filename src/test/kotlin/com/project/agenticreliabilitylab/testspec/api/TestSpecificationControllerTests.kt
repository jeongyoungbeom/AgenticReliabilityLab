package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.testspec.application.TestSpecificationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.MDC
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestSpecificationControllerTests {
    private val specifications = Mockito.mock(TestSpecificationService::class.java)
    private val access = Mockito.mock(OperatorAccessService::class.java)
    private val controller = TestSpecificationController(specifications, access, ObjectMapper())

    @AfterEach
    fun clearCorrelationId() {
        MDC.clear()
    }

    @Test
    fun `passes the Target credential session to a direct specification run`() {
        val specificationId = UUID.randomUUID()
        val credentialSessionId = "credential-session-0001"
        Mockito.`when`(access.requireExecutor(null)).thenReturn("operator")
        Mockito.`when`(
            specifications.execute(
                specificationId,
                "direct-run-0001",
                "operator",
                "missing-correlation-id",
                credentialSessionId,
            ),
        ).thenThrow(ClientRequestException("TEST_RUN_REJECTED", "test rejection"))

        val error = assertFailsWith<ClientRequestException> {
            controller.execute(specificationId, null, "direct-run-0001", credentialSessionId)
        }

        assertEquals("TEST_RUN_REJECTED", error.code)
        Mockito.verify(specifications).execute(
            specificationId,
            "direct-run-0001",
            "operator",
            "missing-correlation-id",
            credentialSessionId,
        )
    }

    @Test
    fun `passes the Target credential session to regression runs`() {
        val credentialSessionId = "credential-session-0001"
        Mockito.`when`(access.requireExecutor(null)).thenReturn("operator")
        Mockito.`when`(
            specifications.triggerRegressionRuns(
                "sideproject-local",
                "regression-0001",
                "operator",
                "missing-correlation-id",
                credentialSessionId,
            ),
        ).thenReturn(emptyList())

        controller.triggerRegressionRuns("sideproject-local", null, "regression-0001", credentialSessionId)

        Mockito.verify(specifications).triggerRegressionRuns(
            "sideproject-local",
            "regression-0001",
            "operator",
            "missing-correlation-id",
            credentialSessionId,
        )
    }
}
