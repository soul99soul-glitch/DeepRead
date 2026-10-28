# Android 补全 iOS 最新功能的增量计划（2026-10-17）

## 0. 基线与授权

| 项 | 值 |
| --- | --- |
| iOS 基准 | `amberagent-ios` `main` @ `657976c3`（2026-10-17 20:36，「快速判断入口去掉 Jev 品牌名」） |
| Android 基准 | 本仓 `main` @ `969da63b`（2026-10-14，generative-ui 自适应任务 UI）+ 工作区 WIP |
| 上一份台账 | [2026-09-09-android-ios-parity-plan.md](./2026-09-09-android-ios-parity-plan.md)（基线 iOS `1023e8a`，其 D01–D20 已大部分落地，本文只处理**之后**的增量） |
| 读取授权 | 用户已明确授权本次**只读**遍历 `amberagent-ios` 源码与提交历史；未复制任何 iOS 代码，结论与计划全部落在本仓 |

本文只回答一个问题：**iOS 在 2026-09-09 之后新增了什么，Android 现在落后哪些，怎么补。**

## 1. 结论摘要

iOS 自 2026-09-09 起有 104 个提交（Android 同期 58 个）。但绝大多数属于三条主线，其中**两条 Android 已经跟平或不适用**：

- **跟平**：产物架、顶栏活动岛/回顾、Jev 九用途适配器、Mac Gateway、Intelligent UI 自适应任务 UI、Novel 独立产品面、免费搜索多引擎、附件大图、会话分页、主题包。
- **架构不同、不追**：iPhone 自控的传输栈（XCTest runner + NE 隧道 + vendored `idevice`，`native/iphone-control` 约 5.9 万行）、Live Activity/灵动岛 v3、Watch 1.1、音频/定位后台保活、SwiftUI 专属 P0–P7 渲染性能条目。

**真正的缺口集中在两处**：

1. **Jev 本体落后一个版本代际**——iOS 已经到 `jev-1.14` + 图片 assets + Box + Gateway `stateParts v4` + 4 家供应商 + 13 个用途；Android 停在 2 方言、纯文本、9 用途。
2. **手机自控的工具面与验收语义落后**——同一套无障碍能力，iOS 用 10 个 `phone_*` 工具覆盖「应用白名单 / 固定流程 / 列表采集 / 有界结束 / 四类验收」，Android 只有 15 个 `screen_*` 低层工具 + 一个预算很小的 `screen_run_goal`。

外加两个中等缺口和一个文案缺口：工具循环上限（512 vs 8192）、跨对话活动卫星、timeline 视频/Markdown 配图、Jev 入口品牌文案。

## 2. 逐域差异台账

结论列：`已对齐` = Android 已有等价能力；`架构差异` = 不移植；`缺口` = 需要补。

### 2.1 手机自控 / 无障碍

| iOS 能力 | iOS 证据 | Android 现状 | 结论 |
| --- | --- | --- | --- |
| 本机自控传输栈（XCTest runner、NE 隧道、usbmuxd、配对、自发现） | `native/iphone-control/`、`iosApp/PhoneControlRunner`、`iosApp/PhoneControlTunnel`、`iosApp/PhoneControlRuntime/` | 系统 `AccessibilityService`：[AmberAccessibilityService.kt](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/main/java/app/amber/core/automation/AmberAccessibilityService.kt) + [AccessibilityController.kt](/Users/arquiel/Downloads/AI/amberagent-Android/core/automation/api/src/main/kotlin/app/amber/core/automation/AccessibilityController.kt) | 架构差异，**不移植**（iOS 必须外挂 runner，Android 无障碍是系统原生） |
| `phone_status` / `phone_apps` / `phone_add_app`（持久应用白名单 + 会话 scope 报告） | `IOSPhoneControlToolCatalog.swift` | 只有每次调用传 `package_name`；全仓无 `allowedPackages` 类白名单 | 缺口 |
| `phone_collect_list`（逐屏采集、重叠去重、覆盖边界报告） | 同上 + `docs/iphone-automation-improvements-20261014.md` Phase 2 | 无 | 缺口 |
| `phone_run_flow`（1–12 步固定语义流程，逐步不请求 Jev） | 同上 Phase 3 | 无；只有需要 Jev 的 `screen_run_goal` | 缺口 |
| `phone_stop`（结束有界会话、释放 owner） | 同上 | 无 | 缺口 |
| `phone_run_goal` 预算 | 默认 15 步 / 上限 30 / 180 秒 | `JevScreenGoalRunner` 默认 6、`coerceIn(1, 8)`、`max_duration_ms` 上限 30 000ms | 缺口（预算小一个量级） |
| 动作后验收四类条件（visible / enabled / absent / value_equals + 最多 4 次重读、2 秒预算） | `phone_act` schema、`IOSPhoneGoalVerification.swift` | 只有 `verify_text` 文本轮询（`verify_timeout_ms` ≤ 15 秒） | 缺口 |
| 端侧 OCR（`include_screen_text`，默认不截图） | Phase 2 | 走 VLM：`vlm_task` + `screen_screenshot`（多一次大载荷请求） | 缺口（成本问题，非能力问题） |
| 常用任务：参数化流程保存 + 运行入口 | Phase 3 | 无 | 缺口 |

