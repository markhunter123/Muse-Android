# 内嵌 Shizuku server 设计

日期：2026-11-08
状态：设计已确认（对话中分节过目），待实施

## 背景与目标

Muse 的 shell 能力（`adb_shell`、`setting_put`、`Android/data` 访问、AgentScreen 虚拟屏、按包静音）全部依赖 Shizuku：以 shell uid（2000）运行 server 进程，通过 binder 向客户端提供服务。现状是 Muse 只做客户端，依赖官方 Shizuku App 启动和托管 server；用户要装两个 App、去 Shizuku 里配对无线调试。

**目标**：把 server 嵌进 Muse，装一个 APK 即有完整 shell 能力。官方 Shizuku 路径（内置 APK 安装、引导、queries）整体移除。

**不做**：不给第三方 App 提供 Shizuku 服务；Muse 是终端产品，内嵌 server 只服务自己，对自身 uid 自动放行授权。

## 现状（源码确认的事实）

客户端侧（Muse）：

- `dev.rikka.shizuku:api` / `:provider` 13.1.5（Maven）。manifest 已声明 `rikka.shizuku.ShizukuProvider`（authority `${applicationId}.shizuku`），负责接收 server 推来的 binder。
- [ShellAccess.kt](../../../app/src/main/java/io/github/markhunter123/muse/core/platform/ShellAccess.kt)：状态机（NotInstalled / InstalledNotRunning / RunningNoPermission / Ready / Unsupported），围绕官方包名 `moe.shizuku.privileged.api`。
- [ShellUserService.kt](../../../app/src/main/java/io/github/markhunter123/muse/core/platform/ShellUserService.kt)：Shizuku user service（`IShellService.Stub`），exec、虚拟屏、文件、任务、静音。
- `ShizukuShell` / `ShellFiles` / CapabilityHub：消费 `ShellState`。
- 内置官方 APK：`tools/fetch-shizuku.sh`（下载+校验）、`app/build.gradle.kts` 的 `fetchShizuku` 任务与 `SHIZUKU_*` BuildConfig、`ShizukuInstaller`。

Shizuku 机制（本地 `D:\project\Shizuku` 检出，13.6.0 分支）：

- server = `app_process` 进程，入口 `rikka.shizuku.server.ShizukuService.main()`，进程名 `shizuku_server`（`DdmHandleAppName`）。server 类打包在 manager APK 里，native starter（`libshizuku.so`，源在 `manager/src/main/jni/starter.cpp` 及配套 selinux/cgroup/misc）以 `--apk=<apk路径>` 被 shell 身份执行，其内部 `CLASSPATH=<apk>` exec `app_process`。
- server 启动时硬依赖 manager：`getManagerApplicationInfo()` 为空即 `System.exit(MANAGER_APP_NOT_FOUND)`；`checkCallerManagerPermission` 按 manager 的 appId 判定。
- binder 传递是 server 主动推送：`sendBinderToUserApp` 对所有声明 `moe.shizuku.manager.permission.API_V23` 的包，经 `getContentProviderExternal(<包名>.shizuku)` 调 `sendBinder` 方法投递 `BinderContainer`；`BinderSender` 注册进程/uid 观察者，App 起来时补推。**客户端不需要 manager 在场**。
- 配置持久化在 `/data/user_de/0/com.android.shell/shizuku.json`（`ShizukuConfigManager.FILE`），shell 用户可写。
- manager 自带完整 ADB 客户端（`manager/adb/`：AdbClient、AdbKey、AdbMessage、AdbProtocol、AdbPairingClient、AdbPairingService、AdbMdns）：无线调试配对（SPAKE2，JNI 在 `adb_pairing.cpp`，依赖 BoringSSL prefab `io.github.vvb2060.ndk:boringssl`）、连接认证、mDNS 服务发现（`_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`）、开机自启（`BootCompleteReceiver` 用已存密钥直连再跑 starter）。
- 模块构成：主仓库 `:server`（10 个类）、`:starter`（Java 侧 ServiceStarter）、`:common`；Shizuku-API 子仓库（主仓库 `api/` 子模块，本地未检出）`server-shared`（Service/ConfigManager/UserService 基类）、`aidl`、`shared`。server 依赖 Maven 工件：gson、`dev.rikka.rikkax.parcelablelist`、`dev.rikka.hidden:compat/stub` 4.4.0、refine。
- ShizukuService 引用了 `rikka.rish.RishConfig`（rish 是给终端用户的交互 shell，Muse 用不到）。
- 用户服务（user service）机制：server 用 ServiceStarter 以请求方 App 的 APK 为 CLASSPATH 拉起 user service 类；Muse 的 `ShellUserService` 今天就这么跑，机制对任意客户端 APK 有效，嵌入后客户端仍是 Muse，不变。

