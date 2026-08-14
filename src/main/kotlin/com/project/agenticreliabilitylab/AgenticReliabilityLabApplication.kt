package com.project.agenticreliabilitylab

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AgenticReliabilityLabApplication

fun main(args: Array<String>) {
    runApplication<AgenticReliabilityLabApplication>(*args)
}
