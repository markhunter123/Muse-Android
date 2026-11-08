package rikka.core.util

import android.os.Build

// MUSE: 复制自 rikka-core 库（上游坐标不在本仓库依赖里），只保留 vendored adb 客户端用到的成员。
object BuildUtils {
    val atLeast29: Boolean get() = Build.VERSION.SDK_INT >= 29
}
