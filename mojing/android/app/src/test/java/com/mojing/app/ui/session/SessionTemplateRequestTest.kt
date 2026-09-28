package com.mojing.app.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTemplateRequestTest {
    @Test
    fun requestedTemplateUsesExactDatabaseRowEvenWhenDefaultExists() {
        assertEquals(InitialWorldTemplateRequest(2L, null), initialWorldTemplateRequest(2L, "star-sea"))
    }

    @Test
    fun missingRequestedRowDoesNotFallBackToTheSavedDefault() {
        assertEquals(InitialWorldTemplateRequest(9L, null), initialWorldTemplateRequest(9L, "star-sea"))
    }

    @Test
    fun savedDefaultUsesStableTemplateId() {
        assertEquals(InitialWorldTemplateRequest(null, "star-sea"), initialWorldTemplateRequest(null, " star-sea "))
    }

    @Test
    fun customOrEmptyDefaultDoesNotRequestAWorld() {
        assertEquals(InitialWorldTemplateRequest(null, null), initialWorldTemplateRequest(null, "custom"))
        assertEquals(InitialWorldTemplateRequest(null, null), initialWorldTemplateRequest(null, ""))
    }
}
