# DeepRead iOS 审查与分阶段修复

基线：`main@fcfbc3bf086558c9793716c5144ed1d06189452b`。审查日期：2026-10-07。
范围：当前独立 iOS application target 的生产代码与调用链。保留用户已有 `Resources/Info.plist`、`project.yml` 版本号改动；不提交、推送或发布。

审查覆盖来源采集与文件导入、Discovery 刷新及设置、分阶段生成与补全、Chat / Responses / Claude 请求和流式解析、任务状态与持久化、精读与合成模板、文章阅读和分享导出。候选按真实调用路径与既有测试契约筛选；“缺少测试”或静态警告本身不算缺陷。

收尾验证期间，外部并行工作将产品树移至 `ios/` 并加入 `harmony/`。本次代码和证据跟随新目录保留，最终检查使用新 iOS 路径；Harmony 不属于本轮审查范围。下文路径均相对 `ios/`。

完成标准：候选问题结合 caller 和已有契约二次复核；真实问题有失败测试或可观测复现；各 phase 修复、相关测试通过并经独立 subagent review；最终全套模拟器测试和设备目标构建通过。真机后台系统接管、付费 Provider 和人工 UX 验收与上述检查分别报告。

## 已确认问题与计划

| ID | 优先级 | 问题与真实触发 | Phase |
| --- | --- | --- | --- |
| S01 | P1 | RSS 正则捕获索引错位，arXiv / InfoQ 条目 title 恒空。真实 arXiv HTTP 200、857 items 复现。 | 1 |
| S02 | P2 | Wi-Fi 门禁先返回，使停用来源、关注与翻译开关的缓存投影不生效。 | 1 |
| S03 | P2 | 配置变化取消旧刷新，新刷新撞 `isRefreshing` 后丢弃；真实挂起 translator + 取消/重入复现。 | 1 |
| S04 | P2 | 界面提供 15 分钟刷新，store 强制至少 30 分钟；20 分钟缓存未 fetch 复现。 | 1 |
| S05 | P2 | 免费搜索把 URL 整体转小写，大小写敏感 path/query 的不同来源被合并。 | 1 |
| S06 | P2 | HTML/XML entity 级联解码，将原文 `&amp;lt;` 解成 `<` 而非 `&lt;`。 | 1 |
| S07 | P2 | 原文 segment 删除 inline / linked Markdown 图片及其中数据。 | 1 |
| S08 | P2 | direct 原文采集丢弃合法的相对及协议相对图片 URL。 | 1 |
| S09 | P1 | DOCX 条目全量解压没有绝对限制，约 21 KB 合法输入展开为 21 MiB XML；现有 20 MiB 输入限制可被绕过。 | 1 |
| S10 | P2 | `scrape_web` 的 Jina fallback 使用 strict public transport，无法走已有 Fake-IP verified HTTPS 能力；本机 `r.jina.ai` 解析为 `198.18.3.181`。 | 1 |
| G01 | P1 | 补全章节时新恢复来源插在旧来源前，保留章节的数字引用指向另一来源。 | 2 |
| G02 | P2 | `[{}]` 及纯空白分析被当作有效章节，空版面可标为完成。 | 2 |
| G03 | P2 | 精读只缺比较章节时重试仍重做导读，新导读失败会用无批注原文覆盖已完成导读。 | 2 |
| G04 | P2 | 模板草稿与标题翻译向 o-series Chat API 下发不支持的 `max_tokens`。 | 2 |
| R01 | P2 | 自定义模板 `{{summary}}` 取到生成日期而非摘要；概览失败、其他章节成功的部分稿件同样可触发。 | 3 |
| R02 | P2 | 自定义模板按行转换遗漏 Markdown 语义，来源链接不可点击。 | 3 |
| R03 | P2 | HTML 有序列表丢失非 1 的 startIndex。 | 3 |
| R04 | P2 | PDF 未注册 `amberfont` handler，内置 serif / mono 字体退回系统字体。 | 3 |
| R05 | P2 | 原文模式下 PDF 分享原文，TXT/Markdown 仍分享全部 AI 导读与批注，三种分享选择不一致。 | 3 |
| R06 | P2 | PDF 残留“左右滑动查看”提示，但 PDF 无横向滑动交互。 | 3 |
| R07 | P2 | 星系阵营切换保留上一阵营的 scrollTop，跳过新阵营标题与开头；真实 WebKit 复现修复前切换后仍为 240；修复后两条切换路径都归零。 | 3 |
| R08 | P2 | 模板允许页内目录，但阅读器将 `#analysis` 当外部链接取消并交给 Safari，无法跳到对应段落。真实 WebKit 已复核导航类型与 URL。 | 3 |
| R09 | P2 | 模板变量循环替换再次解释已插入标题/正文的字面 `{{title}}`、`{{font_css}}` 等内容，改变文章。 | 3 |