### 2.2 Jev（快速判断模型）

| iOS 能力 | iOS 证据 | Android 现状 | 结论 |
| --- | --- | --- | --- |
| 固定 `jev-1.14.0` + 「测试并升级」 | `docs/product/jev-1.14-upgrade-plan.md` | `JevLimits.DEFAULT_MODEL` 无 1.14 概念 | 缺口 |
| 图片判断：原生 `assets`、`TypeSafe-Danger-Zone-Images`、2 MiB 图片预算、图片不缓存 | 同上 Phase 1 | `core/jev` 全模块无 `assets` / 图片路径 | 缺口 |
| Box 解析（归一化四边 + confidence，禁止凭 Box 点击） | 同上 | 无 | 缺口 |
| Gateway `/v4/ai/decision-model` + `stateParts` 规格 v4 | 同上 | `JevClient` 只有 systemone 与 evaluation-model 两种编码 | 缺口 |
| 出站供应商 4 家：systemone / vercelGateway / perplexityDecisions / liquidSystemone | `IOSJevAPIStyle`（`83086ff1`，+240 行 client、+284 行设置） | `JevApiMode { TYPESAFE, VERCEL }` | 缺口（缺 2 家） |
| 用途 13 个（含 `subagentIntent` 意图路由、`approvalTriage` 审批分诊、`scriptJudgment` 脚本判断、`phoneVerification`/`phoneActions` 拆分） | `IOSJevSettings.swift` | `JevPurpose` 9 个（手机合并为 `SCREEN_AUTOMATION`） | 缺口（缺 3 类 + 手机未拆分） |
| 8 个数据外发范围（含 `modelMetadata`、`phoneScreenshots`） | `IOSJevDataScope` | `JevDataScope` 6 个 | 缺口 |
| 入口品牌化：「快速判断模型」 | `657976c3` | 「Jev 判断服务」，6 语言 strings | 缺口（文案） |

### 2.3 运行时与 UI

| iOS 能力 | iOS 证据 | Android 现状 | 结论 |
| --- | --- | --- | --- |
| 工具循环上限默认 128、可配 128–8192 | `ed120270`、`IOSExecutionPreferenceKeys.chatMaxToolResumeCount` | `MAX_AGENT_TOOL_LOOP_STEPS = 512`，选项 64/128/256/384/512 | 缺口 |
| 跨对话活动卫星（其他对话 awaitingUser/failed/completed，按钮变形提示） | `ConversationActivityCenter.swift`、`ChatTopBarTrailingDock.swift`、`docs/product/chat-topbar-dock-plan.md` | 顶栏已有岛/产物架/回顾（[ChatTopBarPanels.kt](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatTopBarPanels.kt)），无跨对话活动中心 | 缺口 |
| 产物架（图片/文件/HTML/小程序/网页） | `ChatArtifactShelf*.swift` | [ChatShelfItems.kt](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatShelfItems.kt) 五类 + `ChatShelfPanelHost` | 已对齐 |
| 顶栏活动岛 + 会话回顾面板 | `7531425d`、`c9e4ea5c` | `ChatIsland`、`ChatRecapPanelContent` | 已对齐 |
| Timeline 富媒体：搜索图片图集 / 视频卡片 / Markdown 配图 / 预留框稳定 | `docs/plans/2026-10-17-timeline-rich-media.md`（Phase 1 进行中） | 有 `SearchImageBlock`；无 media3/ExoPlayer，无视频卡片；Markdown 配图无原生媒体块 | 部分缺口 |
| 生成式界面流式骨架揭示 | `3b047038` | `IntelligentUiStreamingPreviewCard`、`GenerativeWidgetCard` 骨架 | 已对齐 |
| 自适应原生任务 UI | `c52b5855` | `969da63b` | 已对齐 |
| 模型议会工具过程可视化 | `038ed2de` | `feature:modelcouncil` + `CouncilRoomTaskReporter` + `JevCouncilPoolRanker` | 已对齐 |
| Live Activity / 灵动岛 v3、Watch 1.1、音频/定位保活 | `71172df9`、`16c7f097`、`8acd44a5` | Android 无对应系统面 | 架构差异，**不追** |
| Mac Gateway 配对/推送/外网入口 | `54696837`、`ce182ca1` | `feature/macgateway` + `bbae74b1`/`04d77a8f` | 已对齐 |
| Novel 独立应用 / 主题 / 后台续跑 | `b96682c6`、`4782e5a0` | `novel` flavor（`app.amber.novel`）+ `NovelWorkspaceGhostwriteJobs` | 已对齐 |

### 2.4 Android 侧已领先、勿回退

`wm_*` 工具面（`wm_eval`、`wm_network_inspect`、`wm_signed_fetch`、`wm_fetch_replay`、`wm_profile_synthesize`、`wm_recipe_candidates`）比 iOS 更宽；Jev 九用途适配器已覆盖议会池排序（iOS 无独立适配器）；Novel 章节快照复用；免费搜索多引擎并发。

## 3. 工作包

人日按熟悉该模块的开发者估算，含实现、定点测试和一次修订；不含等待与设备排队。

