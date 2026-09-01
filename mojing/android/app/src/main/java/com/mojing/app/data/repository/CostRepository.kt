package com.mojing.app.data.repository

import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.entity.CostRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CostRepository @Inject constructor(
    private val costRecordDao: CostRecordDao
) {
    suspend fun getRecent(): List<CostRecordEntity> = costRecordDao.getRecent()
    suspend fun getTotalCost(): Double? = costRecordDao.getTotalCost()
    suspend fun insert(entity: CostRecordEntity): Long = costRecordDao.insert(entity)
}