版本对齐：vendor 基准 = 本地检出的 master（13.6.0.r<N>.<hash>），与 Muse 客户端工件 13.1.5 协议兼容（server API v13 ≥ 客户端要求的 MIN_VERSION 11）。

## 总体架构

```
:main 进程
  ShellServerController（新）── "让 server 活着"的唯一入口
    ├─ RootLaunch        su -c <starter>（手动按钮，不静默探测 su）
    ├─ AdbLauncher       无线调试：NsdManager 发现端口 → AdbClient 连接认证 → 跑 starter
    └─ PairingFlow       配对 UI（6 位码）+ ADB 密钥管理（Keystore AES-GCM 加密，noBackupFilesDir）
  ShellAccess            状态机改语义，对外接口不变，下游（CapabilityHub、卡片、ShellGuide、诊断）不动逻辑
  ShizukuShell / ShellUserService / CapabilityHub ── 不动

muse_server 进程（shell uid）
  vendor 的 Shizuku server（fork：manager 身份指向 Muse）
```

客户端协议零改动：server 起来后照样把 binder 推到 `${applicationId}.shizuku` provider，`rikka.shizuku:api` 照常收。`:engine` 进程不涉及。

## Vendor 边界与 fork 点

新 Gradle 模块 `:shizuku-server`（android library，`app` 依赖它），源码 vendor，保留 Apache-2.0 文件头，fork 点集中标注（`// MUSE:` 注释）便于跟上游。

**Vendor 清单**（基准：Shizuku 主仓库 + Shizuku-API 子仓库各一个 commit；实施第一步先在本地检出里 `git rev-parse` 取两个 commit 号写入 `:shizuku-server` 的 README，vendor 即以这两个 commit 为准）：

| 来源 | 内容 |
|---|---|
| Shizuku 主仓库 `server/` | 全部 10 个类（ShizukuService、BinderSender、ServerConstants、ShizukuClientManager、ShizukuConfig、ShizukuConfigManager、ShizukuUserServiceManager、ApkChangedObservers、api/IContentProviderUtils、ktx/Handler） |
| Shizuku 主仓库 `starter/` | Java 侧 `moe.shizuku.starter.ServiceStarter`（user service 拉起）+ `IContentProviderCompat` |
| Shizuku 主仓库 `common/` | BuildUtils、OsUtils（ShizukuService 引用） |
| Shizuku-API `server-shared/` | Service、ConfigManager、UserService、UserServiceManager 基类 |
| Shizuku-API `aidl/`、`shared/` | AIDL 接口、共享常量 |

**Maven 依赖**（不 vendor）：gson、parcelablelist、`dev.rikka.hidden:compat`/`:stub` 4.4.0、`dev.rikka.tools.refine`（:shizuku-server 模块加 refine 插件）、`dev.rikka.shizuku:api`/`:provider` 13.1.5（已有）。BinderContainer 来自 api 工件，server 现成引用。

**裁掉**：RishConfig 依赖（fork 移除 import 与 `main()` 里的 `setLibraryPath` 调用，不 vendor rish 模块）。

**fork 点（全部）**：

1. `ServerConstants.MANAGER_APPLICATION_ID` → `io.github.markhunter123.muse`
2. `ShizukuService` 构造器：manager 存在性检查改为查 Muse 自己（必然在）；`checkCallerManagerPermission` → 仅 Muse 自身 uid 放行（不再依赖 manager 权限的 appId 判定）
3. `BinderSender.PERMISSION_MANAGER` → Muse 自有权限字符串（Muse manifest 声明）；`PERMISSION`（`moe.shizuku.manager.permission.API_V23`）**保持不变**——Muse 已通过 provider 库 manifest 声明它，且保留官方 server 推送的兼容（并存期）
4. `ShizukuConfigManager.FILE` → `/data/user_de/0/com.android.shell/muse_shizuku.json`（避开官方路径，防两个 server 并存时写坏同一文件）
5. `DdmHandleAppName.setAppName("shizuku_server")` → `"muse_server"`
6. 授权流程简化：对自身 uid 自动 granted，无需 REQUEST_PERMISSION 弹窗路径（`ShizukuConfig` 保留，server-shared 基类需要它）

