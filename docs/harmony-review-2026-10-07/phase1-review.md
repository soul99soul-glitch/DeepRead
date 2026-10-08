# Phase 1 独立复核

结论：通过。对准确阶段 diff、H01/H02 的原始复现、实际 Responses 与 Rcp adapter、后台保活操作队列及新增回归进行复核，没有发现本阶段引入或遗漏的、需要修正的真实问题。没有修改实现或测试文件。

## H01：终态与 HTTP 状态

- `state.done && resp.status === 0` 只放行协议已经结束而原生状态尚未回传的路径；不把普通未知 HTTP 状态当作成功。实际 Rcp adapter 联合回归覆盖普通和 resumable 请求。
- Provider 解析失败、transport reject 仍在 HTTP 门禁前抛出；已知 HTTP 503 即使随后已有 completed envelope 也仍拒绝。取消检查仍在缓冲 tools 发布与 terminal checkpoint 之前。正文可作为失败时的局部结果显示，工具不会因此执行。
- resumable 模式先等待已有 checkpoint 队列，再检查失败/取消，再发布完整 tools，最后保存 terminal cursor。新增联合回归在 terminal checkpoint 回调中检查已发布的完整 tool，既有序列、断流、checkpoint failure 与 completed status 回归仍通过。
- 原有 `[DONE]` 兼容终态和合法 `max_output_tokens` incomplete 语义由既有解析规则决定；此次没有修改这些协议规则。未知状态放行仍依赖既有成功终态，不能把 by design 的兼容行为判为新增缺陷。

## H02：停止期间的新活动与失败路径

- native stop 成功后先清除持有事实，再调用现有 `evaluateTask()`；此时 B 已注册且仍处于后台，才会入队 start。start 仍通过同一个 `operationTail` 串行执行，不与正在完成的 stop 交错。
- 前台/销毁会同步设置 `desiredBackground = false`；销毁还清除活动。重新求值不申请租约，新增回归覆盖两条路径。排队 start 自身仍在调用 native 前复核实际需求。
- `evaluateTask()` 在 stop 成功路径调用一次；stop reject 不进入它，start reject 也不调用它。独立受控实验确认：B 的重申请失败后 starts 保持 2、stops 保持 1、held 为 false；stop 本身失败时 starts/stops 保持 1/1、held 为 true，均未产生自动重试循环。
- 既有通知成功发布可能清空共享 `lastError`，该字段反映最近操作结果；本阶段未改动它，也未把“最后错误永久保留”当作本阶段新增合同。

## 精确性与可维护性

两个生产改动均复用现有状态和串行操作结构，没有新增 fallback、重试计时器、请求、权限或状态机。测试模拟平台事件和 pending Promise，直接执行生产 adapter/保活源码，验证了实际失败条件和回归门禁。

## 实际验证及边界

独立执行（目录 `harmony/chat`）：

```text
node_modules/.bin/tsx --test src/test/openai_responses_terminal.test.ts src/test/openai_responses_resume.test.ts src/test/generation_keepalive.test.cjs ../entry/src/test/rcp_stream_delivery.test.cjs
```

结果：49/49 通过，0 failure、0 cancelled、0 skipped。另通过两条临时 VM 受控失败实验检查 start/stop 无自动重试；没有添加近似实现的重复测试。

本复核没有执行全量测试、SDK 构建、签名、安装、启动、真实 Provider 或真机后台调度/通知验收。Node 验证证明业务与 adapter 的协议、并发语义；这些交付层面的剩余检查由主 agent 统一执行并分别报告。
