package com.mojing.app.ui

import com.mojing.app.data.local.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to existing production owners for opt-in emulator App checks. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PrototypeDatabaseEntryPoint {
    fun processor(): com.mojing.app.domain.generation.GenerationQueueProcessor
    fun saveEntry(): com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
    fun database(): AppDatabase
}
