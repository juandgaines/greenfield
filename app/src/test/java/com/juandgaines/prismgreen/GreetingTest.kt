package com.juandgaines.prismgreen

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class GreetingTest {

    @Test
    fun theAppKnowsItsName() {
        assertThat("prismgreen").isEqualTo("prismgreen")
    }
}
