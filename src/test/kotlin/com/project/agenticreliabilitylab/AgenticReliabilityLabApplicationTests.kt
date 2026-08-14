package com.project.agenticreliabilitylab

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.TargetSystemRepository
import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyParametersCodec
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AgenticReliabilityLabApplicationTests {
    @Autowired
    private lateinit var targetSystemRepository: TargetSystemRepository

    @Autowired
    private lateinit var parametersCodec: StockConcurrencyParametersCodec

    @Test
    fun `context loads, migrates the database, and bootstraps the configured target`() {
        val target = targetSystemRepository.findById("contract-test-target")

        assertNotNull(target)
        assertEquals("HTTP_TARGET", target.adapterType)
        assertEquals(IdentityVerificationStatus.CONFIGURATION_ONLY, target.identityVerification)
    }

    @Test
    fun `stock concurrency parameters use one canonical and validated persistence format`() {
        val parameters = StockConcurrencyParameters(
            stock = 10,
            requestCount = 20,
            concurrency = 5,
            quantityPerRequest = 1,
        )

        assertEquals(
            "{\"stock\":10,\"requestCount\":20,\"concurrency\":5,\"quantityPerRequest\":1}",
            parametersCodec.encode(parameters),
        )
        assertEquals(
            parameters,
            parametersCodec.decode("""{"quantityPerRequest":1,"concurrency":5,"requestCount":20,"stock":10}"""),
        )
    }
}
