# :shizuku-server — 内嵌的 Shizuku server

Muse 内嵌的特权 server（shell uid，`app_process` 拉起，进程名 `muse_server`）。源码 vendor 自 Shizuku 上游，fork 后 manager 身份指向 Muse 本包。对外协议与官方 Shizuku 完全一致：server 起来后把 binder 推到客户端 manifest 里的 `${applicationId}.shizuku` provider，客户端照常用 `dev.rikka.shizuku:api`。

## vendor 基准（fork 的 diff 基线）

- Shizuku 主仓库：`b844bc491f1790c72328e1a8e5b2349f8978f0ea`（master，13.6.0 线，检出于 2026-11-08）
- Shizuku-API 子仓库：`a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`

## vendor 清单（原样复制，包名不改）

| 来源 | 落点 `shizuku-server/src/main/` |
|---|---|
| 主仓库 `server/src/main/java/rikka/shizuku/server/` | `java/rikka/shizuku/server/`（10 个文件，含 `api/`、`ktx/` 子包） |
| 主仓库 `starter/src/main/java/moe/shizuku/starter/` | `java/moe/shizuku/starter/`（ServiceStarter、util/IContentProviderCompat） |
| 主仓库 `common/src/main/java/moe/shizuku/common/util/` | `java/moe/shizuku/common/util/`（BuildUtils、OsUtils） |
| API 仓库 `server-shared/src/main/java/` | `java/`（Service、ConfigManager、UserService、UserServiceManager 等基类与 util） |
| API 仓库 `aidl/src/main/aidl/` | `aidl/`（IShizukuService 等 4 个接口） |
| API 仓库 `shared/src/main/java/` | `java/`（共享常量与工具） |

除下列 fork 点外不允许任何改动。

## fork 点（每处 `// MUSE:` 标注）

1. `rikka/shizuku/server/ServerConstants.java`：`MANAGER_APPLICATION_ID = "io.github.markhunter123.muse"`；`PERMISSION_MANAGER = "io.github.markhunter123.muse.permission.MANAGER"`（`PERMISSION`，即 `moe.shizuku.manager.permission.API_V23`，保持不变——并存期官方 server 的推送照常可达）
2. `rikka/shizuku/server/ShizukuService.java`：`main()` 里进程名改 `muse_server`；删除 RishConfig 引用与 import（不 vendor rish）。构造器的 manager 存在性检查与 `checkCallerManagerPermission` 逻辑不改——常量指向 Muse 后语义自动成立（manager 永远在、appId = Muse）
3. `rikka/shizuku/server/ShizukuConfigManager.java`：配置文件 `/data/user_de/0/com.android.shell/muse_shizuku.json`（避开官方 `shizuku.json`，两个 server 并存时互不写坏）
4. 授权：对自身 uid 自动放行，不走 REQUEST_PERMISSION 弹窗

许可证：Apache-2.0。上游：https://github.com/RikkaApps/Shizuku 、https://github.com/RikkaApps/Shizuku-API （文件头保留）。