| 工作包 | 范围 | 优先级 | 依赖 | 人日 |
| --- | --- | --- | --- | --- |
| W01 | 工具循环上限 8192 + 设置项 + 预算提示文案 | P0 | — | 0.5–1 |
| W02 | Jev 入口改名「快速判断模型」+ 6 语言 strings | P0 | — | 1–2 |
| W03 | Jev 供应商扩到 4 家（Perplexity Decisions、Liquid systemone） | P1 | — | 2–3 |
| W04 | Jev 1.14：固定版本 + 「测试并升级」+ Gateway stateParts v4 | P0 | — | 4–6 |
| W05 | Jev 图片判断：assets、实验头、2 MiB 图片预算、不缓存、`PHONE_SCREENSHOTS` 范围 | P0 | W04 | 4–6 |
| W06 | Jev Box 解析（归一化四边 + confidence，禁止凭 Box 生成动作） | P1 | W05 | 2–3 |
| W07 | Jev 用途补齐：`SUBAGENT_INTENT`、`APPROVAL_TRIAGE`、`SCRIPT_JUDGMENT`；手机拆成 `PHONE_VERIFICATION` / `PHONE_ACTIONS` | P1 | — | 4–6 |
| W08 | 手机自控应用白名单 + `screen_status` / `screen_apps` / `screen_add_app` / `screen_stop` | P0 | — | 3–5 |
| W09 | `screen_run_goal` 预算对齐（默认 15 / 上限 30 / 180 秒）+ 验收四类条件 + 重读预算 | P0 | — | 3–4 |
| W10 | `screen_run_flow`（固定语义流程，逐步不请求 Jev） | P1 | W09 | 3–4 |
| W11 | `screen_collect_list`（逐屏采集、重叠去重、覆盖边界） | P2 | W09 | 3–4 |
| W12 | 端侧 OCR 观察（`include_screen_text`，可选，替代部分 VLM 调用） | P2 | W09 | 5–7 |
| W13 | 跨对话活动中心 + 顶栏卫星变形 | P2 | — | 4–6 |
| W14 | Timeline 视频卡片 + Markdown 原生配图 + 预留框稳定 | P2 | — | 5–8 |
| W15 | 设备回归与文档收尾（真机无障碍旅程、文案回归、计划台账回填） | 收尾 | 全部 | 3–5 |

合计 **47–74 人日**。P0 部分（W01/W02/W04/W05/W08/W09）约 **16–24 人日**，两人并行约 **2–3 周**出可安装内测包。

## 4. 每包实现与验收

### W01 工具循环上限 8192

- 入口：[PreferencesStore.kt](/Users/arquiel/Downloads/AI/amberagent-Android/core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt:309) 的 `MAX_AGENT_TOOL_LOOP_STEPS = 512`、[SettingAgentExecutionPage.kt](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExecutionPage.kt:54) 的选项表。
- 只改常量、选项表（追加 1024/2048/8192）与既有预算提示文案，`coerceIn` 与 `RunKernel` 不动。
- 验收：设置可选到 8192 并持久化；旧值不被改写；`DefaultRunKernelTest` 既有的步数上界用例仍通过；新增一个「设置 8192 时内核拿到 8192」的定点用例。
- 回滚：还原常量与选项表，无数据迁移。

### W02 入口改名

- 改 `values*/strings.xml` 中 `setting_page_jev*`、`setting_jev_*` 面向用户的主标题文案为「快速判断模型」。**不改**内部类名、包名、`JevPurpose` 枚举与协议字段，避免和 W03–W07 冲突。
- 验收：6 语言资源键齐全（`values`, `values-zh`, `values-zh-rTW`, `values-ja`, `values-ko-rKR`, `values-ru`）；设置页截图复查；无残留用户可见的「Jev」入口文案。

### W03 供应商扩到 4 家

- 入口：`core/jev/JevModels.kt` 的 `JevApiMode`、`JevClient` 的编码/解码分支、`jevEndpointFor`。
- 每新增一家 = 一个 `JevApiMode` case + 一条端点/模型 id 注册表条目 + 编码分支；只在遇到全新请求契约时才动客户端主干。
- 验收：4 种 wire 各有一组编码快照与解码用例（含 confidence 落在 `providerMetadata`、缺失 confidence 的容忍、分数越界拒绝）；设置页能在 4 家间切换且不丢已选模型。

### W04 Jev 1.14 与 stateParts v4

- 按 iOS 已核对过的官方契约实现：`/v4/ai/decision-model`、`stateParts`、规格版本 4；原生 systemone 纯文本协议保持原样。
- 设置页提供「测试并升级至 1.14」：探测成功且返回版本一致才固定；`jev-preview` / `jev-latest` 视为浮动别名，不计为已验收版本。
- 验收：Gateway 新旧协议各自的编码用例；固定版本持久化与运行时消费；升级失败不改变原固定版本。

### W05 图片判断

- 原生侧：`assets` 引用 + `TypeSafe-Danger-Zone-Images` 头，PNG/JPEG，每边 280–1568；应用级 2 MiB 上限，**不放宽**纯文本 64 KiB 限额；图片请求不缓存。
- Gateway 侧：原状态位置转 base64 file part；不支持 Box 时明确拒绝，不静默丢弃。
- 权限：新增 `PHONE_SCREENSHOTS` 数据范围，**默认关闭**；未授权分节及其图片都不得进入批请求。
- 生产链路：在真实调用 Jev 的手机路径上惰性获取绑定观察 id 的新鲜截图；截图获取失败、观察不匹配、权限撤销或取消后，禁止继续执行旧候选。
- 验收：图片权限矩阵（开/关 × 4 家供应商 × 两种 wire）；字节预算精度用例；唯一候选零截图零 Jev 用例；取消后不派发用例。

