package com.aetheria.vance.di

import android.content.Context
import com.aetheria.vance.actions.ActionExecutor
import com.aetheria.vance.actions.IntentHandler
import com.aetheria.vance.actions.SmsHandler
import com.aetheria.vance.actions.SystemSettingHandler
import com.aetheria.vance.brain.BrainRouter
import com.aetheria.vance.brain.GroqClient
import com.aetheria.vance.brain.LiteRTEngine
import com.aetheria.vance.brain.NpuBridge
import com.aetheria.vance.context.ContextEngine
import com.aetheria.vance.context.MemoryStore
import com.aetheria.vance.context.RoutineEngine
import com.aetheria.vance.shizuku.ShizukuBridge
import com.aetheria.vance.voice.VoicePipeline
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
    fun provideNpuBridge(): NpuBridge {
        return NpuBridge()
    }

    @Provides
    @Singleton
    fun provideLiteRTEngine(
        @ApplicationContext context: Context,
        npuBridge: NpuBridge
    ): LiteRTEngine {
        // initialize() is safe — never crashes. Returns false if NPU unavailable.
        val npuReady = npuBridge.initialize()
        return LiteRTEngine(
            context = context,
            npuBridge = if (npuReady) npuBridge else null
        )
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
    fun provideMemoryStore(@ApplicationContext context: Context): MemoryStore {
        return MemoryStore(context)
    }

    @Provides
    @Singleton
    fun provideRoutineEngine(
        @ApplicationContext context: Context,
        memoryStore: MemoryStore,
        actionExecutor: ActionExecutor,
        shizukuBridge: ShizukuBridge
    ): RoutineEngine {
        return RoutineEngine(context, memoryStore, actionExecutor, shizukuBridge)
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
