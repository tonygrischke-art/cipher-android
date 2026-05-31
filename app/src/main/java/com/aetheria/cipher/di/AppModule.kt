package com.aetheria.cipher.di

import android.content.Context
import com.aetheria.cipher.actions.ActionExecutor
import com.aetheria.cipher.brain.BrainRouter
import com.aetheria.cipher.brain.GroqClient
import com.aetheria.cipher.brain.LiteRTEngine
import com.aetheria.cipher.context.ContextEngine
import com.aetheria.cipher.shizuku.ShizukuBridge
import com.aetheria.cipher.voice.VoicePipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContextEngine(@ApplicationContext context: Context): ContextEngine {
        return ContextEngine(context)
    }

    @Provides
    @Singleton
    fun provideLiteRTEngine(@ApplicationContext context: Context): LiteRTEngine {
        return LiteRTEngine(context)
    }

    @Provides
    @Singleton
    fun provideGroqClient(): GroqClient {
        return GroqClient()
    }

    @Provides
    @Singleton
    fun provideShizukuBridge(): ShizukuBridge {
        return ShizukuBridge()
    }

    @Provides
    @Singleton
    fun provideActionExecutor(shizukuBridge: ShizukuBridge): ActionExecutor {
        return ActionExecutor(shizukuBridge)
    }

    @Provides
    @Singleton
    fun provideBrainRouter(
        liteRTEngine: LiteRTEngine,
        groqClient: GroqClient,
        actionExecutor: ActionExecutor
    ): BrainRouter {
        return BrainRouter(liteRTEngine, groqClient, actionExecutor)
    }

    @Provides
    @Singleton
    fun provideVoicePipeline(@ApplicationContext context: Context): VoicePipeline {
        return VoicePipeline(context)
    }
}
