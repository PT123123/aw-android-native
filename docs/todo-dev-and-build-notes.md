# Android TODO 功能开发 & 构建问题记录

> 项目：aw-android（分支 feature/native-ui）
> 服务端子模块：aw-server-rust（分支 feature/inbox，HEAD 3576a06）
> 时间：2026-08 末 ~ 2026-09 初
> 约定：**远端优先**——submodule 内不产生本地 commit，本地修改只留工作区；主仓库可 commit。

---

## 一、inbox 依赖解析（结论）

- inbox 功能**完全内嵌依赖 aw-server-rust**：
  - Kotlin 端只是 Retrofit 薄客户端，打 `http://127.0.0.1:5600/inbox/*`；
  - 功能本体是 aw-server-rust workspace 的 `aw-inbox-rust` crate；
  - Android 端经 JNI 在 App 进程内启动 Rust 服务器（`aw-server` crate 的 `android/mod.rs`）。
- 依赖链：`aw-android(mobile/Kotlin) → 同进程 JNI → aw-server-rust(aw-server cdylib) → aw-inbox-rust`。

---

## 二、submodule 切换（remove-aw-webui → feature/inbox）

- `.gitmodules` 的 url = `https://github.com/PT123123/aw-server-rust.git`，aw-server-rust 是 aw-android 的 submodule。
- 从原分支切到远端 `feature/inbox`（`3576a06`）：
  - 该分支把嵌套 submodule `aw-inbox-rust` 变回**内嵌普通 crate**；
  - 移除 aw-query / aw-sync / aw-webui，workspace 成员变 7 个。
- 切换前把本地 `compile-android.sh` 的 Windows 修复备份到 `patches/`，切换后重应用。
- 用户决策（三次）：
  1. **以远端优先**：submodule 内不 commit 修复，只留工作区；
  2. `patches/` 目录删除，compile-android.sh 修复从此**无备份**（用户已知并接受）；
  3. 主仓库 commit `07929bf`（gitlink 更新到 3576a06）。

---

## 三、TODO 功能开发（Android 端）

### 服务端契约（feature/inbox 的 aw-inbox-rust）

- 路由：`GET/POST /inbox/todos`、`GET/PUT/DELETE /inbox/todos/<id>`。
- 写请求需 `X-Device-ID` 头。
- `GET` 只支持 `completed/limit/offset` 参数，**无 tag/search**。
- `due_date` 类型为 `Option<DateTime<Utc>>`：**必须发 RFC3339 完整时间**（`yyyy-MM-ddT00:00:00Z`），不能只发日期。
- `TodoResponse` 字段：id/title/content/completed/priority/due_date/tags/created_at/updated_at/completed_at/version/device_id/deleted/synced_at/conflict。

### 界面参考（aw-qtui，TickTick 式三栏）

- 克隆于 `C:\Users\<user>\AppData\Local\Temp\aw-qtui-ref`（仅参考）。
- **清单用 tags 模拟**：`listId = tag字符串哈希(qHash%1000000+1)`，listId 0 = 收集箱；创建清单不实际写入服务端，tag 随任务自动出现。
- subtasks / recurrence 暂不支持。
- 视图枚举：INBOX / TODAY / NEXT7 / ALL / LIST：
  - INBOX = listId==0；TODAY = 有 due 且 due<=今天；NEXT7 = due<=今天+6；ALL = 全部。
- 排序：未完成 `priority desc → 有期限在前 → due asc`；已完成按 `completed_at desc`。

### Android 端实现（mobile/src/main/java/net/activitywatch/android/todo/）

| 文件 | 作用 |
| --- | --- |
| `TodoModels.kt` | TodoResponse(Create/Update payload)、TodoView 枚举、TodoListInfo、`tagToListId()`、`String.toRfc3339()`；Gson 不序列化 null（只传改动字段） |
| `TodoApi.kt` | Retrofit+OkHttp+Gson lenient 单例，BASE_URL `http://127.0.0.1:5600/`，`X-Device-ID` interceptor |
| `TodoAdapter.kt` | 任务行（复选框+标题+元信息）、已完成折叠头、日期着色（过期红/今天黄） |
| `TodoDetailDialog.kt` | 新建/编辑/删除对话框：清单单选、优先级单选、DatePicker、标签、备注 |
| `TodoFragment.kt` | Toolbar(标题+视图名+未完成数)、视图 chips、清单 chips(彩色圆点)、快速添加行、SwipeRefresh+RecyclerView、FAB 新建清单；过滤/排序客户端内存实现 |

