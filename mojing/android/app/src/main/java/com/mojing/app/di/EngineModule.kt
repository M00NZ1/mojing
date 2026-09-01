package com.mojing.app.di

import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.AnthropicAdapter
import com.mojing.app.domain.engine.ChatEngine
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.PromptBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideAnthropicAdapter(client: okhttp3.OkHttpClient): AnthropicAdapter = AnthropicAdapter(client)

    @Provides
    @Singleton
    fun provideLlmRetry(
        llmApi: LlmApiService,
        costRecorder: CostRecorder,
    ): LlmRetry = LlmRetry(llmApi, costRecorder)

    @Provides
    @Singleton
    fun provideChatEngine(
        llmApi: LlmApiService,
        llmRetry: LlmRetry,
        costRecorder: CostRecorder,
        promptBuilder: PromptBuilder,
        anthropicAdapter: AnthropicAdapter,
    ): ChatEngine = ChatEngine(llmApi, llmRetry, costRecorder, promptBuilder, anthropicAdapter)
}