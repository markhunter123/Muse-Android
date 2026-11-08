# 内嵌 Shizuku server 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Shizuku server 以源码形式嵌入 Muse（fork 后 manager 身份指向 Muse），配对/启动/自启全部在 App 内完成，移除官方 Shizuku App 依赖。

**Architecture:** 新 Gradle 模块 `:shizuku-server` 承载 vendor 的 server/AIDL/基类与移植的 ADB 客户端；native starter 并入 Muse 现有 NDK 管线产出 `libmuse_starter.so`；`ShellServerController` 统一负责拉起（root / 无线调试 / 手动命令），binder 仍走既有 provider 推送协议，客户端代码（ShizukuShell/ShellUserService/CapabilityHub）不动。

**Tech Stack:** Kotlin/Java（vendor 主体）、CMake/NDK（starter + pairing JNI）、NsdManager（mDNS）、BoringSSL prefab、JUnit（JVM 单测）。

**Spec:** [docs/superpowers/specs/2026-11-08-embedded-shizuku-design.md](../specs/2026-11-08-embedded-shizuku-design.md)

## Global Constraints

- vendor 基准：Shizuku 主仓库与 Shizuku-API 子仓库各取一个 commit（Task 1 记录进 `:shizuku-server/README.md`），vendor 文件一律原样复制，改动只允许 fork 点，且每处标注 `// MUSE:`。
- fork 六点：① `MANAGER_APPLICATION_ID = "io.github.markhunter123.muse"`；② manager 检查查自己（常量改后自动成立，无需额外 diff）；③ `PERMISSION_MANAGER = "io.github.markhunter123.muse.permission.MANAGER"`（Muse manifest 声明），`PERMISSION`（`moe.shizuku.manager.permission.API_V23`）保持不变；④ 配置文件 `/data/user_de/0/com.android.shell/muse_shizuku.json`；⑤ 进程名 `muse_server`；⑥ 裁掉 RishConfig。
- 包名不改（vendor 文件保持 `rikka.shizuku.*` / `moe.shizuku.*` 原包名，diff 最小化）。
- Muse 现有约束不动：minSdk 29、targetSdk 36、compileSdk 37.2、arm64-v8a only、JDK 21。
- 用户可见文案中"Shizuku"改叫"shell 权限"；**代码标识符不改**（`shizuku_required` 等错误码进引擎契约，保持原样）。
- 不给第三方 App 提供 server 服务；对自身 uid 自动放行。
- 提交节奏：每个 task 一到两个 commit，消息用中文短句（对齐仓库现有风格）。

## Review Focus

1. **两个 server 并存**：老用户官方 server 与内嵌 server 同时推 binder —— 期望：Muse 用任何先到的 binder 都能工作，切换 server 时状态机收敛到 Ready 而不是卡在过渡态。（Task 12 状态机测试覆盖"binder 到达即 Ready"，并存行为进真机清单第 5 项。）
2. **Muse 升级后 server 自杀**：ApkChangedObservers 使 server 退出 —— 期望：binder 死亡 → `PairedNotRunning` → 前台自动重连重启，用户无感。（Task 12 单测覆盖 binder 死亡转移；真机清单第 6 项验证端到端。）
3. **无线调试端口变化**：重开无线调试换端口 —— 期望：不缓存端口，每次连接前现发现；发现失败给出"重开无线调试"文案。（Task 9 的 discover 实现不持久化端口；真机清单第 4 项覆盖。）
4. **Android 10 无配对路径**：API 30+ 才有无线调试 —— 期望：Android 10 设备显示 root/手动命令入口，配对入口不可见而非报错。（Task 15 UI 按 `Build.VERSION.SDK_INT >= 30` 分支；Task 12 单测覆盖 `NotPaired` 在 API < 30 的文案。）
5. **密钥丢失/恢复出厂**：Keystore 数据随卸载清除而 `/data/local/tmp` starter 残留无密钥可用 —— 期望：`PairedNotRunning`（原因：连接被拒绝）并引导重新配对，不崩溃不空指针。（Task 10 单测：无密钥时 `paired=false`、连接尝试跳过。）