### W06 Box 解析

- 解析归一化四边与 confidence 并校验范围；目标不存在时服务端也会返回 Box，因此 **Box 永不作为发起点击的依据**，只作为候选定位的辅助证据。
- 验收：合法 Box 解析；非法答案、越界坐标、题内不支持的引用一律拒绝；断言生成动作的路径不读取 Box。

### W07 用途补齐

- `SUBAGENT_INTENT`：子代理 spawn 时按已有 catalog 角色做意图校验，附 alignment 标记。
- `APPROVAL_TRIAGE`：对待批工具**只标注中性事实**，不代替用户决策；与现有 `JevAutoApprovalGate`（自动批准复核）是两件事，不得合并。
- `SCRIPT_JUDGMENT`：仅对工具输出做判断，数据范围只给 `TOOL_OUTPUT`。
- 手机拆分：`PHONE_VERIFICATION` 与 `PHONE_ACTIONS` 分别声明所需范围与置信度下限。
- 验收：三用途各有 shadow/active/off 三态用例；每用途的 `requiredScopes` 与设置页可用性判断共用一处定义；`APPROVAL_TRIAGE` 无任何写回审批结果的路径。

### W08 应用白名单与结束语义

- 白名单持久化在设置层，随任务立即生效且后续任务保留；`screen_run_goal` / `screen_run_flow` 只在前台等于白名单内应用时执行，前台切换即停止。
- `screen_status` 报告可用性、准备工作与当前 scope；`screen_apps` 分页查询已安装应用（query/offset/limit，含 `has_more`）；`screen_add_app` 只写白名单，不启动应用。
- `screen_stop` 结束本次 run 的控制权与 owner，**不撤销**已发出的点击/输入。
- 验收：未授权应用拒绝并提供 `no_scope` 引导；加入白名单后同任务立即生效；前台切走即停止；重复调用 `screen_stop` 幂等；`screen_owner` 释放后不再接受动作。

### W09 预算与验收语义

- 把 `JevScreenGoalRunner` 的 `maxSteps` 默认提到 15、上限 30，总预算 180 秒；`ScreenAutomationTools.screen_run_goal` 的 schema 同步。
- 新增可选 `expectation`：`visible` / `enabled` / `absent`（须附仍可见 anchor 且此前确实出现过目标）/ `value_equals`（仅非敏感输入框）；动作后自动重读，最多 4 次观察、2 秒继续观察预算，**等待不重放动作**。
- 保留 `verify_text` 兼容，不删除既有参数。
- 验收：四类条件各自的满足/未满足/超时用例；`enabled` 要求唯一可见目标；`absent` 缺少 anchor 被拒绝；重读次数与预算可观测；预算耗尽后不回放动作。

### W10 `screen_run_flow`

- 1–12 步固定语义流程，复用现有候选唯一定位与动作后核验；后一步消费上一动作的新树；**不保存 ref/坐标**；每步最多派发一次。
- 交回时保留已执行步骤与最新证据，并阻止同轮后续工具（复用既有 handback 通道）。
- 验收：多步成功、中途页面偏离（转交回）、未知结果停止、60 秒预算耗尽；断言无 ref 复用。

### W11 `screen_collect_list`

- 唯一已观察滚动容器内逐屏滑动采集；仅按稳定 identifier 合并相邻页可确认的重叠；匿名同名行保留；报告 `observed_screens_only` 覆盖与停止原因。
- 验收：重叠合并、重复稳定 id 交回、无进展停止、数量与滑动次数上界。

### W12 端侧 OCR 观察

- 在 `screen_read_ui` 增加可选 `include_screen_text`：一次截图 + 端侧 OCR，返回文字、置信度与归一化位置，绑定同一截图 observation id；**不伪造 ref**。默认观察不额外截图。
- 验收：OCR 文字/坐标与截图一致；旧截图失效后拒绝；图标盲区给出明确观察建议；未开启时不产生额外截图。

### W13 跨对话活动卫星

- 新增 App 级活动中心：汇总**非当前对话**的 running / awaitingUser / failed / completed，优先级 awaitingUser > failed > completed，同一对话只留最新一条。
- 顶栏右上停靠位平时是产物架按钮，有其他对话需注意事件时变形为卫星；空对话无提醒时隐藏。
- 验收：三条优先级用例；切回目标对话后提醒清除；不改变现有产物架与回顾面板行为。

### W14 Timeline 富媒体

- 视频：`Video` part 单独可显示、可与文字混排、封面 + 播放按钮；点击才创建播放器，关闭即停止，保持原位卡片与滚动身份；不支持的可播放来源明确失败可重试。
- Markdown 配图：完整图片语法转原生媒体块，保留前后文字、链接图片与代码块字面内容；流式未闭合语法不发请求。
- 预留框：用户图/搜索图/Markdown 图加载前后框尺寸稳定；缩略图按显示尺寸降采样。
- 验收：流式与终态、历史重开、来源打开、失败重试、横竖图与动态字号；滚动位置不被抢。

