# Phase 1：Responses 与后台保活

已完成 H01、H02 的精确修复；待独立 reviewer 检查。本阶段没有运行完整测试或 SDK 构建，由集成阶段统一验证。

## H01：合法终态在未知 HTTP 状态下被误判失败

真实 `RcpHttpClient.ets` 收到 `response.completed` 后按 `shouldStop()` 立即关闭 native HTTP，此时尚未收到真实状态码，返回 `status: 0`。原 Responses 组装层仍执行普通 HTTP 非 2xx 判断，已收到的正文最终变成失败。

修复只允许 `state.done && resp.status === 0` 通过状态检查。Provider 解析错误和 transport 异常仍先抛出；已知非 2xx 状态仍拒绝；取消仍在 tools 发布和 terminal checkpoint 前拒绝。没有增加请求、重试或状态。

新增回归把真实 Responses 组装层与真实 Rcp adapter 联合执行，仅替换 NetworkKit 事件来源。native `requestInStream()` 保持 pending，终态使请求关闭；普通与 resumable 两条路径均能完成，正文及一个完整 tool 发布成功，resumable terminal cursor 在 tool 发布后保存。另覆盖 status 0 没有终态、provider failed、transport failed、取消不会发布缓存 tool。既有 HTTP 503、content_filter、resume checkpoint 错误及 EOF 回归也继续通过。

## H02：native stop pending 期间注册的新活动丢保活

新回归先让 A 获得租期，再结束 A 并等待 native `stopBackgroundRunning()` 真正进入 pending，期间注册 B；停止完成后 B 的活动仍在，但原实现没有重新申请租期。red 断言实际 starts=1、预期 starts=2。

修复在 stop 成功、清除持有事实后调用已有 `evaluateTask()`，按最新活动和 `desiredBackground` 入队必要 start，沿用现有串行操作尾。没有增加重试或新状态机。新增前台及销毁回归：B 在 stop pending 期间注册后，如进入前台或销毁，stop 成功后不会重启。

## 验证证据

- 开始全局快照：`/tmp/deepread-harmony-review-20261007/baseline/`；本阶段四个修改文件的独立快照：`/tmp/deepread-harmony-review-20261007/phase1-start/`。
- Red：`/tmp/deepread-harmony-review-20261007/phase1-red.log`。27 个测试中 24 通过、3 失败；H01 实际 adapter 失败为 `Failed to get response: 0`，H02 为 starts=1 的真实错误，取消回归的预期取消原因亦被 status 0 错误抢先覆盖。
- Green：`/tmp/deepread-harmony-review-20261007/phase1-green.log`。Responses terminal、resume、generation keepalive、Rcp stream delivery 四组 49/49 通过。
- 可独立审阅的本阶段 diff：`/tmp/deepread-harmony-review-20261007/phase1.diff`。
- 执行目录 `harmony/chat`，命令：`node_modules/.bin/tsx --test src/test/openai_responses_terminal.test.ts src/test/openai_responses_resume.test.ts src/test/generation_keepalive.test.cjs ../entry/src/test/rcp_stream_delivery.test.cjs`。

修改文件：

- `harmony/chat/src/main/ets/chat/openai_responses_api.ts`
- `harmony/entry/src/main/ets/platform_impl/BackgroundGenerationKeepAlive.ets`
- `harmony/chat/src/test/openai_responses_terminal.test.ts`
- `harmony/chat/src/test/generation_keepalive.test.cjs`

未验证：本阶段没有声称真实 Provider、真机保活、系统通知或调度验收；这些 Node 回归执行实际业务与 adapter 代码，但 native 系统调用由可控替身提供。
