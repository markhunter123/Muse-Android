package rikka.core.ktx

// MUSE: 复制自 rikka-core 库（上游坐标不在本仓库依赖里），vendored adb 客户端的 AdbKey 用到。
fun <T> unsafeLazy(initializer: () -> T): Lazy<T> = lazy(LazyThreadSafetyMode.NONE, initializer)