---

## 阶段 1：vendor + fork + 最小启动

### Task 1: 取 vendor 基准（检出 Shizuku-API 子仓库）

**Files:**
- Modify（外部仓库）: `D:\project\Shizuku`（git submodule update --init）
- Create: `shizuku-server/README.md`

- [ ] **Step 1: 检出子仓库**

```bash
cd /d/project/Shizuku && git submodule update --init
ls api/   # 应出现 aidl/ api/ provider/ rish/ server-shared/ shared/ hidden-api-stub/
```

- [ ] **Step 2: 记录两个基准 commit**

```bash
git -C /d/project/Shizuku rev-parse HEAD
git -C /d/project/Shizuku/api rev-parse HEAD
```

- [ ] **Step 3: 写入 `shizuku-server/README.md`**（模块说明 + 两个 commit 号 + fork 点清单 + Apache-2.0 声明与上游链接）

- [ ] **Step 4: Commit**（README 与 Task 2 的骨架一起提）

### Task 2: `:shizuku-server` 模块骨架

**Files:**
- Create: `shizuku-server/build.gradle.kts`、`shizuku-server/src/main/AndroidManifest.xml`、`shizuku-server/consumer-rules.pro`
- Modify: `settings.gradle.kts`（include `:shizuku-server`）、`gradle/libs.versions.toml`（新增 hidden/refine/gson/parcelablelist/boringssl 条目）、`app/build.gradle.kts`（依赖 `:shizuku-server`）

**Interfaces:**
- Produces: 模块 `:shizuku-server` 可独立 `assembleDebug`；app 依赖它。

- [ ] **Step 1: `shizuku-server/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.refine)
}

android {
    namespace = "moe.shizuku.server.muse"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}

dependencies {
    api(libs.shizuku.api)            // BinderContainer 等，server 代码引用
    compileOnly(libs.shizuku.provider)
    implementation(libs.gson)
    api(libs.rikkax.parcelablelist)
    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)
    implementation(libs.bouncycastle.bcpkix)   // AdbKey 解析（阶段 2 用，一次加齐）
    implementation(libs.ndk.boringssl)          // prefab，阶段 2 pairing JNI 用
}
```

（版本号：hidden/refine 4.4.0 对齐 Shizuku 上游；gson 2.13.1；parcelablelist 2.0.1；bcpkix 1.80；boringssl 20250114 —— 全部来自上游 `libs.versions.toml` 与 manager/build.gradle。`refine` 插件需在根 `build.gradle.kts` 声明版本 `4.4.0`。）

- [ ] **Step 2: 空 manifest（仅 `<manifest/>`）+ `settings.gradle.kts` include + version catalog 条目 + app 依赖**
- [ ] **Step 3: 构建验证**：`./gradlew :shizuku-server:assembleDebug` → PASS
- [ ] **Step 4: Commit**：`shizuku-server 模块骨架`

### Task 3: vendor 主仓库源码（server + starter + common）

**Files（Create，全部原样复制自 D:\project\Shizuku，包名不动）:**
- `shizuku-server/src/main/java/rikka/shizuku/server/`：ApkChangedObservers.kt、BinderSender.java、ServerConstants.java、ShizukuClientManager.java、ShizukuConfig.java、ShizukuConfigManager.java、ShizukuService.java、ShizukuUserServiceManager.java、api/IContentProviderUtils.java、ktx/Handler.kt
- `shizuku-server/src/main/java/moe/shizuku/starter/`：ServiceStarter.java、util/IContentProviderCompat.java
- `shizuku-server/src/main/java/moe/shizuku/common/util/`：BuildUtils.java、OsUtils.java

- [ ] **Step 1: 用 `cp -r` 复制（不许手抄，保证逐字节一致）**

