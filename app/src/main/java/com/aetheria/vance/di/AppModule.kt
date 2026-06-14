package com.aetheria.vance.di

import android.content.Context
import com.aetheria.vance.actions.ActionExecutor
import com.aetheria.vance.actions.IntentHandler
import com.aetheria.vance.actions.SmsHandler
import com.aetheria.vance.actions.SystemSettingHandler
import com.aetheria.vance.brain.*
import com.aetheria.vance.context.ContextEngine
import com.aetheria.vance.context.MemoryStore
import com.aetheria.vance.context.MemoryDao
import com.aetheria.vance.context.LoraCheckpointDao
import com.aetheria.vance.context.RoutineEngine
import com.aetheria.vance.npu.NpuEngine
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
    fun provideFastLlmClient(): FastLlmClient {
        return FastLlmClient()
    }

    @Provides
    @Singleton
    fun provideMainLlmClient(): MainLlmClient {
        return MainLlmClient()
    }

    @Provides
    @Singleton
    fun provideNpuEngine(@ApplicationContext context: Context): NpuEngine {
        return NpuEngine(context)
    }

    @Provides
    @Singleton
    fun provideMemoryStore(@ApplicationContext context: Context): MemoryStore {
        return MemoryStore(context)
    }

    @Provides
    @Singleton
    fun provideMemoryDao(memoryStore: MemoryStore): MemoryDao {
        return memoryStore.memoryDao
    }

    @Provides
    @Singleton
    fun provideLoraCheckpointDao(memoryStore: MemoryStore): LoraCheckpointDao {
        return memoryStore.loraCheckpointDao
    }

    @Provides
    @Singleton
    fun provideMemoryFineTuner(
        memoryDao: MemoryDao,
        loraCheckpointDao: LoraCheckpointDao,
        @ApplicationContext context: Context
    ): MemoryFineTuner {
        return MemoryFineTuner(memoryDao, loraCheckpointDao, context)
    }

    @Provides
    @Singleton
    fun provideMemoryRetriever(memoryStore: MemoryStore): MemoryRetriever {
        return MemoryRetriever(memoryStore.embeddings, memoryStore.memoryDao)
    }

    @Provides
    @Singleton
    fun providePreferenceEngine(@ApplicationContext context: Context): PreferenceEngine {
        return PreferenceEngine(context)
    }

    @Provides
    @Singleton
    fun provideLoraEvolutionManager(
        @ApplicationContext context: Context,
        shizukuBridge: ShizukuBridge
    ): LoraEvolutionManager {
        return LoraEvolutionManager(context, shizukuBridge)
    }

    @Provides
    @Singleton
    fun provideSkillMatcher(memoryStore: MemoryStore): SkillMatcher {
        return SkillMatcher(memoryStore)
    }

    @Provides
    @Singleton
    fun provideSkillLearner(memoryStore: MemoryStore): SkillLearner {
        return SkillLearner(memoryStore)
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
        @ApplicationContext context: Context
    ): SmsHandler {
        return SmsHandler(context)
    }

    @Provides
    @Singleton
    fun provideIntentHandler(
        @ApplicationContext context: Context
    ): IntentHandler {
        return IntentHandler(context)
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
        fastLlmClient: FastLlmClient,
        mainLlmClient: MainLlmClient,
        npuEngine: NpuEngine,
        skillMatcher: SkillMatcher,
        memoryRetriever: MemoryRetriever?,
        memoryFineTuner: MemoryFineTuner,
        preferenceEngine: PreferenceEngine,
        loraEvolutionManager: LoraEvolutionManager
    ): BrainRouter {
        return BrainRouter(
            fastLlmClient = fastLlmClient,
            mainLlmClient = mainLlmClient,
            npuEngine = npuEngine,
            skillMatcher = skillMatcher,
            memoryRetriever = memoryRetriever,
            memoryFineTuner = memoryFineTuner,
            preferenceEngine = preferenceEngine,
            loraEvolutionManager = loraEvolutionManager
        )
    }

    @Provides
    @Singleton
    fun provideVoicePipeline(@ApplicationContext context: Context): VoicePipeline {
        return VoicePipeline(context)
    }
}
