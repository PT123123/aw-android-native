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

- 克隆于 `C:\Users\ted\AppData\Local\Temp\aw-qtui-ref`（仅参考）。
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
  `C:\Users\ted\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none`
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
$env:PATH = 'C:\Users\ted\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none;' + $env:PATH
cd C:\Users\ted\Desktop\aw-android
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
- NDK：`C:\Users\ted\AppData\Local\Android\Sdk\ndk\25.2.9519653`。
- `build.gradle` cargo.exec（Windows 分支）已固化：`OPENSSL_SRC_PERL=C:/msys64/usr/bin/perl.exe`、CC 裸 basename(26)、PATH 前置 NDK bin + MSYS bin、`MAKEFLAGS=-j4`。
- submodule 工作区未提交修改：`compile-android.sh`（Windows NDK 修复，无备份）、`aw-server/Cargo.toml`、`aw-sync-rust/Cargo.toml`、`aw-server/src/android/mod.rs`（方案 A + E0061 修复）——**任意 checkout/reset/submodule update 会冲掉，注意保留**。

---

## 七、笔记（Inbox）功能增强规划（仅规划，不改代码）

> **状态更新（2026-09-06）：本节 ⑦-A / ⑦-B / ⑦-C / ⑦-D 已全部实现**，实现与下述规划一致，差异与补充见本节末尾「实现记录」。

> 状态：**纯文档**。下述所有条目均为待实现需求，**不会在本次落盘时改动任何代码**。涉及模块：`mobile/src/main/java/net/activitywatch/android/inbox/`（UI 层）+ `aw-server-rust/aw-inbox-rust`（服务端，`/inbox/*` 路由）。
> 与现有 `todo/` 模块的关系：⑦ 会把笔记转成待办，因此需对接 `TodoSource / TodoApi` 的创建接口。

### ⑦-A · 多级标签（层级 tag）

现状：标签是普通字符串，笔记写入时按逗号/空白拆成多 tag，**无层级**。

目标：tag 支持**路径式层级**（类似 `项目/工作/ActivityWatch`），各段之间以 `/` 分隔。

- **层级格式**：`段1/段2/.../段N`，每段规则沿用现有 tag 规则（去首尾空白、非空）。大小写保留原样（tag 匹配保持大小写不敏感即可，与现行为一致）。
- **服务端**（`aw-inbox-rust`）：
  - 存储层**保持现状**（仍为一个 text 字段，逗号分隔的 tag 串），层级只是字符串约定，**不做列拆分**。
  - `GET /inbox/notes` 新增可选查询参数 `?tag=<路径>`：
    - `tag=项目` → 匹配以 `项目` 或 `项目/...` 开头的**所有子孙**（前缀匹配）；
    - `tag=项目/工作` → 只匹配 `项目/工作` 及其子孙；
    - 空 / 不传 = 全量（现行行为）。
  - 新增 `GET /inbox/tags`（或复用现有端点）：返回**标签树**，供 UI 渲染层级。建议结构：

    ```json
    {
      "tags": [
        {
          "path": "项目",
          "count": 3,
          "children": [
            { "path": "项目/工作", "count": 2, "children": [
                { "path": "项目/工作/ActivityWatch", "count": 1, "children": [] }
            ]},
            { "path": "项目/生活", "count": 1, "children": [] }
          ]
        }
      ]
    }
    ```

  - 前缀匹配边界：`/` 作为段分隔，必须**段边界**匹配，避免 `项目` 误匹配 `项目2`。实现上可`= 精确 OR 以 $path/$ 开头`。
- **Android 端**（`inbox/`）：
  - `NoteEditorFragment` / `NoteDetailFragment` 的标签解析：按逗号拆 tag 后，每个 tag 若含 `/` 视为层级 tag，展示为可点击的**面包屑**（`项目 / 工作 / ActivityWatch`），每段独立可点，点击按该段路径筛选。
  - 列表新增**多级标签筛选入口**（见 ⑦-B）。
  - 输入标签时：`/` 作为普通字符输入（不再被替换、不再触发别的动作）；自动补全可基于 `GET /inbox/tags` 返回的树做段级补全（用户输入 `项目/` 时提示子段）。