23 项已确认（3 项 P1、20 项 P2）。修复按以下顺序推进，每阶段结束后由未参与该阶段实现的 subagent 独立复核。

1. **Phase 1，来源与 Discovery**：S01–S10，先保证来源可获取、正文与图片不丢失、缓存展示及刷新生效，并限制 DOCX 实际解压输出。
2. **Phase 2，生成与补全**：G01–G04，保留引用编号与已完成精读，修正空内容判定和真实调用使用的模型参数。
3. **Phase 3，阅读与导出**：R01–R09，修复摘要、Markdown、模板变量与页内链接，统一当前阅读模式的分享内容，恢复 PDF 字体并处理星系滚动状态。

G04 的 API 契约已核对 [OpenAI 官方 SDK 的 Chat 参数定义](https://github.com/openai/openai-python/blob/main/src/openai/types/chat/completion_create_params.py#L106-L121)：o-series 应使用 `max_completion_tokens`。本次验证实际请求 body 的字段选择，没有发送真实付费请求。

## 修复入口

| Phase | 主要生产入口 | 新增回归文件 |
| --- | --- | --- |
| 1 | `IOSHotListProviders`、`IOSHotListDashboardStore`、`DeepReadDiscoveryView`；`IOSFreeSearchAggregator`、`IOSSearchExecutor`；`DeepReadCloseReading`、`DeepReadFileImporter`、`DeepReadDocumentZipReader` | `DeepReadDiscoveryReviewTests.swift`、`DeepReadIngestionReviewTests.swift` |
| 2 | `IOSDeepReadDraftGenerator`、`DeepReadStructures`、`DeepReadRuntime`、`DeepReadOpenAIProvider` | `DeepReadGenerationReviewTests.swift`、`DeepReadRuntimeReviewTests.swift`、`DeepReadProviderReviewTests.swift` |
| 3 | `IOSDeepReadTemplates`、`IOSDeepReadEditorialRenderer`、`IOSDocumentExportSupport`；`DeepReadDetailView`、`DeepReadTextExporter`、`DeepReadArticleWebView`；`Resources/Galaxy/main.js` | `DeepReadRenderingReviewTests.swift` |

## 排除项

- 不可执行的搜索种类在设置页已明确披露仅保存配置。
- Discovery 普通点击跨模式复用、topic 按共享来源复用有明确代码说明与测试。
- 自动模板补全 magazine 保持原模板，已有回归保护。
- `others: []` 表示全部比较资料跑题，是既有契约。
- PDF 故意隐藏远程图片；Galaxy parser 保证有效 camp 数量。
- DOCX raw deflate、表格 cell 换行已有真实 fixture，未复现缺陷。
- Provider SSE BOM、多 Text 请求、尾斜杠路径等缺乏产品实际失败证据；不追加兼容机制。
- Keychain revision 保存已有 rollback / 重启测试，未确认凭据丢失。
- 不删死代码，不迁移架构，不加网络重试或通用兜底。
- 宽表裁切候选已排除：macOS WKWebView.createPDF 有裁切，但产品使用 iOS UIPrintPageRenderer；导出实际 iOS PDF、提取文字并渲染 PNG 后，六列内容清楚完整。仅修复残留的滑动提示，不重排表格。

## 执行记录

- 基线：Xcode 27，iOS 26.5 iPhone 17 Pro 模拟器，164 tests / 0 failures。
- 结果：`build/ReviewBaseline-20261007.xcresult`；日志：`build/review-baseline-20261007.log`。
- Phase 1：完成 S01–S10。首轮 13 个回归测试全部复现预期失败，`build/review-phase1-red.log`。相关 74 tests 中 73 通过，1 项新测试假阳性为缓存时间戳；纠正断言后 discovery 5/5 通过，其余 69 个结果复用。独立 `phase1_review` 发现并关闭图片前后单字被过滤的回归，最终 0 未关闭问题。
- Phase 2：完成 G01–G04；已取得 generation/provider/runtime 的实际失败测试，`build/review-phase2-3-red.log`。相关 82 tests / 0 failures，`build/review-phase2-green.log`；独立 `phase2_review` 复审 0 需修问题。
- Phase 3：完成 R01–R09。摘要、Markdown、列表和 PDF 首批 6 项均取得 red；原文分享、页内 anchor 与变量替换分别取得 red。独立复核进一步确认 R01 在概览失败的部分稿件中漏修，新增精确测试复现两条摘要断言失败，成功章节断言仍通过；修复后保留旧摘要和普通 Markdown 首段的控制断言。`phase3_review` 复读当前代码及实际 WebKit 探针，0 未关闭问题。新目录完整模拟器测试 199 tests / 0 failures，其中 RenderingReviewTests 11 项全部通过；无签名 generic iOS Release 构建通过。
- PDF 验证使用产品实际 iOS `UIPrintPageRenderer` 路径，实际字体字典包含 NotoSerifSC / JetBrainsMono；导出的六列表格经文字提取及 PNG 视觉复核确认完整，滑动提示已消失。保留 `build/review-pdf-after-0.png` 和 `build/review-pdf-after-attachments/`。
- 收尾期间目录迁移发生在一次 selected test 完成之后，旧 Xcode 进程结束结果收集时挂起。已有完整失败断言日志保留；结束本轮该进程，并在新 `ios/` 路径使用新的 DerivedData 重跑全套及 Release，未回退目录迁移。
- 用户版本号 WIP 与初始修改逐字节一致。本轮生产代码和新增测试的独立快照为 `build/review-baselines/repair-final.patch`，不包含原有版本号修改及外部目录重组；反向 `git apply --check` 通过。


## 最终验证与交付边界

- 新目录完整 Simulator test：199 passed / 0 failed / 0 skipped，`build/ReviewFinalLayout-20261007.xcresult` 与 `build/review-final-layout-tests.log`；结果通过 `xcresulttool get test-results summary` 复核。包含原有 164 项及本轮 35 项回归与控制测试。
- generic iOS Release：新的 `build/Device-review-final` DerivedData，`CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO`，`BUILD SUCCEEDED`；日志 `build/review-final-layout-device-build.log`。这证明设备目标编译与链接，不构成真机签名、安装或运行验收。
- 三个独立阶段 reviewer 均报告当前范围 0 未关闭真实问题；复审发现的单字正文回归和部分稿件摘要漏修都已关闭。JavaScript 语法、diff 空白及修复快照的反向应用检查通过。
- 本轮没有发送真实付费 Provider 请求，也没有进行真机签名、安装、启动、长时后台系统接管或人工交互验收。WebKit 探针和实际 iOS PDF 检查仅证明各自已观察的行为，不外推到这些未执行环节。
- 本轮修改保留在工作区，没有执行提交、推送或发布；原有版本号修改及外部目录重组完整保留。已确认的 23 项全部修复，当前没有留待下一阶段的已确认问题。