**native 侧**（进 Muse 现有 NDK 管线）：

- `starter.cpp` + selinux/cgroup/misc/helper → `libmuse_starter.so`（可执行，放 nativeLibraryDir），命令语义与官方相同：`<nativeLibraryDir>/libmuse_starter.so --apk=<Muse sourceDir>` 内部 `CLASSPATH=<Muse APK> app_process /system/bin --nice-name=muse_server rikka.shizuku.server.ShizukuService`
- `adb_pairing.cpp`（SPAKE2/配对密码学）→ 移植，依赖 `io.github.vvb2060.ndk:boringssl` prefab
- ADB 客户端 Kotlin 7 个文件 → 放进 `:shizuku-server`（特权机制的代码集中在一个模块，App 侧 ShellServerController 只消费 connect/pair/discover 三个入口）

** dexopt 取舍**：CLASSPATH 指向约 110MB 的 Muse APK，server 首次启动多花 1–2 秒（ART 复用安装时 oat，一次性）。替代方案"server 单独打成小 dex jar 放 assets"已评估并否决：多一条打包/版本同步链路，不值得。

## 启动与连接流程

`ShellServerController` 触发点：App 冷启、回前台、shell 工具调用发现 shell 不可用时。决策顺序：

1. binder 已活（provider 已收到推送且 `pingBinder` 成功）→ Ready，不做任何事
2. 有已存 ADB 密钥且 Android 11+：NsdManager 现场发现无线调试端口（**每次现发现，不缓存端口**——无线调试重开会换端口）→ AdbClient 连接 + 认证 → shell 执行 starter 命令 → server 自杀式守护不需要，起不来就报状态
3. 都不行 → 状态落到 `PairedNotRunning` 或 `NotPaired`，等用户操作

**配对**（一次性）：设置 → 手机权限 → 新"shell 权限"页。检测无线调试开关（`Settings.Global.ADB_WIFI_ENABLED`）→ mDNS 发现配对服务 → 用户输入 6 位码 → AdbPairingClient 完成配对 → RSA 密钥对经 Keystore AES-GCM 加密存 `noBackupFilesDir`（与 SecretStore 同一路数）→ 成功后立刻走一遍启动流程。

**root**：设置里手动按钮（`su -c <starter 命令>`），不静默探测、不自动弹 su 授权。

**开机自启**：BOOT_COMPLETED receiver（移植官方 `BootCompleteReceiver` 逻辑）：读密钥 → mDNS 发现 → 连接 → 执行 starter。系统开关"无线调试"须保持开启；体检页（ReliabilityDoctor）加对应检查项。

**Android 10（minSdk 29）**：无线调试配对不可用（系统限制，API 30+）。保留"复制手动启动命令"入口（电脑 `adb shell` 执行）作兜底，root 按钮照常可用。

## 状态机改造

`ShellState`（[ShellAccess.kt](../../../app/src/main/java/io/github/markhunter123/muse/core/platform/ShellAccess.kt)）：

| 旧 | 新 |
|---|---|
| NotInstalled | 移除（无"装没装 App"概念） |
| InstalledNotRunning(wasReady) | `PairedNotRunning(reason)`：无线调试没开 / mDNS 没找到 / 连接失败 / starter 失败，带机器可读原因与用户文案 |
| RunningNoPermission(denied) | 保留（仅并存期官方 server 可能触发；内嵌 server 对自身自动放行不会出现） |
| Ready | 不变 |
| Unsupported(version) | 不变 |

`classify()` 纯函数重写 + 单测同步。`ShellProbe` 字段调整：去掉 `managerInstalled`，加 `paired`、`lastFailure`。`reason`/`hint` 文案改写；`describe()` 诊断行同步。

下游不动逻辑：Shizuku 卡片、ShellGuide、CapabilityHub 的 `shizuku_required` 错误码（**标识符不改名**，避免动引擎契约；只改用户可见文案"Shizuku"→"shell 权限"）、诊断页。设置 → 手机权限里原"安装 Shizuku"卡换成新"shell 权限"页。

## 移除清单