```bash
mkdir -p shizuku-server/src/main/java/{rikka/shizuku/server,moe/shizuku/starter/util,moe/shizuku/common/util}
cp /d/project/Shizuku/server/src/main/java/rikka/shizuku/server/*.{java,kt} shizuku-server/src/main/java/rikka/shizuku/server/
cp -r /d/project/Shizuku/server/src/main/java/rikka/shizuku/server/{api,ktx} shizuku-server/src/main/java/rikka/shizuku/server/
cp /d/project/Shizuku/starter/src/main/java/moe/shizuku/starter/*.java shizuku-server/src/main/java/moe/shizuku/starter/
cp /d/project/Shizuku/starter/src/main/java/moe/shizuku/starter/util/*.java shizuku-server/src/main/java/moe/shizuku/starter/util/
cp /d/project/Shizuku/common/src/main/java/moe/shizuku/common/util/*.java shizuku-server/src/main/java/moe/shizuku/common/util/
```

- [ ] **Step 2: vendor server-shared / aidl / shared（自 api/ 子仓库，同样 cp）**

```bash
cp -r /d/project/Shizuku/api/server-shared/src/main/java/* shizuku-server/src/main/java/
cp -r /d/project/Shizuku/api/aidl/src/main/aidl shizuku-server/src/main/aidl
cp -r /d/project/Shizuku/api/shared/src/main/java/* shizuku-server/src/main/java/
```

（若 aidl 模块另有 java 源一并复制；以实际目录为准。）

- [ ] **Step 3: 构建验证**（此时 fork 未做，可能因 RishConfig 报缺失 —— 属预期，下一 task 一并解决）：`./gradlew :shizuku-server:assembleDebug`
- [ ] **Step 4: Commit**：`vendor shizuku server 源码（基准 <hash8> / api <hash8>）`

### Task 4: fork 六点（全部 `// MUSE:` 标注）

**Files（Modify，均为 Task 3 vendor 进来的文件）:**

- [ ] **Step 1: `rikka/shizuku/server/ServerConstants.java`**

```java
// MUSE: manager identity points at Muse itself (embedded server serves only Muse)
public static final String MANAGER_APPLICATION_ID = "io.github.markhunter123.muse";
// MUSE: our own manager permission instead of the official app's
public static final String PERMISSION_MANAGER = "io.github.markhunter123.muse.permission.MANAGER";
```

（`REQUEST_PERMISSION_ACTION` 随常量自动指向 Muse，内嵌 server 不走弹窗路径，保留无害。）

- [ ] **Step 2: `rikka/shizuku/server/ShizukuService.java`**

```java
public static void main(String[] args) {
    // MUSE: no rish in the embedded build
    // RishConfig.setLibraryPath(System.getProperty("shizuku.library.path"));
    DdmHandleAppName.setAppName("muse_server", 0);
    ...
```

删 `import rikka.rish.RishConfig;`。构造器里的 manager 检查（`getManagerApplicationInfo() == null → exit`）**不改**：常量已指向 Muse，检查变成"自己必然在"；`ApkChangedObservers.start(ai.sourceDir, ...)` 顺势获得"Muse 更新 → server 退出"的语义（spec 要求的行为）。`checkCallerManagerPermission` 用 `managerAppId`（现在 = Muse uid）判定，不改。

- [ ] **Step 3: `rikka/shizuku/server/ShizukuConfigManager.java`**

```java
// MUSE: separate file from the official server's shizuku.json (both servers may coexist)
private static final File FILE = new File("/data/user_de/0/com.android.shell/muse_shizuku.json");
```

- [ ] **Step 4: 全局搜残留**：`grep -rn "shizuku_server\|RishConfig\|moe.shizuku.privileged.api" shizuku-server/src/` —— 命中只允许出现在注释或 `ShizukuApiConstants`（api 工件）引用里；`ServerStarter` 的 nice-name 由 native 侧传，见 Task 6。

- [ ] **Step 5: 构建 + Commit**：`fork：server 身份指向 Muse`（构建必须 PASS）

### Task 5: Muse manifest 声明自有 manager 权限

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 根元素内加**

```xml
<!-- MUSE embedded Shizuku server: BinderSender 用它识别 manager（即 Muse 自己） -->
<permission
    android:name="io.github.markhunter123.muse.permission.MANAGER"
    android:protectionLevel="normal" />
```