### W15 收尾

- 真机无障碍旅程（白名单、goal、flow、collect_list、stop）、6 语言文案回归、设置页窄屏与辅助字号截图；回填本台账状态。
- 无法完成的设备验收必须单列，不得用 JVM 测试冒充真机结论。

## 5. Phase 划分、review gate 与执行顺序

每个 phase 是一个可独立验收的交付单元。**每个 phase 完成后必须过一次 subagent review**，检查三件事：逻辑是否闭环、调用链路是否完整（设置 → 运行时 → 工具/内核 → 持久化）、UI 细节（错位、对齐、边距间距、字号与点击区尺寸）。review 发现的确认问题在本 phase 内修完才进入下一个 phase；未确认的怀疑记入台账，不顺手改。

| Phase | 工作包 | 交付 | 人日 | review gate |
| --- | --- | --- | --- | --- |
| P1 配置与供应商 | W01、W02、W03 | 循环上限 8192、入口改名、4 家供应商 | 3.5–6 | 资源键完整性 + 设置页渲染 + 4 种 wire 编解码 |
| P2 Jev 代际 | W04、W07 | 1.14 固定版本、stateParts v4、12 用途 | 8–12 | 协议版本/用途接线 + 设置页可用性判断 |
| P3 Jev 多模态 | W05、W06 | 图片判断、Box 解析 | 6–9 | 权限矩阵 + 字节预算 + 「禁止凭 Box 点击」 |
| P4 手机自控闭环 | W08、W09 | 白名单 + 5 个工具 + 预算与验收 | 6–9 | owner/取消/前台切换 + 四类验收 |
| P5 手机自控深化 | W10、W11 | 固定流程、列表采集 | 6–8 | 无 ref 复用 + 覆盖边界 |
| P6 UI 深化 | W13、W14 | 跨对话卫星、富媒体 | 9–14 | 不抢滚动位置 + 预留框稳定 |

W12（端侧 OCR）不单独占 phase，作为 P5 的可选项按实测成本决定，见第 8 节。

依赖约束：W05 依赖 W04；W06 依赖 W05；W10 依赖 W09。其余可并行。

## 6. 明确不做

- iPhone 自控的传输栈（`native/iphone-control`、Runner、NE 隧道、vendored `idevice`）：Android 用系统无障碍，无对应需求，移植等于自造第二套控制后端。
- Live Activity / 灵动岛 v3、Watch 1.1、音频与定位后台保活：无对应系统面；Android 侧的等价物是 `feature:live` 与 `feature:wear`，已存在。
- iOS 专属 SwiftUI 渲染性能条目（临界阻尼弹簧、EDR 提亮、120Hz 思考框跟随）：Compose 渲染模型不同，按本仓既有性能台账处理，不照搬。
- 不把「都用了 Markdown/JSON」当作互通依据；跨端交换仍走既有真实格式验证路径。

## 7. 验证策略

1. 每包先写定点 JVM 测试（`app/src/test` 或模块内 `src/test`），再 `:app:compileFullDebugKotlin` / 对应模块 `compile`。
2. 涉及设置与资源的改动跑 6 语言资源检查；涉及 `Tool` 声明的改动跑既有 `ToolProfileFilterTest` / `PermissionDecisionResolverTest` / `ExecutionPolicyCoveragePinTest`。
3. 无障碍相关必须有真机证据（模拟器无障碍树不完整），按 `docs/verification/` 既有格式记录。
4. 不改默认聊天 kernel，不新建第二套任务数据库，不一次打开全部 flags。

## 8. 待确认决策
1. **W12（端侧 OCR）**：Android 现有 `vlm_task` 已能读图标，OCR 的收益主要是省一次大载荷请求。是否值得 5–7 人日，需要先测一轮真实调用成本再决定。
2. **W14 视频**：会新增 `media3` 依赖。本仓此前按「自动新增依赖需决策」的约定处理，需明确授权后才开工。
3. **W07 `APPROVAL_TRIAGE`**：只标注不决策，价值依赖用户是否真的在批改界面停留；建议先在 shadow 模式观察一段用量再决定是否 active。
4. **P0 范围确认**：本文把 W01/W02/W04/W05/W08/W09 定为 P0（16–24 人日）。若只能先做一件，建议 W08+W09（手机自控闭环最影响实际可用性）。

## 9. 执行记录（2026-10-17）

每个 phase 完成后过一次 subagent 只读 review（逻辑闭环 / 调用链路 / UI 细节），确认问题在本 phase 内修完才进入下一个。

### Phase 1 配置与供应商 — 完成

- W01：`MAX_AGENT_TOOL_LOOP_STEPS` 512 → 8192，选项表补 1024/2048/8192（保留 384，避免已有选中值落空）。
- W02：6 语言把入口从「Jev 判断服务」改为「快速判断模型」/「快速判断」，内部类名与协议字段不动。
- W03：`JevApiMode` 增加 PERPLEXITY / LIQUID，复用 systemone 信封；新增 `perplexityModel` / `liquidModel` 与 `modelFor` / `withModel` 助手；`jevDefaultModelFor` / `jevConnectionTestModelFor` 收敛模型解析。
- UI：API 模式从 4 等分分段控件改为行尾 `Select`——`Perplexity` 在 320dp 上会被省略号截断；两段 supporting 文案改为不重复 Select 内容的短句。
- review 结论：无必须修；2 条建议修已处理（补内核 clamp 用例、文案瘦身）。遗留决策：**每个供应商共用同一个 API Key 槽**，切供应商时旧 Key 会发到新端点（靠 401 → authPaused 暴露），属既有形态，不判为本轮回归。
- 验证：`:core:jev:testDebugUnitTest`、`:core:settings:testDebugUnitTest` 全绿；新增 4 个供应商用例 + 3 个上限用例。