- `ShizukuInstaller.kt`、`tools/fetch-shizuku.sh`
- `app/build.gradle.kts`：`fetchShizuku` 任务、`shizukuPin`、`SHIZUKU_*` BuildConfig、`dsh.fetchShizuku` 属性处理、assets/shizuku 输出
- `AndroidManifest.xml`：queries 里的 `moe.shizuku.privileged.api`
- `ShellAccess`：`PACKAGE`、`RELEASES_URL`、`managerVersion()`、`launchIntent()`、`playStoreIntent()`、`atLeast()`、bootTip 三件套
- ShellGuide 的安装引导段、README 相关章节、致谢（改为"内嵌 Shizuku server 组件（Apache-2.0）"）、`docs/ARCHITECTURE.md` 的 Shizuku 段落
- `docs/RELEASE.md` 若提到 fetch-shizuku 步骤，同步

## 老用户迁移

- 官方 Shizuku 已装且 server 在跑：推送协议相同，Muse 升级后 binder 直接可用，无感切换；设置页一次性提示"不再需要官方 Shizuku，可卸载"。
- 官方 server 停了而 Muse 未配对：走新配对流程，卡片文案引导。
- 官方与内嵌并存（用户不卸载）：两个 server 都会推 binder，Muse 以收到的为准；内嵌配置文件独立路径（fork 点 4），互不写坏。不主动杀官方进程。

## 错误处理

- 配对失败（码错/超时/ROM 限制配对）：页面明示原因，可重试，可"重新配对"（重新生成密钥前先删旧的）。
- 连接失败（密钥失效、mDNS 找不到、adbd 拒绝）：状态带原因，失败详情进诊断页（`describe()`）。
- Muse 更新后：APK 内容变化 → server 自杀（上游 `ApkChangedObservers` 行为，**保留**）→ binder 死亡 → Muse 前台时 ShellServerController 自动重连重启。比官方体验好（官方需用户手动点）。
- 首次 dexopt 慢：状态机容忍 starter 命令执行 10–30 秒，超时报失败可重试。
- server 异常退出（不因 APK 变化）：`BinderSender` 机制依赖 server 在跑；binder 死亡后走正常重连路径。

## 测试

JVM 单测：

- 新 `classify()` 状态机全分支
- AdbKey 生成 → 加密存储 → 解密 → 签名可用（往返）
- starter/root/手动命令拼装（路径 mock）
- 配对码输入校验

真机手动清单（发布前跑一遍）：

1. 全新设备：开无线调试 → 配对 → 启动 → `adb_shell` 工具 → `setting_put`
2. `Android/data` 列目录（ShellFiles）
3. AgentScreen 虚拟屏创建 + 截屏
4. 重启（无线调试保持开启）→ 开机自启生效
5. 老设备（官方 Shizuku 在跑）升级 Muse → 无感可用 → 卸载官方 App → 重启 → 重新配对可用
6. Muse 升级（覆盖安装）→ server 自杀 → Muse 前台自动重启 server
7. Android 10 设备：配对入口显示不可用，手动命令/root 可用
8. 诊断页 `describe()` 各状态文案正确

## 风险与取舍

- **长期维护**：hidden API 兼容层（`rikka.hidden.compat` 对 AOSP 内部接口的适配）从此自己跟上游。Android 大版本发布时要对照 Shizuku 上游改动手动同步。这是方案的本质代价，换单 APK 闭环。
- ROM 怪癖（MIUI 等对 adbd/配对的限制）修复靠跟上游，不会自动到达。
- mDNS 本机发现在个别 ROM 上不稳定（官方同样存在），表现为"找不到端口"，文案引导重试/重新开关无线调试。
- 多用户/工作资料：server 推 binder 按 userId 扫描，行为与官方一致，不额外支持。
- dexopt 首启延迟 1–2 秒（见上，已接受）。

## 分阶段实施

1. **vendor + fork + 最小启动**：`:shizuku-server` 模块、fork 六点、`libmuse_starter.so`、root 按钮 + 手动命令入口。产出：root/电脑 adb 用户可用。
2. **ADB 客户端 + 配对 UI**：7 个 Kotlin 文件 + `adb_pairing.cpp`、密钥存储、"shell 权限"页配对流程、无线调试自动启动。
3. **开机自启 + 状态机/文案 + 拆官方路径**：BootCompleteReceiver 移植、`ShellState` 重写与下游文案、移除清单执行、迁移提示、文档更新。

每阶段可独立构建验证；阶段 3 完成后官方路径代码归零。