### ⑦-B · 多级标签筛选 & 跳转

目标：用户能快速进入「只显示某前缀下的笔记」的筛选视图，并且能逐级向上跳转。

- **进入筛选的入口**（二选一或并行）：
  1. 笔记上标签面包屑的任意一段被点击 → 列表进入「`?tag=该段路径`」筛选模式；
  2. 列表顶部新增「标签」chips 行（与 todo/ 的视图 chips 类似），每个顶层 tag 一个 chip，点击后进入筛选。
- **筛选态的 UI 表现**：
  - 在 `InboxFragment` Toolbar 副标题（或单独的筛选条）显示当前筛选路径，例如 `项目 / 工作`，并带一个「返回上级」按钮（chevron / 左箭头）。
  - **返回上级**：从 `项目/工作/ActivityWatch` → `项目/工作` → `项目` → 顶层（无筛选）。该按钮在顶层时隐藏。
  - 列表调用 `GET /inbox/notes?tag=<当前路径>` 刷新。
- **跳转语义**：筛选导航**不改变笔记本身**，只改变列表查询参数。用户返回顶层即恢复全量视图，不丢数据。
- **状态保持**：筛选路径记在 `InboxFragment` 的 ViewModel（或 `savedInstanceState`）里，配置变更/进程重建可恢复；离开 Inbox 页回到顶层（不持久化到 prefs，保持轻量）。

### ⑦-C · 笔记选项增加「转化为 to do」

目标：在笔记的选项（长按菜单 / 详情页菜单）里新增一个操作：**把该笔记原样迁移为一条 Todo，并删除原笔记**。

- **行为定义**（原子操作）：
  1. 读取笔记内容 `title`/`content`（及现有字段：tags、created_at 等可带则带）；
  2. 调用 Todo 创建接口写入一条 **content 完全相同** 的 Todo（title = 笔记标题，content = 笔记正文）；
  3. 删除原笔记（调用 `DELETE /inbox/notes/<id>`，与现有一致）；
  4. 若 2 成功但 3 失败 → Todo 已创建，原笔记保留（**不二次创建**），在 UI 提示「已转为待办，原笔记删除失败」；
  5. 若 2 失败 → 不执行 3，保留笔记，提示「转换失败：<原因>」。
- **Todo 创建时字段映射**：
  - `title` = 笔记 `title`（若笔记无标题，截取正文首行/前 N 字符作 title）；
  - `content` = 笔记 `content`（含 markdown 原文，**不截断**）；
  - `tags` = 笔记的 tags（原样带上；多级 tag 本来就是普通字符串，**无需特殊处理**）；
  - `priority` = 默认（中）；`due_date` = 空（不强制设期限）；
  - 清单（listId）默认「收集箱」或最近用过的清单，遵循 Todo 模块现有默认值。
- **UI 位置**：
  - `NoteDetailFragment` 工具栏菜单（`menu/note_detail.xml` 之类）新增 `action_convert_to_todo`；
  - `InboxAdapter` 长按菜单（`InboxFragment` 的上下文菜单）新增同名项；
  - 点击后弹**二次确认对话框**：「转为待办并删除该笔记？」，避免误操作；确认后执行上述原子操作。
- **列表刷新**：操作完成后，`InboxFragment` 的列表应从服务端重新拉取（`GET /inbox/notes`），并保留当前筛选态（⑦-B 的 `?tag=` 参数需一并带回，不因转换丢失筛选上下文）。
- **服务端**：**无新增接口**。复用现有的 `POST /inbox/todos`（或 `TodoSource.create(...)`）+ `DELETE /inbox/notes/<id>`。实现全在 Android 端通过**顺序调用**完成；事务一致性按上面「失败保留」的规则兜底。

### ⑦-D · 编辑器工具栏布局修复（输入框工具栏与发送按钮同水平线）