- [ ] **Step 2: queries 里的 `moe.shizuku.privileged.api` 本 task 不动**（阶段 3 统一拆除，避免中间态破坏官方 server 并存检测）
- [ ] **Step 3: 构建 + Commit**：`声明内嵌 server 的 manager 权限`

### Task 6: native starter → `libmuse_starter.so`

**Files:**
- Create: `native/starter/`（starter.cpp、selinux.cpp/.h、cgroup.cpp/.h、misc.cpp/.h、helper.cpp、logging.h、android.cpp/.h、CMakeLists.txt，全部复制自 `D:\project\Shizuku\manager\src\main\jni\`，删除 adb_pairing 相关仅保留 starter 链路所需）
- Modify: `native/build.sh`（把该 CMake 目标并入现有交叉编译流程，产出 `libmuse_starter.so` 进 jniLibs）；`app/build.gradle.kts` 无需改（jniLibs 随 `native/prebuilt.sh fetch` 产物路径打包）

- [ ] **Step 1: 复制源文件（cp，不手抄）**，读 `starter.cpp` 确认内部是否硬编码 `shizuku_server`/`moe.shizuku.privileged.api`，有则 fork 为 `muse_server`/Muse 包名（`// MUSE:` 标注）
- [ ] **Step 2: CMake 目标**

```cmake
add_executable(muse_starter starter.cpp selinux.cpp cgroup.cpp misc.cpp helper.cpp android.cpp)
target_link_libraries(muse_starter log)
# 产物重命名为 lib*.so 以满足 targetSdk 29+ 只能 exec nativeLibraryDir 的限制（与 manager 的 libshizuku.so 同理）
set_target_properties(muse_starter PROPERTIES OUTPUT_NAME "libmuse_starter")
```

- [ ] **Step 3: 本机构建验证**：`native/build.sh` 产出 `libmuse_starter.so`（arm64）；`adb shell run-as 不可用，真机验证挪到 Task 7`
- [ ] **Step 4: Commit**：`native starter（libmuse_starter.so）`

### Task 7: ShellServerController（root + 手动命令路径）

**Files:**
- Create: `app/src/main/java/io/github/markhunter123/muse/core/platform/ShellServerController.kt`
- Test: `app/src/test/java/io/github/markhunter123/muse/core/platform/ShellServerCommandsTest.kt`
- Modify: `app/src/main/java/io/github/markhunter123/muse/core/platform/ShellAccess.kt`（仅注入 Controller 的启动触发，状态机不动）

**Interfaces:**
- Produces:
  - `object ShellServerCommands { fun starterCommand(apkPath: String, nativeLibDir: String): String; fun rootCommand(starter: String, apkPath: String): String }`
  - `class ShellServerController @Inject constructor(@ApplicationContext ctx) { suspend fun ensureRunning(): ShellState; val connection: ConnectionStatus }`
  - `enum class ConnectionStatus { IDLE, CONNECTING, STARTING, FAILED }`

- [ ] **Step 1: 写失败测试**