- 布局/资源：`todo_fragment/todo_task_item/todo_detail_dialog/todo_done_header` + drawable `todo_input_bg/todo_task_bg/todo_chip_bg/todo_chip_bg_selected`，深色系 `#0f131a/#1a1d24/#e6e8eb/#4f8cff`。
- 导航接入：`res/menu/activity_main_drawer.xml` 加 `nav_todo`；`MainActivity.kt` Inbox 组加 NavRow。
- **问题**：Edit 工具反复 `Native execution failed`，改用 Python 脚本 patch 文件成功。
- Kotlin 编译验证：修复 2 个错误（HeaderVH 误用构造参数、字段 colorRes 命名）+ 清 2 个 warning 后 `BUILD SUCCESSFUL`。

---

## 四、完整构建的问题链（重点）

> 最终通过 **方案 A（根治，默认不编 OpenSSL）** 解决。以下按出现顺序记录。

### 1. linker-wrapper 依赖 `pipes` 模块，默认 Python 3.14 没有

- `build/linker-wrapper/linker-wrapper.py` `import pipes`，而系统默认 `python` = **3.14.7**（`pipes` 自 3.13 移除）→ `ModuleNotFoundError`。
- 手工把 py 改成 shlex **无效**：`generateLinkerWrapper` 每次构建会重新生成覆盖。
- **解法**：构建前把 Python 3.12 目录前置到 PATH
  `C:\Users\<user>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none`
  并 `gradlew --stop` 重启 daemon 才生效。

### 2. openssl-src 从源码编 OpenSSL（死结 1：Configure 重拼反斜杠路径）

