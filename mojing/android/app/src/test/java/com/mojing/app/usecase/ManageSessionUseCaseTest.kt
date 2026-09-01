package com.mojing.app.usecase

import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.domain.usecase.ManageSessionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ManageSessionUseCaseTest {

    private val sessionDao = mockk<SessionDao>(relaxed = true)
    private val useCase = ManageSessionUseCase(sessionDao)

    @Test
    fun insertReturnsId() = runTest {
        val entity = SessionEntity(title = "新会话")
        coEvery { sessionDao.insert(entity) } returns 1L
        val result = useCase.insert(entity)
        assertEquals(1L, result)
    }

    @Test
    fun getByIdReturnsEntity() = runTest {
        val entity = SessionEntity(id = 5, title = "测试会话")
        coEvery { sessionDao.getById(5) } returns entity
        val result = useCase.getById(5)
        assertEquals("测试会话", result?.title)
    }

    @Test
    fun deleteCallsDao() = runTest {
        useCase.delete(3)
        coVerify { sessionDao.delete(3) }
    }
}
