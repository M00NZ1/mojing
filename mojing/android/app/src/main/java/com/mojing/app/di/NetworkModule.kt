package com.mojing.app.di

import com.mojing.app.BuildConfig
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.remote.BackendAssetsApi
import com.mojing.app.data.remote.BackendCharactersApi
import com.mojing.app.data.remote.BackendEncyclopediaApi
import com.mojing.app.data.remote.BackendSystemProbeApi
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.data.remote.ImageApiService
import com.mojing.app.data.remote.LlmApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideBackendWorldsApi(client: OkHttpClient, secureStorage: SecureStorage): BackendWorldsApi =
        BackendWorldsApi(client, secureStorage)

    @Provides
    @Singleton
    fun provideBackendAssetsApi(client: OkHttpClient, secureStorage: SecureStorage): BackendAssetsApi =
        BackendAssetsApi(client, secureStorage)

    @Provides
    @Singleton
    fun provideImageApiService(): ImageApiService = ImageApiService()

    @Provides
    @Singleton
    fun provideLlmApiService(): LlmApiService = LlmApiService()

    @Provides
    @Singleton
    fun provideBackendSystemProbeApi(
        client: OkHttpClient,
        secureStorage: SecureStorage,
        imageApiService: ImageApiService,
        llmApiService: LlmApiService,
    ): BackendSystemProbeApi =
        BackendSystemProbeApi(client, secureStorage, imageApiService, llmApiService)

    @Provides
    @Singleton
    fun provideBackendCharactersApi(client: OkHttpClient, secureStorage: SecureStorage): BackendCharactersApi =
        BackendCharactersApi(client, secureStorage)

    @Provides
    @Singleton
    fun provideBackendEncyclopediaApi(client: OkHttpClient, secureStorage: SecureStorage): BackendEncyclopediaApi =
        BackendEncyclopediaApi(client, secureStorage)
}
