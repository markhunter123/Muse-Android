package io.github.markhunter123.muse.core.platform

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import moe.shizuku.server.muse.KeyStorage
import moe.shizuku.server.muse.MuseAdb
import moe.shizuku.server.muse.MuseAdbClient
import moe.shizuku.server.muse.KeystoreKeyStorage
import javax.inject.Singleton

/** 内嵌 shell 的网络与密钥实现绑定（实现类在 shizuku-server 模块，不带 Hilt 注解，这里 @Provides）。 */
@Module
@InstallIn(SingletonComponent::class)
object ShellPlatformModule {

    @Provides
    @Singleton
    fun museAdb(@ApplicationContext context: Context): MuseAdb = MuseAdbClient(context)

    @Provides
    @Singleton
    fun keyStorage(@ApplicationContext context: Context): KeyStorage = KeystoreKeyStorage(context)
}