```kotlin
class ShellServerCommandsTest {
    @Test fun `starter command points app_process at the muse apk`() {
        val cmd = ShellServerCommands.starterCommand(
            apkPath = "/data/app/~~x/base.apk",
            nativeLibDir = "/data/app/~~x/lib/arm64",
        )
        assertEquals(
            "/data/app/~~x/lib/arm64/libmuse_starter.so --apk=/data/app/~~x/base.apk", cmd,
        )
    }
    @Test fun `root command wraps starter in su -c`() {
        val cmd = ShellServerCommands.rootCommand(
            starter = "/data/app/~~x/lib/arm64/libmuse_starter.so",
            apkPath = "/data/app/~~x/base.apk",
        )
        assertEquals("su -c '/data/app/~~x/lib/arm64/libmuse_starter.so --apk=/data/app/~~x/base.apk'", cmd)
    }
    @Test fun `manual command is the adb shell form for the user to copy`() {
        val cmd = ShellServerCommands.manualCommand(
            starter = "/data/app/~~x/lib/arm64/libmuse_starter.so",
            apkPath = "/data/app/~~x/base.apk",
        )
        assertEquals("adb shell /data/app/~~x/lib/arm64/libmuse_starter.so --apk=/data/app/~~x/base.apk", cmd)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**：`./gradlew :app:testDebugUnitTest --tests "*ShellServerCommandsTest*"` → FAIL（类不存在）
- [ ] **Step 3: 实现 `ShellServerCommands`（纯函数，路径拼接）与 `ShellServerController` 最小版**：binder 已活（`Shizuku.pingBinder()`）→ 直接返回 Ready；否则若设置里 root 开关打开 → `Runtime.exec` 跑 `rootCommand`（读取 exit code 与输出）；手动路径仅产出字符串供 UI 复制。starter 命令执行后轮询 `Shizuku.pingBinder()` 至多 30 秒（dexopt 首启慢），成功即 Ready。
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: Commit**：`ShellServerController：root 与手动命令启动`

### Task 8: 设置页入口（root 开关 + 手动命令）

**Files:**
- Modify: `app/src/main/java/io/github/markhunter123/muse/feature/settings/`（手机权限页，执行时按 `PermissionCatalog` 实际结构定位插入点）
- Modify: `app/src/main/res/values/strings*.xml`（新增文案 key：`shell_server_root_hint`、`shell_server_manual_hint`、`shell_server_manual_copy`；英文同步 `values-en/`）

- [ ] **Step 1: 手机权限页加"shell 权限（实验）"卡**：root 启动按钮（调 `ShellServerController`）+ "复制手动启动命令"（`ClipboardManager`，内容 = `ShellServerCommands.manualCommand(...)`）
- [ ] **Step 2: 构建 + 真机 smoke**：root 设备点启动 → `pgrep muse_server` 有进程 → Muse 状态 Ready（`ShellAccess.state`）
- [ ] **Step 3: Commit**：`设置页：root 与手动启动入口`

## 阶段 2：ADB 客户端 + 配对 UI

### Task 9: ADB 客户端移植（Kotlin 7 文件 + pairing JNI）

**Files:**
- Create: `shizuku-server/src/main/java/moe/shizuku/manager/adb/`（AdbClient.kt、AdbKey.kt、AdbMessage.kt、AdbProtocol.kt、AdbPairingClient.kt、AdbPairingService.kt、AdbMdns.kt，cp 自 manager/adb/）
- Create: `shizuku-server/src/main/jni/`（adb_pairing.cpp/.h，cp 自 manager/jni；CMake 接入模块 own NDK build）
- Modify: `shizuku-server/build.gradle.kts`（externalNativeBuild 指向该 CMakeLists；boringssl prefab 链接）

**Interfaces:**
- Produces:
  - `AdbClient(host: String, port: Int, key: AdbKey) : Closeable` — `shellCommand(command: String, listener: ((ByteArray) -> Unit)?)`（与上游签名一致）
  - `AdbMdns(context, serviceType, callback)` — `_adb-tls-connect._tcp.tcp` / `_adb-tls-pairing._tcp.tcp`
  - `AdbPairingClient(pairingPort, key, listener)` — 6 位码配对
- 暴露给 app 的窄入口（阶段 2 末尾定稿）：

```kotlin
// shizuku-server: moe.shizuku.server.muse.MuseAdb —— app 只 import 这一个
data class AdbResult(val success: Boolean, val log: String?)   // log: adbd shell 输出/异常摘要，进诊断
data class PairResult(val success: Boolean, val reason: PairFailure?) // PairFailure: WRONG_CODE / TIMEOUT / DISCOVERY
object MuseAdb {
    suspend fun discoverConnectPort(timeoutMs: Long): Int?          // mDNS，不缓存
    suspend fun connectAndStart(key: AdbKey, port: Int, starterCmd: String): AdbResult
    suspend fun pair(key: AdbKey, pairingPort: Int, code: String): PairResult
    fun loadKey(storage: KeyStorage): AdbKey?
    fun createKey(storage: KeyStorage): AdbKey
}
```

- [ ] **Step 1: cp 七文件**（注意上游这些文件 import `moe.shizuku.manager.*` 与 JNI 包装，编译报错逐个处理：manager 包内的工具（ktx/Log 等）最小复制进同包，继续 `// MUSE:` 不改逻辑）
- [ ] **Step 2: adb_pairing.cpp + CMake + prefab 链接**，构建 PASS
- [ ] **Step 3: Commit**：`移植 ADB 客户端与配对 JNI`