- openssl-sys vendored 触发 openssl-src 全量编译 OpenSSL 3.6.3。
- openssl Configure 检测到 CC 带 triple 前缀的**完整路径**时，会重建出 Windows **反斜杠**路径（`bin\clang.exe`）；MSYS `/bin/sh` 把 `\` 吞掉成 `C:Usersted...` → clang 找不到（Error 127）。
- **解法**：CC 传**裸 basename**（`armv7a-linux-androideabi26-clang.exe`）+ 把 NDK bin 放进 PATH。Configure 生成的 Makefile 里 `CC=$(CROSS_COMPILE)clang.exe`，MSYS 直接从 PATH 找 `clang.exe`。

### 3. CC 变体选择：只有 26 有 `.exe`

- 第一次用 `armv7a-linux-androideabi24-clang`（无扩展名）→ cc crate 在 Windows 按 PATHEXT 找工具 → `failed to find tool`。
- NDK `25.2.9519653` 里 **只有 26 有 `<triple>26-clang.exe`**，24 只有无扩展名 + `.cmd`。
- **解法**：改用 `armv7a-linux-androideabi26-clang.exe` / `aarch64-linux-android26-clang.exe`。

### 4. MSYS make 找不到 `sh` / `clang.exe`（死结 2）

- Configure 成功（`--target=armv7a-linux-androideabi26`）但 `make depend` exit 2。
- 原因：openssl-src 用 `Command::new("make")`（MSYS make）跑 OpenSSL，MSYS make 需要 `sh` 在 PATH 才能执行 recipes；CC 归一化成 `clang.exe` 后也靠 PATH 查找。
- **解法**：cargo.exec 里 PATH 前置 **NDK bin + `C:/msys64/usr/bin`**（两处都要）。

### 5. OpenSSL 串行编译极慢，并行又把系统拖垮

- openssl-src 在 Windows 上**不传 MAKEFLAGS**，make 串行编几百个 C 文件 → 26 分钟级。
- 在 cargo.exec 注入 `MAKEFLAGS=-j8` 加速；但 **-j8 × arm/arm64 双 target = 16 路并行**，把系统内存/IO 拖到无响应（Bash/文件工具全部超时约 1.5h，需重启系统恢复）。
- **教训/结论**：单 target 用 `-j4` 稳妥；双 target 改为串行；不要再双 target 高并行。

### 6. 方案 A（根治）：默认不编 OpenSSL

- `aw-server/Cargo.toml` 的 `[target.'cfg(target_os="android")'.dependencies]` 里删掉：
  `openssl-sys = { version = "0.9.82", features = ["vendored"] }`
- **必补**：`aw-sync-rust/Cargo.toml` 的 reqwest 原本 `default-features=false` 且无 TLS feature，删除 openssl-sys 后若同步要建 HTTPS 会编译失败；补上 `rustls-tls-native-roots`（与 aw-client-rust 一致）。
- 结果：TLS 链路全走纯 Rust rustls（hyper-rustls / rustls-native-certs），`openssl-sys` 彻底移出 Android 依赖树，**OpenSSL 编译时间 = 0**。
- 验证：`cargo tree --target aarch64-linux-android -p aw-server -i openssl-sys` → `did not match any packages`。

### 7. feature/inbox 远端代码自身编译错误（E0061）

- `plugins.rs` 的 `register_all_plugins()` 新增第 3 参数 `todo_db: SharedTodoDb`，但 Android JNI 启动路径 `android/mod.rs:476` 只传 2 参。
- **解法**：在 `android/mod.rs` 的 inbox 连接池初始化成功后，同步初始化 todo 独立连接池（`init_todo_pool()` + `migrate_todo()`，todo.db 独立文件），构造 `SharedTodoDb` 后以 3 参数调用（与桌面版 `main.rs` 对齐）。
- 修后 `BUILD SUCCESSFUL in 46s`，arm + arm64 的 `libaw_server.so` 均产出。

### 8. Windows 命令行/脚本编码坑

- Bash 工具跑的是 PowerShell 包装：`&&`、`cmd /c "...内联 PowerShell 变量"` 会出问题；写 `.ps1/.sh/.py` 文件再执行最稳。
- `.cmd` 里含**中文注释**会被 cmd 按 GBK 误读，把后续命令字符吞坏（`gradlew` → `radlew`、`PATH` → `ATH`）。
- **解法**：`.cmd` 一律**纯英文 + CRLF + ASCII 编码**。

---

## 五、构建命令（已验证可行）

```powershell
# 前置 Python 3.12（linker-wrapper 需要 pipes）
$env:PATH = 'C:\Users\<user>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none;' + $env:PATH
cd C:\Users\<user>\Desktop\aw-android
.\gradlew.bat --stop                 # 改环境后必须重启 daemon
.\gradlew.bat :mobile:cargoBuildArm :mobile:cargoBuildArm64 --console=plain   # Rust .so
.\gradlew.bat :mobile:assembleDebug --console=plain                           # 完整 APK
```

- 产物：`aw-server-rust\target\{armv7-linux-androideabi,aarch64-linux-android}\release\libaw_server.so`；
  APK 在 `mobile\build\outputs\apk\debug\`。
- 本地辅助脚本：`build_rust.cmd`（Rust .so，arm→arm64 串行）、`build_apk.cmd`（assembleDebug）——两者均内置 py312 PATH 前置。

---

## 六、环境约束备忘

- 默认 `python` = 3.14.7（无 pipes）；可用的 Python 3.12 见上。
- NDK：`C:\Users\<user>\AppData\Local\Android\Sdk\ndk\25.2.9519653`。
- `build.gradle` cargo.exec（Windows 分支）已固化：`OPENSSL_SRC_PERL=C:/msys64/usr/bin/perl.exe`、CC 裸 basename(26)、PATH 前置 NDK bin + MSYS bin、`MAKEFLAGS=-j4`。
- submodule 工作区未提交修改：`compile-android.sh`（Windows NDK 修复，无备份）、`aw-server/Cargo.toml`、`aw-sync-rust/Cargo.toml`、`aw-server/src/android/mod.rs`（方案 A + E0061 修复）——**任意 checkout/reset/submodule update 会冲掉，注意保留**。
