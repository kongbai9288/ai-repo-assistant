package com.kongbai.airepo.di

import com.kongbai.airepo.core.Constants
import com.kongbai.airepo.core.SecureStore
import com.kongbai.airepo.data.local.AppDatabase
import com.kongbai.airepo.data.local.ChatDao
import com.kongbai.airepo.data.remote.ai.AiClient
import com.kongbai.airepo.data.remote.ai.AiRest
import com.kongbai.airepo.data.remote.github.GitHubService
import com.kongbai.airepo.data.search.WebSearch
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    @Named("plain")
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(Constants.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    /** 不需要挂 Token 的场景（AI 接口、OAuth 换 token、联网搜索）直接用这个无限定实例 */
    @Provides
    @Singleton
    fun provideDefaultOkHttp(@Named("plain") ok: OkHttpClient): OkHttpClient = ok

    /** GitHub 专用：自动挂 Bearer Token，token 从加密存储里实时读取 */
    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubOkHttp(secureStore: SecureStore): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = secureStore.get(SecureStore.KEY_GITHUB_TOKEN)
            val req = chain.request().newBuilder()
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .addHeader("User-Agent", Constants.USER_AGENT)
            if (!token.isNullOrBlank()) req.addHeader("Authorization", "Bearer $token")
            chain.proceed(req.build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    @Provides
    @Singleton
    fun provideGitHubService(moshi: Moshi, @Named("github") ok: OkHttpClient): GitHubService =
        Retrofit.Builder()
            .baseUrl(Constants.GITHUB_API)
            .client(ok)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubService::class.java)

    @Provides
    @Singleton
    fun provideAiRest(@Named("plain") ok: OkHttpClient, moshi: Moshi): AiRest = AiClient(ok, moshi)

    @Provides
    @Singleton
    fun provideWebSearch(@Named("plain") ok: OkHttpClient): WebSearch = WebSearch(ok)

    @Provides
    @Singleton
    fun provideDatabase(app: android.app.Application): AppDatabase =
        androidx.room.Room.databaseBuilder(app, AppDatabase::class.java, "airepo.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()
}