### Phase 2 Jev 代际 — 完成（W04）

- VERCEL 契约从 `evaluation-model` 迁到 `decision-model`：端点 `{base}/decision-model`、body `{stateParts:[{type:"text",text:<state JSON>}], questions}`、头名 `ai-decision-model-specification-version`。
- 版本固定：`DEFAULT_MODEL` / `CONNECTION_TEST_MODEL` / `VERCEL_DEFAULT_MODEL` 全部指向 1.14；模型对话框新增「使用推荐版本 jev-1.14.0」，仅在生效模型不是推荐版时出现。
- `JevPolicy.CURRENT_POLICY_VERSION` 2 → 3，旧缓存随契约变更失效。
- review 结论：1 条必须修（6 语言 `setting_jev_base_url_desc` 仍写 `/evaluation-model`，已改）；4 条建议修已处理（推荐按钮条件、残留注释/测试名、转义回归用例）；1 条按计划降级（未实现「探测成功才固定」，改为「给定推荐值 + 既有连接测试验证」，已记于此）。
- 验证：`:core:jev` 24 个用例、`JevStatusToolTest`、全量 `:app:testFullDebugUnitTest` 通过（仅剩 2 个与本轮无关的既有 Antigravity OAuth 失败）。

### Phase 3 手机自控闭环 — 完成（W08 + W09）

- 白名单：`ScreenAutomationSetting(allowedPackages)` 落在 `AgentRuntimeSetting`，空集 = no_scope；`screen_status` / `screen_apps` / `screen_add_app` / `screen_remove_app` / `screen_stop` 五个工具；`screen_run_goal` 在**拉起应用之前**校验白名单。
- 用户可见撤销：设置 → Agent 执行 新增「手机自控授权应用」行，弹窗列出自名单并支持逐个撤销。
- 预算：`screen_run_goal` 默认 6→15 步、上限 8→30、deadline 15s→180s；`JevBudget.SCREEN_AUTOMATION_MAX_REQUESTS` 24→150（否则第二个 goal 必然中途撞预算）。
- 验收语义：`expectation` 四类（visible / enabled / absent / value_equals），最多 4 次观察、2 秒预算、**等待不重放动作**；`absent` 要求动作前目标确实可见；`value_equals` 排除密码遮罩标签；回执带 `observations` 与 `elapsed_ms`。
- 可测试性：四类判定抽成 `ScreenExpectationProbe` + `evaluateScreenExpectation` 纯函数，9 个 JVM 用例覆盖假通过、歧义、密码遮罩、缺参。
- 登记：`Capability`（add_app → SCREEN_AUTOMATION，其余 → SCREEN_CONTROL）、`ToolRegistry`（add_app 显式 mutates、stop 显式非 mutates）、policy pin、设备测试先授权再接跑。
- review 结论：**BLOCK** 1 条（`stopped` 曾是 Koin 单例上的进程级布尔，一次 stop 会永久关闭全 App 屏幕自控，已改为按 run 键控且无 run 边界时拒绝）；5 条建议修已处理（add_app 同样受 stop 约束、白名单可撤销、预算不自洽、absent 假通过、value_equals 遮罩）。
- 明确降级并记录：`screen_stop` 冻结的是本 run 的 `screen_run_goal` 与白名单变更；低级手势工具是跨 run 复用的 `val` 实例，无法界定 run，仍由每次调用审批把关（工具描述已如实写明）。

### Phase 4 Jev 多模态 — 完成（W05 + W06，协议与接线层）

- 传输：`JevImageAsset`（reference / format / data / 出图尺寸，280–1568 每边校验）；原生方言写 `assets` 并置 `TypeSafe-Danger-Zone-Images: true`；网关把 state 文本按参考标记切成 `stateParts`（text / file / text）。
- 能力面：`jevSupportsImages` / `jevSupportsBox`；Perplexity 与 Liquid 显式拒收图片，网关显式拒收 Box——不把资产按 systemone 形状发过去换一个 400。
- Box：`JevQuestion.Box` + `JevAnswer.Box`，四边 0..1、不得倒置；**永不作为发起点击的依据**。
- 预算与缓存：带图请求单独放行到 2 MiB（纯文本仍守 64 KiB）；图片请求不缓存（旧画面等于过期观察）；run 预算只计"状态字节"，不计 base64 载荷。
- 生产接线：`JevScreenGoalRunner` 在用户单独授权 `SCREEN_SCREENSHOTS` 且宿主已有 MediaProjection 会话时才惰性截图；回执新增 `image` 字段（attached / skipped:<reason> / scope_off / not_configured），"开了截图却一直走纯文字"可被看见。
- review：4 条必须修全部修复——run_flow 的 absent 假失败、collect_list 用 viewId 去重会丢真实行、2 MiB 被 512 KiB run 预算架空、截图链路静默降级无诊断；另有 settle、`next` 提示、后台暂停播放等 4 条建议修已处理。
- 验证：`:core:jev` 新增 8 个多模态用例（原生 assets + 危险区头、网关切块顺序、两家拒图、网关拒 Box、缺参考标记拒绝、Box 数值校验、边距校验）；新增 `ScreenshotAttemptTest` 4 例。

