# 生成链路审查

范围：独立鸿蒙 DeepRead 实际可达的生成调度、资料转研究证据、结构化章节、合成模板、取消与续跑、阶段失败与保存收口。只读实现；本文件为审查结果。基线与授权见 `plan.md`。

## 真实入口与边界

- `DeepReadRootPage.ets` 装配发现、阅读库、设置；文章操作最终进入 `DeepReadArticlePage.ets`。其 `retryFirstFailure()` 在 741–771 行只将首个失败章节交给 scheduler.runSection。
- `AppContainer.ets:266–333` 每轮捕获 provider、模板、playbook，创建 prefetcher、研究 AiClient 与 collectRun；326 行明确独立 DeepRead 总是 `writerMode: 'structured'`。工具模式、小说、council 能力不作为本产品缺陷来源。
- `DeepReadRunConfig.ets` 是旧 OpenAI-compatible 路径配置帮助函数；当前生成使用 `resolveDeepReadRuntimeCore()` 的真实 provider 配置，不能仅凭该文件推断产品只支持一个协议。
- scheduler 准入、topic 锁、token ownership、活动观察、通知终态与取消都已追踪；run_manager 的续跑、强制替换、首次采集失败、落盘失败、合成模板路线与经典结构化路线均已审查。

## 二次复核结果

本分工未确认需修复的问题。以下候选已复核，不应进入本轮问题/修复列表。

| 候选 | 二次复核 | 证据与理由 |
| --- | --- | --- |
| 段落超时调用 child.abort 后，AbortError 比 TimeoutError 更早赢得 race，从而把预算超时报成用户取消 | 当前生产链路假阳性；可直接构造的接口行为，不是当前产品问题 | `supervisor_loop.ts:425–430` 确实先 abort 再 reject；直接返回监听 abort 的 Promise 可复现。但生产 `AppContainer.ets:302–319` 返回 `aiClient.generateText(...).then(...)`，且实际 `ChatDeepReadAiClient` 与 protocol adapter 有 await 链。相同 HTTP abort 行为经真实 adapter 与生产 collectRun 形态后，两次请求均正常归为 TimeoutError，最终 OVERVIEW=FAILED、outcome=timeout。只增加一层 `.then(out=>out)` 也已消除先前复现。不得把直接 stub 结果宣称为生产路径错误。 |
| 模板 sectionsReady 只检查 hasSynthesisBody，FAILED 状态不能使模板文章失去 complete | by design；理论矛盾未见生产可达写入 | `helpers.ts:sectionsReady` 模板判定不使用经典四段状态。`run_manager.ts:328–332` 成功模板本来就是 `sectionStates={}`；336–338 初稿失败会清除 templateArticle 并标记 OVERVIEW=FAILED；334–335 有可读旧稿时保留旧稿。不存在当前生成 owner 给保留模板正文标 FAILED 的路径，`generationComplete` 对真实模板初稿失败是 false。 |
| 合成模板不经 supervisor 段落超时 | 设计差异，未确认 bug | 模板只调用一次合成请求，自动模式额外一次选择；并不承诺经典章节的 90/120 秒阶段预算。Rcp 网络层设有 120 秒 readTimeout。晚到模板响应在 `generateSynthesis` 里检查父 signal，取消后不落盘；现有合成测试覆盖选择和正文取消。不要为统一阶段形式增加未需要的超时/重试状态机。 |
| 部分章节失败却 run 返回 ok=true | by design | classic 分支保留可用 READY 内容，ok 表示已有可用结果；generationComplete 仍为 false，失败状态留在 sectionStates。通知明确区分“深度阅读部分完成”，文章页面 partialError 展示失败并允许只重试该段。 |
| 强制替换过程中旧文章仍 COMPLETE、首次生成阶段保存状态不同 | by design | `generateStages` 的 previousOutput/saveGuarded 仅在完整新稿成功落盘时替换可读旧稿；活动状态以 scheduler 的真实 runningJobs 为准，不将旧稿 COMPLETE 误当成无活动。取消或新稿失败保留旧稿与已有捕获模板。 |
| 四个 stage 理应都请求模型，structured 的 EXTENDED_READING 没有模型请求 | by design | 431–434 行本地组装已验证来源，避免给只做来源抄录的阶段增加模型费用；runSection 的同段修复复用 saved research。真实正文/文件也能作为 numbered sources，不需虚构外部链接。 |
| collectRun 可以调用 scrape_web、工具预算或 approval loop 有共享领域缺陷 | 独立产品不可达，排除 | 真实 writerMode 永远 structured，章节传 `tools=[]`；预算说明与提示中也不广告不可用 scrape_web。未将工具模式与小说/council 的接口行为扩大为 DeepRead 缺陷。 |

## 实际验证

已运行聚焦测试：`supervisor_loop.test.ts`、`run_manager.test.ts`、`deepread_structured_pipeline.test.ts`、`synthesis_templates.test.ts`，47 个测试全部通过。覆盖结构化章节补正、冷启动仅补失败章节、旧文章替换保护、来源本地组装、模板捕获、自动选择固定、取消后晚到输出不存、真实持久化拒绝上抛。

记录位于 `/tmp/deepread-harmony-review-20261007/audit-pipeline-tests.log`。

超时二次复核脚本：

- `audit-timeout.ts`：直接 abort-aware collectRun stub，可得到 AbortError。
- `audit-timeout-chain.ts`：增加生产存在的 `.then()` 后，正常得到 FAILED/timeout。
- `audit-timeout-real-adapter.ts`：真实 createChatDeepReadAiClient + createOpenAIChatApi + HTTP signal rejection + 生产 collectRun `.then()` 形态，缩短长定时器后结果为 `{"outcome":"timeout","requestCount":2,"state":{"OVERVIEW":{"status":"FAILED","errorMessage":"概览超时未完成（本段预算约 90 秒）。"}}}`。

上述为 Node 领域/adapter 验证，未代表真实 provider、设备后台调度或 UI 验收；本分工未修改实现，未运行完整测试或 SDK 构建。
