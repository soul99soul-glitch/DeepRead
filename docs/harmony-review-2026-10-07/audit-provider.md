# AI 协议、平台请求与后台生命周期审查

范围：独立鸿蒙 DeepRead 的实际 AI Provider 调用、HTTP/SSE 接线、搜索平台请求、EntryAbility 生命周期、生成活动、保活与通知。只读审查，不以历史共享实现或无实际 owner 的能力判缺陷。

结论：确认 2 个真实问题，已取得当前实现的可观测失败。源码行号对应本轮审查基线；修复后的行号可能变化。未修改实现，未运行全量或 SDK 构建。

## 确认问题

### H01 / P1：Responses 正常终态被平台未知 HTTP 状态误判为失败

真实调用链：独立 DeepRead `AppContainer.createRunManager` → `createChatDeepReadAiClient` → `buildProviderApi` → `createOpenAIChatApi` Responses 分支 → `createOpenAIResponsesApi.streamText` → `RcpHttpClient.fetchStream`。

触发：模型配置启用 Responses API；收到没有 HTTP 状态行的 SSE 响应头；`response.completed` 到达时 `shouldStop()` 提前关闭原生传输。

根因：`harmony/entry/src/main/ets/platform_impl/RcpHttpClient.ets:197-206` 允许协议终态先于原生状态码完成，以 `status: 0` 返回。OpenAI Chat / Claude 已接受这个终态路径，`harmony/chat/src/main/ets/chat/openai_responses_api.ts:289-294` 却无条件对非 2xx 抛错。

影响：Provider 已正常提供正文，深读规划或写作仍报 `Failed to get response: 0`，不能正常完成。不是 Provider 配置错误，也不是未收到合法终态。

复现：

- `/tmp/deepread-harmony-review-20261007/response-zero-repro.ts`：接口级控制传输响应，输出 `text received 1`、`shouldStop true`、`FAILURE Failed to get response: 0`。
- `/tmp/deepread-harmony-review-20261007/response-actual-http-repro.ts`：沿用 `rcp_stream_delivery.test.cjs` 的 transpile/VM NetworkKit event harness，执行真实 `RcpHttpClient` 和 Responses API；原生 `requestInStream` 保持未完成，仅注入无状态行 SSE MIME 和 `response.completed`。输出 `text received 1`、`FAILURE Failed to get response: 0 { destroys: 1 }`。

最小修复：仅接受已经收到合法终态且 `resp.status === 0` 的平台路径。仍检查已知 HTTP 失败、协议失败与取消，再发布缓冲工具调用。不要直接 `if (state.done) return`，否则会遗漏工具发布和 checkpoint，也会绕过既有失败门禁。

回归：合法终态加未知 HTTP 状态；未知状态且无终态仍失败；保留 `openai_responses_terminal.test.ts` 中终态后 503 / 取消不得发布工具的门禁。

### H02 / P2：旧后台 stop 在新活动注册后完成，释放租约且不恢复

位置：`harmony/entry/src/main/ets/platform_impl/BackgroundGenerationKeepAlive.ets:210-219`、`:233-250`。

触发：后台最后一个活动 A 结束，排入或开始 `stopBackgroundRunning`；B 在 stop 完成前注册。B 求值时 `taskHeld === true`，不排 start。旧 stop 成功后置 `taskHeld = false`，不再核对当前活动集。

真实 owner：DeepRead scheduler 的替换运行在 `harmony/deepread/src/main/ets/agent/scheduler.ts:177-186` 等旧 run 退出后注册新 run，旧 run 在 `:244-246` 释放活动。平台 stop 的异步完成可跨越这个替换过程。

影响：B 仍处于准备、资料采集或规划，系统长时任务却已释放；必须等下一次 raw 输出进度或生命周期切换才可能恢复。等待首包期间留下后台可能被挂起的窗口。

复现：`/tmp/deepread-harmony-review-20261007/keepalive-replacement-repro.cjs` 执行真实保活平台模块，输出：

```text
A started { starts: 1, stops: 0, held: true }
B active after pending stop { starts: 1, stops: 1, held: false }
```

最小修复：stop 成功更新持有事实后，按最新 `desiredBackground` 和活动集重新求值，不新增重试计时器或状态机。仅在平台调用前跳过失效 stop 不足以修复，因为 B 也可在原生 stop 已开始后注册。

回归：扩展现有 `harmony/chat/src/test/generation_keepalive.test.cjs` fixture，为 stop 添加 deferred gate；覆盖 stop 已进入平台后 B 注册、stop resolve 后新租约恢复，并确认前台或销毁期间不会重启租约。

## 二次复核与排除

### 固定 notification requestCode 991 导致文章通知串目标：假阳性

不能套用 Android PendingIntent 排除 extras 的身份判断。当前 OpenHarmony `PendingWantManager::CheckPendingWantRecordByKey` 比较 requestCode 后调用 `Want::IsEquals`，后者比较 parameters；不同 topicId 可以区分。

已核对官方源码：

- [PendingWantManager 匹配实现](https://github.com/openharmony/ability_ability_runtime/blob/master/services/abilitymgr/src/pending_want_manager.cpp)，当前下载源码 304-339 行。
- [PendingWantKey 委托 Want 比较](https://github.com/openharmony/ability_ability_runtime/blob/master/services/abilitymgr/src/pending_want_key.cpp)，140-143 行。
- [Want::IsEquals 包含 parameters](https://github.com/openharmony/ability_ability_base/blob/master/interfaces/kits/native/want/src/want.cpp)，1405-1416 行。

本地证据：`/tmp/deepread-harmony-review-20261007/pending_want_manager.cpp`、`pending_want_key.cpp`、`want.cpp`；本地官方 API 文档检索内容保存在 `wantagent-doc.txt`。源码来自当前 master；它用于排除本轮未经证明的 Android 类比，不构成所有 Harmony 版本的设备行为验收。

### 其他排除项

- DeepRead 仅支持 OpenAI Chat Completions：不可达历史实现推断。`ChatDeepReadDraft` 的旧注释和 legacy API 客户端不能代表独立产品。实际运行捕获 Chat Provider，支持 Claude、Google、Responses 和 OAuth 分流。
- 独立 DeepRead 不使用工具 writer：by design。`AppContainer` 选择 `writerMode: 'structured'`，不能把无 tool 能力下旧 tools writer 的问题算入独立产品。
- Google 搜索依赖当前文章 Web host：明确的 owner 边界。每个文章绑定独立 controller；离页后不借用其他页面的浏览器。目前没有证据支持增加跨页共享 host 或静默回退。
- 后台不保证无限保活、长静默不伪造传输进度：by design。现有 dataTransfer 合同明确约束，不应追加心跳或定时续约。
- Responses 已知 HTTP 失败或取消发生在终态之后仍不发布缓冲工具：现有正确门禁。H01 修复需要保留。

## 已覆盖但未发现新确认缺陷的范围

检查了实际 SearchRegistry owner、Search SDK transport、EntryAbility 产品隔离与前后台调用、通知 owner token 与异步取消串行化、生成 tracker baseline 和迟到快照处理、Provider 参数/auth 捕获。没有发现可证明的新缺陷，未因猜测增加修复项。

## 验证边界

以上复现执行当前生产源码并控制平台 API 的事件/Promise。它们证明协议与生命周期逻辑问题，不代表真实 Provider、系统通知显示或设备后台调度验收。本审查未运行全量测试、SDK 构建或设备测试；这些交由主 agent 的阶段验证统一执行。