现状：`NoteEditorFragment` 底部的输入框工具栏（含 #、/ 等工具按钮）和发送按钮**不在同一水平线**（错开、错位）。

目标：**工具栏与发送按钮水平对齐到同一行**。

- **仅调整布局，不改动功能逻辑**。
- **布局要求**：
  - 工具栏（工具按钮横排）和发送按钮**同属一个水平行**，垂直居中对齐；
  - 工具栏在上、发送按钮在下，或工具栏在左、发送按钮在右贴底，两种都接受，但**必须共线**，不得出现上下错半格/发送按钮跑到toolbar 上方的视觉 bug；
  - 高度、padding、margin 以视觉上「明显在一条横线」为准；不破坏深色主题、圆角、波纹反馈等既有样式。
- **工具按钮内容（严格）**：
  - 井号键显示文字就是 `#`，**不要加空格**，**不替换**成其他符号/标签图标；
  - 斜杠键显示文字就是 `/`，**不要替换**成「插入链接」「列表」等其他功能；
  - 这两个键的**点击行为**保持现状不变（只改布局/文案显示，不改点击逻辑）。
- **涉及文件**（仅作为实现时参考，本次不动）：`NoteEditorFragment.kt` 及其布局（`res/layout/fragment_note_editor.xml` 或同类）。

### ⑦ 整体实施顺序建议

| 顺序 | 条目 | 依赖 | 备注 |
| --- | --- | --- | --- |
| 1 | ⑦-D 布局修复 | 无 | 纯 UI，风险最低，建议先做先验证 |
| 2 | ⑦-A 多级标签（服务端树端点 + 前缀筛选） | 无 | 服务端 + 客户端，先搭基础能力 |
| 3 | ⑦-B 筛选 & 跳转 | ⑦-A | 依赖 /inbox/tags 与 ?tag= 前缀匹配 |
| 4 | ⑦-C 转化为 to do | ⑦-B（共享刷新/筛选保留逻辑） | 顺序调用现有接口，无新服务端代码 |

- ⑦-A 与 ⑦-D 互相独立，可并行开工。
- ⑦-C 必须等 ⑦-B 完成「筛选态刷新」之后做，因为转换后需保留 `?tag=` 上下文，复用同一刷新路径最省事。
- 全部功能在落盘前**再次明确：本次只写本规划到文档，不改动任何代码**。

### ⑦ 实现记录（2026-09-06，全部四项已实现）

**服务端（aw-inbox-rust，submodule 工作区修改，未 commit）**：
- `db.rs::get_notes_db`：`?tag=` 由精确匹配改为**段边界前缀匹配**（`tags LIKE %"t"%` OR `tags LIKE %"t/%`，LIKE 通配符转义），`项目` 命中 `项目` 与 `项目/...` 全部子孙、不命中 `项目2`。
- `db.rs::get_tag_tree_db` + `models.rs::TagNode/TagTreeResponse` + `lib.rs` 新路由 **`GET /inbox/tags/tree`**：按 `/` 分段建树，count 为含子孙的前缀匹配计数；只统计未删除笔记；children 按路径排序。既有 `/tags`、`/tags/detailed` 保持原样（兼容桌面端）。
- 新增 `tests/tag_tree_test.rs`（内存库直打 db 层，Windows 可跑）：覆盖前缀匹配、树结构计数、删除排除。**测试抓出并修复了一个 bug**：树根若只从「被精确打过的 tag」取，`项目` 这类纯中间节点会从树里消失——根节点已改为从全部前缀路径中取无 `/` 者。
- 旧测试 `integration_test.rs`/`integration_http_test.rs`（引用已移除的 axum/reqwest）、`note_crud_test.rs`（Unix shell 专用）在 Windows 上本就无法编译/运行，与本次改动无关。

