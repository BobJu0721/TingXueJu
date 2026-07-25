package com.aichat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSearchTest {
    private val models = listOf(
        "openrouter/free",
        "meta-llama/llama-3.3-70b-instruct",
        "qwen/qwen3-32b",
    )

    @Test
    fun blankQueryReturnsEveryModel() {
        assertEquals(models, filterModels(models, "  "))
    }

    @Test
    fun queryMatchesRegardlessOfCase() {
        assertEquals(
            listOf("meta-llama/llama-3.3-70b-instruct"),
            filterModels(models, "LLAMA"),
        )
    }

    @Test
    fun queryMatchesMiddleOfModelId() {
        assertEquals(
            listOf("meta-llama/llama-3.3-70b-instruct"),
            filterModels(models, "70b"),
        )
    }

    @Test
    fun unknownQueryReturnsEmptyList() {
        assertEquals(emptyList<String>(), filterModels(models, "missing"))
    }

    @Test
    fun manualModelCandidateRequiresANewNonBlankId() {
        assertNull(manualModelCandidate(models, "  "))
        assertNull(manualModelCandidate(models, "qwen/qwen3-32b"))
        assertEquals(
            "@cf/moonshotai/kimi-k2.6",
            manualModelCandidate(models, "  @cf/moonshotai/kimi-k2.6  "),
        )
    }
}

