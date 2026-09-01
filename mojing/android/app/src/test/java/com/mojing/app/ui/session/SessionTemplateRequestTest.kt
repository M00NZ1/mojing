package com.mojing.app.ui.session

import com.mojing.app.data.local.entity.WorldTemplateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTemplateRequestTest {
    private val templates = listOf(
        WorldTemplateEntity(id = 1L, templateId = "old-city", label = "旧城"),
        WorldTemplateEntity(id = 2L, templateId = "star-sea", label = "星海"),
    )

    @Test
    fun requestedTemplateSelectsExactDatabaseRow() {
        assertEquals("星海", findRequestedWorldTemplate(templates, 2L)?.label)
    }

    @Test
    fun missingOrEmptyRequestDoesNotSelectAnotherTemplate() {
        assertNull(findRequestedWorldTemplate(templates, 9L))
        assertNull(findRequestedWorldTemplate(templates, null))
    }

    @Test
    fun savedDefaultSelectsTemplateByStableTemplateId() {
        assertEquals("星海", findDefaultWorldTemplate(templates, " star-sea ")?.label)
    }

    @Test
    fun customOrMissingDefaultDoesNotPretendAnotherTemplateWasSelected() {
        assertNull(findDefaultWorldTemplate(templates, "custom"))
        assertNull(findDefaultWorldTemplate(templates, "deleted-world"))
        assertNull(findDefaultWorldTemplate(templates, ""))
    }
}
