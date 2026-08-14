package com.project.agenticreliabilitylab.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Injectable sources of time and identifiers for deterministic lifecycle tests. */
@Configuration(proxyBeanMethods = false)
class RuntimeConfiguration {
    @Bean
    fun applicationClock(): Clock = Clock.systemUTC()
}

fun interface IdentifierGenerator {
    fun next(): UUID
}

@Component
class UuidIdentifierGenerator : IdentifierGenerator {
    override fun next(): UUID = UUID.randomUUID()
}
