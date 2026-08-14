package com.project.agenticreliabilitylab.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class LayeringArchitectureTests {
    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.project.agenticreliabilitylab")

    @Test
    fun `domain code does not depend on delivery or persistence layers`() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..api..",
                "..application..",
                "..infrastructure..",
                "..adapter..",
                "..http..",
            )
            .check(productionClasses)
    }

    @Test
    fun `api code does not access jdbc infrastructure directly`() {
        noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .check(productionClasses)
    }

    @Test
    fun `application code does not depend on delivery code`() {
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..api..")
            .check(productionClasses)
    }

    @Test
    fun `application services depend on persistence ports not jdbc adapters`() {
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .haveNameMatching(".*\\.Jdbc[A-Za-z0-9_]*")
            .check(productionClasses)
    }

    @Test
    fun `application code depends on configuration ports not adapter implementations`() {
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..infrastructure..", "..profile..")
            .check(productionClasses)
    }

    @Test
    fun `analysis application uses source ports instead of source jdbc repositories`() {
        noClasses()
            .that().resideInAPackage("..analysis.application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..experiment.infrastructure..", "..targetspec.infrastructure..")
            .check(productionClasses)
    }

    @Test
    fun `target test application uses the workload lease port`() {
        noClasses()
            .that().resideInAPackage("..targetspec.application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..experiment.infrastructure..")
            .check(productionClasses)
    }
}
