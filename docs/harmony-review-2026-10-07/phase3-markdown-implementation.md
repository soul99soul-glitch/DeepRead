# Phase 3：H10 Markdown 链接目标完整性

日期：2026-10-07。对应 `audit-reader.md` R3 / findings H10。本子任务只修改共享 Markdown parser、该 parser 的现有测试和一个独立 DeepRead 阅读/导出 integration 测试；未修改另一 worker 独占的 `deepread_template.test.cjs`，未改 SDK API、依赖或 Markdown 框架。

## 失败证据

先新增回归，仍运行修复前生产 parser 与 `DeepReadMarkdownHtml.ets`：46 项中 41 通过、5 失败。保留日志：

- `/tmp/deepread-harmony-review-20261007/phase3-markdown-red.log`
- `[报告](https://example.com/report_(2026)?q=(outer(inner))&source=reader)` 的真实 Web 输出只把 `https://example.com/report_(2026` 放入 href，后续 query 和右括号漏到可见文本。
- `[来源](<https://example.com/source_(2026)>)` 的真实 Web 输出没有来源 anchor；角括号仍在 token URL 中，又在第一个圆括号结束时被截断。

失败来自实际解析/渲染函数，不是重复实现的期望替身。括号报道地址在现有生产正文链可达；来源专用 renderer 的序列化处理不能修复 summary/answer 等共享 Markdown 正文，二次复核结论仍是链接完整性缺陷。

## 修改与范围

`harmony/chat/src/main/ets/chat/markdown_blocks.ts`：链接与图片共用有限 destination 扫描。普通目标用深度计数消费配对圆括号；角括号包装拆成真实 URL；已有 Markdown 标点转义还原后继续走原有 scheme 白名单。未增加 URL 探测、重试、scheme 扩展、通用 AST 层或完整 Markdown 特性。

扫描游标只前进：标签每段扫描一次，destination 从开括号扫描到闭合/失败的恢复点后直接继续，未对每个 `[` 重扫整个剩余字符串；深层配对括号采用计数，无递归。这是本次新增 destination 扫描的线性性质，未宣称原有格式 overlaps 检查及整个 Markdown parser 均为严格线性。

保留代码区间优先级、escaped label 在公式前的既有处理、外层公式覆盖链接等 overlap 行为。目标中的 `\(2026\)` 属于 URL 标点转义，公式扫描跳过已识别目标区间，防止还原后的 URL 被拆成数学 token。

实施中复核到空标签候选如果先消费 destination 会挡住后面的既有有效 link，因此在开始 destination 扫描前沿用“链接 label 不得为空”的原有规则。新增精确回归覆盖 `[]( [来源](https://example.com/source))`；未扩展嵌套标签/嵌套格式能力。

`harmony/chat/src/test/markdown_blocks.test.ts` 新增：balanced path/query、angle URL、转义圆括号、图片共用目标规则、escaped label、代码优先级、unsafe scheme、未闭合目标、长未闭合标签、深层配对括号、空标签后有效链接及外层公式优先级。

`harmony/deepread/src/test/deepread_markdown_links.test.cjs` 新增实际入口验证：

- 用现有 `loadPureModule` 加载生产 parser 与 `DeepReadMarkdownHtml.ets`，断言完整 href/src 和 HTML escaping。
- 提取生产 `NativeMarkdownText.ets.inlineRuns` 实际方法，核对原生渲染 run 的 URL 未截断。
- 加载生产 `DeepReadExportFiles.ets`，只 stub 未调用的 SDK imports，直接核对实际 `deepReadMarkdownToText` 保留完整 URL；未调用文件/分享 API。
- 真实 Web 入口继续保留 code spans，并不产生 unsafe link/image 节点。

## 局部验证

命令（工作目录 `harmony/deepread`）：

```sh
./node_modules/.bin/tsx --tsconfig ../chat/tsconfig.json --test \
  ../chat/src/test/markdown_blocks.test.ts \
  ../chat/src/test/markdown_math.test.ts \
  ../chat/src/test/native_markdown_inline.test.ts \
  ../chat/src/test/markdown_cache.test.ts \
  src/test/deepread_markdown_links.test.cjs \
  src/test/deepread_export_files.test.cjs
```

结果：103 项通过，0 失败。日志 `/tmp/deepread-harmony-review-20261007/phase3-markdown-green.log`。

## 独立 review 后的回归修正

独立 reviewer 比较 baseline 和第一版修复，确认失败 destination 消费至段落末尾会吞后续合法来源。这是本次扫描实现引入的真实回归：

```md
[坏](<https://bad.example) [来源](https://example.com/source)
[坏](https://bad.example/path_(oops) [来源](https://example.com/source)
`[坏](<https://bad.example)` [来源](https://example.com/source)
```

先补回归，107 项中 103 通过、4 失败；证据 `/tmp/deepread-harmony-review-20261007/phase3-markdown-review-red.log`。最后一项还经生产 Web/TXT formatter 验证后续来源丢失，没有将它解释成 malformed Markdown 的预期降级。

精准修正：链接扫描以单向 codeIndex 跳过已经识别的 code spans，目标扫描以最近代码开始位置作为上界；目标中的未编码内部空白作为失败恢复点，恢复在下一未消费字符之前。wrapper 边界的前后空白 trim 保留。角括号结束后缺少外层 `)` 时同样保留下一未消费字符，避免漏掉紧跟的有效 `[`。没有在失败后从每个候选重扫 suffix，也没有额外 fallback parser。

同一局部命令复跑 **107/107 通过**，证据 `/tmp/deepread-harmony-review-20261007/phase3-markdown-review-green.log`。覆盖 reviewer 的三个实际样本、angle wrapper 后紧跟有效 label、wrapper trim，同时既有合法括号路径、angle URL、code 和外层 math 优先级继续通过。生产修改已重新向主 agent/reviewer 声明稳定。

本子任务未运行全量测试、类型检查、ArkTS lint、SDK 构建或设备验收；这些由主 agent 在 Phase 3 合并后统一执行。上述 Node fixture 验证生产 AST、原生 run 映射、Web HTML 和 TXT 格式化，不证明实际浏览器跳转、真机文件分享或设备 UI 行为。

生产修改已向主 agent 声明稳定；本轮独立 review 由主 agent 调度，结果另记录于 Phase 3 review 文档。
