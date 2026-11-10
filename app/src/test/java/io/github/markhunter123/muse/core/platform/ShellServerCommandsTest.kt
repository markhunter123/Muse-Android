package io.github.markhunter123.muse.core.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/** 嵌入式 shell server 的三条启动命令（root / adb 手动 / 复制给用户），纯函数拼接。 */
class ShellServerCommandsTest {

    @Test
    fun `starter command points app_process at the muse apk and forwards the application id`() {
        val cmd = ShellServerCommands.starterCommand(
            apkPath = "/data/app/~~x/base.apk",
            nativeLibDir = "/data/app/~~x/lib/arm64",
            packageName = "io.github.markhunter123.muse.dev",
        )
        assertEquals(
            "/data/app/~~x/lib/arm64/libmuse_starter.so --apk=/data/app/~~x/base.apk --package=io.github.markhunter123.muse.dev", cmd,
        )
    }

    @Test
    fun `root command wraps starter in su -c`() {
        val cmd = ShellServerCommands.rootCommand(
            starter = "/data/app/~~x/lib/arm64/libmuse_starter.so",
            apkPath = "/data/app/~~x/base.apk",
            packageName = "io.github.markhunter123.muse.dev",
        )
        assertEquals("su -c '/data/app/~~x/lib/arm64/libmuse_starter.so --apk=/data/app/~~x/base.apk --package=io.github.markhunter123.muse.dev'", cmd)
    }

    @Test
    fun `manual command is the adb shell form for the user to copy`() {
        val cmd = ShellServerCommands.manualCommand(
            starter = "/data/app/~~x/lib/arm64/libmuse_starter.so",
            apkPath = "/data/app/~~x/base.apk",
            packageName = "io.github.markhunter123.muse.dev",
        )
        assertEquals("adb shell /data/app/~~x/lib/arm64/libmuse_starter.so --apk=/data/app/~~x/base.apk --package=io.github.markhunter123.muse.dev", cmd)
    }
}