### Task 10: ADB 密钥存储（Keystore 加密）

**Files:**
- Create: `shizuku-server/src/main/java/moe/shizuku/server/muse/AdbKeyStore.kt`
- Test: `shizuku-server/src/test/java/moe/shizuku/server/muse/AdbKeyStoreTest.kt`

**Interfaces:**
- Produces: `interface KeyStorage { fun save(bytes: ByteArray); fun load(): ByteArray?; fun clear() }`、`class KeystoreKeyStorage(context) : KeyStorage`（AES-GCM，Key 别名 `muse_adb_key`，密文存 `noBackupFilesDir/muse_adb_key.bin`）

- [ ] **Step 1: 失败测试（JVM 可测部分）**：`InMemoryKeyStorage` + `AdbKey.readFrom/saveTo` 往返：生成密钥 → save → 新实例 load → 用加载的密钥对任意 payload 做 ADB 签名，验证与原密钥签名一致

```kotlin
@Test fun `key roundtrip keeps signing identity`() {
    val storage = InMemoryKeyStorage()
    val k1 = MuseAdb.createKey(storage)
    val k2 = MuseAdb.loadKey(storage)!!
    val payload = ByteArray(20) { it.toByte() }
    assertArrayEquals(k1.sign(payload), k2.sign(payload))
}
```

- [ ] **Step 2: 跑测试失败 → 实现 `AdbKeyStore`（Keystore 路径薄封装）与纯逻辑 → 测试通过**
- [ ] **Step 3: `AdbKeyStoreTest` 加"清除后 load 为 null"用例（对应 Review Focus 5）**
- [ ] **Step 4: Commit**：`ADB 密钥：Keystore 加密存储与往返测试`

### Task 11: AdbLauncher + 配对流程接入 Controller

**Files:**
- Modify: `app/.../core/platform/ShellServerController.kt`（决策序补全：binder → wadb 自动连接 → 失败落状态）
- Create: `app/.../core/platform/ShellPairing.kt`（配对状态流：`Idle/Discovering/WaitingCode/Paired/Failed(reason)`）

- [ ] **Step 1: Controller 决策序实现**（spec 第 3 节：1 binder 已活 → 2 有密钥且 API≥30 → mDNS 现发现端口 → `MuseAdb.connectAndStart`（shell 内跑 `libmuse_starter.so --apk=...`）→ 轮询 binder；3 全失败 → 状态落 `PairedNotRunning(reason)`）
- [ ] **Step 2: 配对流程**：`MuseAdb.pair(...)` 成功后立即走一遍连接；失败带原因（码错/超时/发现失败三类，文案分开）
- [ ] **Step 3: 构建 + 单测（决策序用假 MuseAdb interface 注入测试）**
- [ ] **Step 4: Commit**：`AdbLauncher 与配对流程`

### Task 12: 设置"shell 权限"页（配对 UI）

**Files:**
- Create: `app/.../feature/settings/ShellServerScreen.kt`（Compose：状态行、无线调试检测、6 位码输入（OtpTextField 样式自绘或 6 个 BasicTextField）、开始/重新配对按钮、root 按钮、手动命令入口）
- Modify: `feature/settings/` 导航与手机权限页入口、`strings*.xml`（新增 key 全部中英同步）

