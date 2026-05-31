package com.aetheria.cipher.di

import android.content.Context
import com.aetheria.cipher.actions.ActionExecutor
import com.aetheria.cipher.actions.IntentHandler
import com.aetheria.cipher.actions.SmsHandler
import com.aetheria.cipher.actions.SystemSettingHandler
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
    fun provideSystemSettingHandler(
        @ApplicationContext context: Context,
        shizukuBridge: ShizukuBridge
    ): SystemSettingHandler {
        return SystemSettingHandler(context, shizukuBridge)
    }

    @Provides
    @Singleton
    fun provideSmsHandler(
        @ApplicationContext context: Context,
        shizukuBridge: ShizukuBridge
    ): SmsHandler {
        return SmsHandler(context, shizukuBridge)
    }

    @Provides
    @Singleton
    fun provideIntentHandler(
        @ApplicationContext context: Context,
        shizukuBridge: ShizukuBridge
    ): IntentHandler {
        return IntentHandler(context, shizukuBridge)
    }

    @Provides
    @Singleton
    fun provideActionExecutor(
        @ApplicationContext context: Context,
        shizukuBridge: ShizukuBridge
    ): ActionExecutor {
        return ActionExecutor(context, shizukuBridge)
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
