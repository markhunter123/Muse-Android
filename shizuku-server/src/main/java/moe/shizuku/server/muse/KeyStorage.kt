package moe.shizuku.server.muse

// MUSE: 计划 Task 10 —— 密钥 blob 的存取口；JVM 单测用 InMemory 实现，设备上用 KeystoreKeyStorage。
interface KeyStorage {

    fun save(bytes: ByteArray)

    fun load(): ByteArray?

    fun clear()
}