- [ ] **Step 1: UI 实现**（状态消费 `ShellAccess.state` + `ShellPairing.flow`；Android 10 (`SDK_INT < 30`) 隐藏配对区，只显示 root/手动 —— Review Focus 4）
- [ ] **Step 2: 真机 smoke**：配对 → 启动 → Ready；重启 App（不重启手机）→ 自动连回 Ready
- [ ] **Step 3: Commit**：`shell 权限页：配对与启动`

## 阶段 3：自启 + 状态机 + 拆官方路径

### Task 13: `ShellState` 重写

**Files:**
- Modify: `app/.../core/platform/ShellAccess.kt`（ShellState/ShellProbe/classify 重写；`NotInstalled`、`InstalledNotRunning`、bootTip 三件套、`PACKAGE`/`RELEASES_URL`/`managerVersion`/`launchIntent`/`playStoreIntent`/`atLeast` 删除）
- Test: `app/src/test/java/io/github/markhunter123/muse/core/platform/ShellAccessClassifyTest.kt`（改写既有用例）

- [ ] **Step 1: 失败测试**

```kotlin
@Test fun `binder alive and granted is Ready`() =
    assertEquals(ShellState.Ready, ShellState.classify(probe(alive = true, version = 13, granted = true, paired = true)))
@Test fun `paired but binder dead carries last failure`() {
    val s = ShellState.classify(probe(alive = false, version = null, granted = null, paired = true, lastFailure = "wadb port not found"))
    assertTrue(s is ShellState.PairedNotRunning && s.reason == "wadb port not found")
}
@Test fun `no key is NotPaired`() =
    assertEquals(ShellState.NotPaired, ShellState.classify(probe(alive = false, version = null, granted = null, paired = false)))
@Test fun `running without grant is RunningNoPermission (official server coexistence)`() =
    assertEquals(ShellState.RunningNoPermission(denied = false), ShellState.classify(probe(alive = true, version = 13, granted = false, paired = true)))
@Test fun `old server version is Unsupported`() =
    assertEquals(ShellState.Unsupported(10), ShellState.classify(probe(alive = true, version = 10, granted = null, paired = true)))
```

（Android 10 不显示配对入口是 UI 层分支，见 Task 12；`classify()` 不感知 API level。）

（`probe(...)` 为测试内的 ShellProbe 构造辅助。）

- [ ] **Step 2: 跑失败 → 重写 ShellState（`NotPaired` / `PairedNotRunning(reason)` / `RunningNoPermission(denied)` / `Ready` / `Unsupported`）与 `classify()` → 通过**
- [ ] **Step 3: `wasReady`、bootTip prefs 迁移**：旧 key 读到后写新 key 并删旧 key（老用户升级后 `PairedNotRunning` 的 wasReady 语义并入 `reason`）
- [ ] **Step 4: 全量单测 + Commit**：`ShellState 重写为内嵌 server 语义`

### Task 14: 开机自启

**Files:**
- Create: `app/src/main/java/io/github/markhunter123/muse/core/platform/ShellBootReceiver.kt`
- Modify: `AndroidManifest.xml`（receiver 注册，`BOOT_COMPLETED` filter；不导出）
- Modify: `ShellServerController`（抽出 `suspend fun tryAutoStart(): Boolean` 供 receiver 调用：有密钥 → wadb 连接 → starter；失败静默记 pref，等下次前台）
- Modify: `ReliabilityDoctor`（体检项："无线调试保持开启"——检测 `Settings.Global.ADB_WIFI_ENABLED` 与密钥存在）

- [ ] **Step 1: receiver 实现**（`goAsync` + 协程，20 秒预算内完成 mDNS 尝试；失败不通知——与官方一致，下次解锁 App 前台再试）
- [ ] **Step 2: 体检项 + 文案（中英）**
- [ ] **Step 3: 真机验证**：重启 → 免交互 Ready（Review Focus 2、3 的端到端）
- [ ] **Step 4: Commit**：`开机自启（无线调试）`

### Task 15: 下游文案与引导改造

