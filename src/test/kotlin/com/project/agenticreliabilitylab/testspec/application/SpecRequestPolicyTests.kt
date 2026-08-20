package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpecRequestPolicyTests {
    @Test
    fun `matches a captured call path to a registered single segment template`() {
        assertTrue(
            SpecRequestPolicy.registeredPathMatches(
                "/products/{id}",
                "/products/{{setup.product.productId}}",
            ),
        )
    }

    @Test
    fun `accepts one resolved segment inside the approved call template`() {
        val uri = SpecRequestPolicy.requireSafeResolvedPath(
            "/products/{{setup.product.productId}}",
            "/products/p-9",
        )

        assertEquals("/products/p-9", uri.path)
    }

    @Test
    fun `refuses a captured query or traversal after substitution`() {
        assertFailsWith<SpecExecutionException> {
            SpecRequestPolicy.requireSafeResolvedPath(
                "/products/{{setup.product.productId}}",
                "/products/p-9?admin=true",
            )
        }
        assertFailsWith<SpecExecutionException> {
            SpecRequestPolicy.requireSafeResolvedPath(
                "/products/{{setup.product.productId}}",
                "/products/../admin",
            )
        }
        assertFailsWith<SpecExecutionException> {
            SpecRequestPolicy.requireSafeResolvedPath(
                "/products/{{setup.product.productId}}",
                "/products/p-9%2Fadmin",
            )
        }
    }
}
