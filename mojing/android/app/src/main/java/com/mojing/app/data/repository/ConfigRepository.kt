package com.mojing.app.data.repository

import com.mojing.app.data.local.dao.ConfigDao
import com.mojing.app.data.local.entity.ConfigEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepository @Inject constructor(
    private val configDao: ConfigDao
) {
    suspend fun get(key: String): ConfigEntity? = configDao.get(key)
    suspend fun set(key: String, valueJson: String) {
        configDao.set(ConfigEntity(key = key, valueJson = valueJson))
    }
    suspend fun delete(key: String) { configDao.delete(key) }
}