**Files:**
- Modify: `app/.../feature/settings/ShellGuide.kt`（重写：新状态文案；删除"安装 Shizuku""去 Shizuku 配对"全部段落）
- Modify: `core/capability/` 里 `shizuku_required` 的用户可见消息、诊断页（`describe()` 消费新 `reason`）
- Modify: `strings*.xml`（中英同步；"Shizuku"字样 → "shell 权限"）

- [ ] **Step 1: 文案逐条改**（`ShellState.hint` 是模型可见文本，同步改）
- [ ] **Step 2: grep 兜底**：`grep -rn "Shizuku" app/src/main/res/ app/src/main/java/ | grep -v -i "muse_server\|shizuku-server"` 清到只剩注释与 `rikka.shizuku` 包名引用
- [ ] **Step 3: Commit**：`文案：shell 权限`

### Task 16: 拆官方路径

**Files:**
- Delete: `app/.../core/platform/ShizukuInstaller.kt`、`tools/fetch-shizuku.sh`
- Modify: `app/build.gradle.kts`（删 `shizukuPin`、`fetchShizuku` 任务、`SHIZUKU_*` BuildConfig、`dsh.fetchShizuku` 属性块、assets/shizuku 输出目录引用）
- Modify: `AndroidManifest.xml`（删 queries 里的 `moe.shizuku.privileged.api`；删 `REPLACE_EXISTING_PACKAGE`/PackageInstaller 相关权限若仅 ShizukuInstaller 使用）
- Modify: `ShellAccess`（删 launchIntent/playStoreIntent —— Task 13 已做则跳过）

- [ ] **Step 1: 逐项删除，`grep -rn "fetchShizuku\|ShizukuInstaller\|SHIZUKU_" app/ tools/ js/` 清零**（js/ 与 docs/ 的引用一并处理）
- [ ] **Step 2: 全量构建 + 全量单测 PASS**
- [ ] **Step 3: Commit**：`移除官方 Shizuku App 路径`

### Task 17: 迁移提示 + 文档

**Files:**
- Modify: `feature/settings/`（一次性 DataStore 标记 `official_shizuku_hint_shown`；检测官方包存在且 Muse Ready 时弹提示"不再需要官方 Shizuku，可卸载"）
- Modify: `README.md`（快速开始第 4 步改为"设置 → 手机权限 里完成无线调试配对"；亮点与限制段、致谢条目改为"内嵌 Shizuku server 组件（Apache-2.0，vendor 自 <hash>）"；构建说明删 `-Pdsh.fetchShizuku`）
- Modify: `docs/ARCHITECTURE.md`（进程模型加 `muse_server`；"Shizuku（`core/platform/...`）"段落改写）
- Modify: `docs/RELEASE.md`（如提及 fetch-shizuku）

- [ ] **Step 1: 逐文档改**
- [ ] **Step 2: Commit**：`文档与迁移提示`

### Task 18: 发布前真机全清单（spec 第 7 节 8 项）

- [ ] 全新设备配对→启动→`adb_shell`→`setting_put`
- [ ] `Android/data` 列目录（ShellFiles）
- [ ] AgentScreen 虚拟屏 + 截屏
- [ ] 重启自启
- [ ] 官方 Shizuku 在跑的老设备升级 → 无感 → 卸载官方 → 重启 → 重配对
- [ ] Muse 覆盖安装 → server 自杀 → 前台自动重启
- [ ] Android 10：配对入口隐藏，root/手动命令可用
- [ ] 诊断页 `describe()` 全状态

- [ ] **每项结果记录进 PR/发布说明；发现问题的修复各自提交**

## 依赖与顺序说明

- Task 1 → 3 → 4 串行（fork 依赖 vendor 文件在位）；Task 2 可与 Task 1 并行。
- Task 7/8 只依赖 Task 2/6/7 接口，可与 Task 9-12（阶段 2）并行推进，但建议按序执行避免 UI 返工。
- Task 13 依赖 12 的最终状态语义；Task 16 必须在 13/15 之后（queries 删除会破坏并存检测）。
- 全程每个构建/测试/commit 步骤需要 Bash 可用；Bash 不可用时只做文件级准备，绝不跳过验证步骤往下走。