**Android 端（mobile/）**：
- `InboxModels.kt`：`TagNodeResponse/TagTreeResponse` + `tagSegments/tagParentPath/tagLastSegment/formatTagBreadcrumb` 工具；`LocalInboxApi.kt` 加 `getTagTree()`。
- `MarkdownRenderer.kt`（⑦-A）：层级 tag 渲染为**每段独立可点**的 span，点击按「到该段为止的路径」筛选（配合服务端前缀匹配）；正文文字不变、整段保持高亮色。
- `NoteDetailFragment.kt`（⑦-A）：详情页标签以面包屑（`项目 / 工作`）展示，每段可点 → 跳列表筛选并关闭详情。
- `InboxFragment.kt`（⑦-B）：顶部新增**标签 chips 行**（未筛选=顶层标签，筛选中=当前路径子标签，带计数）；筛选条显示面包屑 + **↑ 返回上级**（`项目/工作/xx → 项目/工作 → 项目 → 顶层`）+ ✕ 清除；筛选路径存 `savedInstanceState`（配置变更/进程重建恢复，不持久化 prefs）。
- `NoteTodoConverter.kt`（⑦-C，新文件）+ 入口两处（`InboxFragment` 长按菜单、`NoteDetailFragment` 工具栏溢出菜单）：二次确认后**先 `POST /inbox/todos` 再 `DELETE /inbox/notes/<id>`**，按规划兜底（建失败不删 / 删失败提示「已转为待办，原笔记删除失败」）；title 取正文首行剥 markdown 标记（截 50 字），content 原文不截断，tags 原样，priority=中，不设期限；完成后整页重载，**自带 currentTag 保留筛选上下文**。
- ⑦-D：`note_editor.xml` 底部改为**单行**（Markdown 工具栏左 + 发送按钮右、垂直居中），快速笔记弹层（`InboxFragment` 程序化布局）同步对齐。
- **后续调整（2026-09-06 用户反馈，均已实现）**：
  - 工具栏键语义修正（推翻本节「点击行为保持现状不变」的旧约束）：**井号键插入字面 `#`、斜杠键插入字面 `/`**（配合 `#标签` 与层级 tag 的 `a/b` 输入），原斜体键（`I`，插 `*` 包裹）移除，标题循环（cycleHeading）不再挂键；B/•/1. 等 Markdown 键保留。涉及 `note_editor.xml`（id 改 `mdHash`/`mdSlash`）、`NoteEditorFragment`、`InboxFragment.buildMarkdownToolbar`、`MarkdownTextActions.insert()`（新增）。
  - 输入框按内容生长：编辑面板 min 4 / max 10 行、快速弹层 min 3 / max 8 行，超限内部滚动；随后按反馈**去掉输入框与工具栏间的弹性留白**，工具栏行紧跟输入框，弹层高度改 wrap_content 紧凑包裹。
  - 快速发送后走 `refreshAndScrollToNote` 跳转定位并高亮新笔记（不属于当前筛选/搜索时退回整页刷新）。
  - TODO 快速添加弹层同步紧凑化：单行输入 + 按钮行紧随其下，弹层 wrap_content（不再固定半屏）。
- 规划中「基于标签树的段级输入自动补全」为可选项，本次未实现。

**验证**：`cargo check -p aw-inbox-rust` 通过；`cargo test --test tag_tree_test` 3/3 通过；`gradlew :mobile:compileDebugKotlin` BUILD SUCCESSFUL；随后重跑 `cargoBuildArm + cargoBuildArm64 + assembleDebug` 产出含修复的 .so 与 APK。
> ⚠️ 新坑：rustJniLibs 里换了新 .so 后，AGP 的 merge/strip native libs 任务可能仍报 UP-TO-DATE（输入快照未失效），**APK 打进的是旧库**。验证方法：`sha1sum build/rustJniLibs/android/<abi>/libaw_server.so` 对比 `build/intermediates/stripped_native_libs/.../libaw_server.so`，不一致即中招；解法：删 `build/intermediates/{merged_jni_libs,merged_native_libs,stripped_native_libs}` 后重新 `assembleDebug`。

---

## 八、（预留）