### Phase 5 手机自控深化 — 完成（W10 + W11）

- `screen_run_flow`：1–12 步固定语义流程，逐步从新观察重新定位，不保存 ref/坐标；每步最多派发一次；`ok / rejected / stale_snapshot / unknown` 一律区分且 unknown 不重放；与 `screen_run_goal` 共用同一条提交类目标红线（`BLOCKED_FLOW_TARGET`）；每步之间有 250ms 稳定时间；handback 带 `next` 提示。
- `screen_collect_list`：容器唯一性判定、逐屏读取、按"相邻两屏完全一致的行"合并重叠（不再用共享资源 id 去重）、`coverage: observed_screens_only`、`stop_reason` 如实反映预算/无进展/回执失败。
- 登记：capability（run_flow → SCREEN_AUTOMATION，collect_list → SCREEN_CONTROL）、分类/风险/审批与 `screen_run_goal` 对齐、policy pin 跟随。

### Phase 6 UI 深化 — 完成

- **W14 视频卡片**：`UIMessagePart.Video` 从 72dp 图标瓦片改为可点卡片 + 全屏 `VideoView` 播放（无新依赖）；点击才创建播放器，`onRelease` 停止，后台 `ON_STOP` 主动暂停；播放失败显示原因并可「用其他应用打开」。Markdown 配图与搜索图片本就有原生块，未重复实现。
- **W13 跨对话活动卫星**：`RunOwnershipRegistry` 增加只读投影 `activeRuns: StateFlow<List<ActiveRun>>`（register / unregister / 成功 cancel 时发布）；新增 `ConversationActivityCenter` 把投影翻成"别处正在跑"的提示（跳过当前对话、同一对话只留最早一条、标题注入 + id 前缀回落）；顶栏在产物架左侧出现一个只在有事时渲染的 `ChatActivityButton`（Radio 图标 + 数字角标），点开 `ChatActivityPanelContent` 列出对话标题，点一条 `navigateToChatPage` 跳过去。
- 设计取舍（写进代码注释）：**没有**把产物架按钮自己变形成卫星。iOS 那样做会在别的对话运行期间把本对话的产物入口挤掉，用户得等它跑完才拿得到自己的东西；Android 用独立 48dp 触控目标，两者互不遮挡。
- 只报告 `RUNNING`：失败/完成需要持久化终态水位（`RunTerminalStore`），是本类的下一步增量，没有在这一轮冒充。
- 测试：`ConversationActivityCenterTest` 7 例（投影随注册/注销变化、被拒绝的重注册不串对话、错配 cancel 不改投影、跳过当前对话、同对话只留最早一条、标题缺失/空白/抛异常的三种回落）。
- W13 review 判定 **BLOCK**，3 条必须修全部修复：
  1. 活动面板漏了 `innerPadding`，面板与 scrim 会盖住顶栏——补上与产物架面板相同的上下 inset。
  2. 三面板互斥缺一条边：岛 → 回顾的分支没关 `activityOpen`，本对话空闲 + 别的对话在跑时会出现两个面板叠加——已补。
  3. 数字角标被父级 `clip(CircleShape)` 削掉约三分之一（几何可算：角标最远点距圆心 19.7dp > 半径 16dp）——把父容器的 clip 换成 `background(color, CircleShape)`，只为底色成圆，不再裁剪子节点。
  另有 3 条建议修已处理：取消异常不再被吞（`CancellationException` 重抛）、测试里两次注册跨毫秒以真正区分"最早/最晚"、卫星不预留位宽的决定写进代码注释。
- 已知取舍：卫星出现/消失时岛屿会横移 24dp。常驻 48dp 空位会让岛屿**永久**左移 24dp，比一次短暂横移更差，因此选择不预留。

### 追补：图片/视频显示与产物架（用户实报问题）

来自 iOS 同问题的三个现象，逐条落到 Android：

1. **「他用链接给我，没有用展示图片的语法」**
   - `DEFAULT_AGENT_SOUL_MARKDOWN` 增加第一条规则：给出图片直链时用 `![简短说明](URL)` 直接展示，不要只贴链接；同一张图只展示一次，代码块与本地路径不要用图片语法。
   - 旧默认整体下沉为 `PREVIOUS_DEFAULT_AGENT_SOUL_MARKDOWN_V2` 并加进迁移比对，未自定义过 agents.md 的老用户会升到新默认；自定义过的不动。
2. **「图片还在屏幕里就变成空白加载状态」**
   - `ZoomableAsyncImage` 的 `ImageRequest` 改为 `remember(model, export, …)`。之前每次重组都新建请求，流式渲染每帧都会触发一次，Coil 把它当新请求重启加载。
   - 水波纹改为 `loading && !loaded`：一旦这张图成功画过就永不再盖水波纹。水波纹是"还没有图"的提示，覆盖已画好的内容看起来就是"图又变空白了"。
3. **「产物架里没有这几张图」**
   - 产物架原先只收 `UIMessagePart.Image`。模型的内联图片是文本里的 `![alt](url)`，所以永远进不了架子。新增 `collectMarkdownImages()`（跳过围栏代码块、丢弃空 URL、剥离可选标题），在 Text 分支把图片收成 `ShelfItemKind.IMAGE`，工具输出里的图片同样收录。
   - 产物架列表对图片项显示 34dp 缩略图（失败回落 kind 图标），标题带条目数。

另加视频显示：
- 视频卡片从 72dp 图标瓦片改为固定 44dp 首帧缩略图 + 播放按钮 + 文件名；缩略图只对本地文件用 `MediaMetadataRetriever` 取帧（http 会拉整段视频，不做），进程内 LruCache 按 URL 复用，失败回落纯图标，尺寸固定所以封面到位前后 timeline 不跳。
- 播放仍走上一轮的 `VideoPlaybackDialog`（全屏 VideoView、关闭停止、后台暂停）。

后续补齐（同一轮）：
- **产物架收视频**：`ShelfItemKind` 原本只有 IMAGE/FILE/MINI_APP/HTML/WEB，用户上传的视频永远进不了架子。新增 `VIDEO`，`visit` 收 `UIMessagePart.Video`（Video 没有 fileName 字段，标签用 URL 末段）。
- **视频封面复用到产物架**：把 `rememberVideoCover`（本地文件首帧 + 进程内 LruCache）从消息组件抽到 `ui/components/media/VideoCover.kt`，卡片与产物架共用同一份缓存，同一段视频不会解码两次。
- 产物架图片/视频行都用缩略图，失败回落 kind 图标。

验证：`MarkdownImageCollectTest` 6 例 + `ChatShelfItemsTest` 新增 2 例（视频收成 VIDEO、正文 markdown 图收成 IMAGE、围栏代码块里的图片语法不计）；`:app:testFullDebugUnitTest` 2846 通过（剩 2 个既有 Antigravity 失败）；`assembleFullDebug` 产出 APK。

Android 侧能力审计结论（本轮开始时）：图片显示、点开大图、搜索图集、生成图画廊本来就存在，缺的是"正文 markdown 图进架子"与"视频进架子"；视频播放与封面是本轮新加的。

预加载（本轮补齐）：
- `TimelineImagePrefetch`（`ui/pages/chat/TimelineImagePrefetch.kt`）在可见范围变化时，把窗口前后各 8 条消息里的图片 URL 预入队到 Coil：`snapshotFlow` 观察 `LazyListState.layoutInfo`，用 `ChatTimelinePlan.messageIndexAt(lazyIndex)` 把 lazy index 映射回消息，只取窗口内的内容——不遍历整个对话，否则长会话会一次性下载所有历史图片。
- URL 来源与产物架一致：`Image` part 与正文 `![alt](url)`；`data:` 内联图不入队（消息体里已经有一份）；视频/音频不预取（封面只对本地文件取帧，没有网络请求）。
- 用 `LruCache` 记住已入队的 URL，滚动抖动不会重复 enqueue。
- "滚出再滚回不闪"由 Coil 内存缓存承担，因此没有、也不需要释放逻辑。
- 顺带：产物架里点图片改为**直接开大图**（`ImagePreviewDialog`），文件/小程序/HTML/网页仍跳回来源消息。

验证：`TimelineImagePrefetchTest` 4 例（Image part + markdown 顺序、跳过 data:、跳过视频音频、忽略代码块内的图片语法）；`:app:testFullDebugUnitTest` 2850 通过（剩 2 个既有 Antigravity 失败）；`assembleFullDebug` 产出 APK（23:57）。

滚动预加载的实际效果（预取是否命中、滚入是否真的不再出现占位）**必须在真机上看**：本机 `adb devices` 为空，只有代码层证据。

### 未执行（如实记录）

| 工作包 | 状态 | 原因 |
| --- | --- | --- |
| W07 用途补齐（意图路由 / 审批分诊 / 脚本判断） | 未做 | Android 缺对应消费方：无子代理角色目录、无 exec 脚本工具；审批分诊需要独立设计（只标注不决策的 UI 面）。只加配置会变成永不生效的开关。 |
| W12 端侧 OCR | 未做 | 计划内可选项，需先测 VLM 调用成本再决定。 |
| 视频竖屏适配 | 未做 | 固定 16:9，竖屏视频会有黑边；属观感问题，播放可用。 |
| 跨对话 failed/completed 提示 | 未做 | 需要持久化终态水位；本轮只投影"运行中"。 |

### 设备验收缺口（未做，必须由有设备的一侧补）

- 本机 `adb devices` 为空，以下全部未做：无障碍旅程（白名单准入、goal、flow、collect_list、stop）、设置页实际渲染（新增「手机自控授权应用」行、Jev 调用模式 Select、推荐版本按钮）、320dp 窄屏与辅助字号、视频卡片实际播放。
- `JevScreenDeviceTest` 只做了编译与「先授权再跑」的前置修复，未在真机执行。

### 既有失败（与本轮无关）

- `AntigravityOAuthSessionTest` 2 项在 `:ai` 模块稳定失败；该模块本轮零改动。
