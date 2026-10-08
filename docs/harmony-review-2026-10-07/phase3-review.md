# Phase 3 独立复审

日期：2026-10-07。结论：**通过；复审发现的一项 H10 新回归已在本阶段修正并复核闭环。**

范围仅为 H08/H09/H10 的三个生产 owner 及对应回归。逐项比较 `/tmp/deepread-harmony-review-20261007/baseline` 中本轮开始时的源码，读取项目 ArkTS skill、audit-reader.md、findings.md 和两个 Phase 3 实施记录。没有修改生产代码或其他 worker 的测试，没有运行全量测试、SDK 构建、lint、设备或 Provider 验收；这些由主 agent 统一完成。

## H08：正文配图门控

确认原问题可达：生产 `writeStructuredStage` 会把结构化响应中额外的 timeline/core_points image_url 传给 NARRATIVE writer，prompt schema 未展示字段不能阻止实际模型响应进入 writer。原实现未经候选验证便保存 URL，而既有 visuals writer 已明确要求候选池内、非 reject。

当前修改在 NARRATIVE owner 复用 `candidateForUrl`，池外和 reject URL 置空，同时清理该图片的图注。日期、事件、核心判断、支撑正文及来源继续按原规则写入；合法 inline 图和图注保留。新测试通过真实 writeStructuredStage → writer → output → editorial/custom HTML/Markdown，核对拒绝图不出现、正文不丢和合法图仍在。没有增加消费端兜底、旧稿迁移、网络探测或新的图元数据体系。

## H09：CSS 示例占位符

原模板校验接受 CSS 注释中的 {{summary}}/{{content}}，但渲染全串替换会把正文塞进 style，又把章节记录为已消费，导致可见正文缺失。属于校验接受的真实输入，而非无效模板假阳性。

当前渲染按捕获 style 区块拆分，保留区块内容，只对其他片段执行原有的一次占位符替换。缺章补全取决于实际替换的正文槽，CSS 中 content 不再关闭补全。核对大小写 style、CSS 与真实 summary/content 槽同时出现、无正文槽、生成标题含槽样文字：CSS 保留、正文齐全、章节各出现一次、生成文章文本未发生第二轮替换。原 HTML 注释、安全清洗及不完整 HTML shell 的行为继续通过既有回归。

## H10：链接目标及复审修正

原问题确认：共享 Markdown parser 的 `[^)]+` 截断平衡括号 URL，角括号包装未剥离，实际 DeepRead Web renderer 丢弃该来源。修复后的有限扫描支持路径/查询中的嵌套括号、转义标点及角括号目标，链接与图片共享目标规则，继续使用既有 scheme 白名单。生产原生 inlineRuns、Web href/src 及 TXT formatter 均有真实入口回归；代码、公式、escaped label 的优先级继续通过。

独立复审初版发现一项确定回归：失败的 destination 扫描跳到文本末尾，吞掉后续合法来源。例如：

```md
[坏](<https://bad.example) [来源](https://example.com/source)
[坏](https://bad.example/path_(oops) [来源](https://example.com/source)
`[坏](<https://bad.example)` [来源](https://example.com/source)
```

逐条比较真实 baseline/current parseInline：基线都保留后一个来源 link，初版修复分别变为整段 text，或 code 加来源语法 text。独立断言探针首先失败，证据在 `/tmp/deepread-harmony-review-20261007/phase3-review-probe-red.log`；不是凭静态代码推测的风险。

实施者随后只修扫描边界：按位置跳过已有 code 区间，并让目标扫描上界停在下一个 code 起点；目标内部遇未转义空白时结束失败候选，保留后续字符；角括号结束后若缺少 wrapper 右括号，也不吞掉下一个未消费字符。URL 内空白需编码，wrapper 开头及角括号结束后的原有空白处理保留。合法 `<https://example.com/source_(2026)>` 没有因 URL 内右括号被误拒。

再次查看实现，label/destination/code 游标均向前，没有按每个 `[` 重扫整个后缀，也没有递归扫描。此判断仅针对新增 destination 扫描，未声称原 parser 的 overlaps 或所有格式匹配均严格线性。补充回归覆盖三个失败样本以及非法角括号包装后立即出现合法标签的恢复，真实 Web/TXT 输出也验证后续来源仍可用。独立同一探针已通过，结果在 `phase3-review-probe-green.log`。

## 实际局部验证

修正后独立运行以下八组现有/本轮测试，**166 项通过，0 失败**：

```sh
cd harmony/deepread
./node_modules/.bin/tsx --tsconfig ../chat/tsconfig.json --test \
  ../chat/src/test/markdown_blocks.test.ts \
  ../chat/src/test/markdown_math.test.ts \
  ../chat/src/test/native_markdown_inline.test.ts \
  ../chat/src/test/markdown_cache.test.ts \
  src/test/section_writer_tools.test.ts \
  src/test/deepread_template.test.cjs \
  src/test/deepread_markdown_links.test.cjs \
  src/test/deepread_export_files.test.cjs
```

日志：`/tmp/deepread-harmony-review-20261007/phase3-review-tests.log`。另单独运行上述 baseline/current 探针，三个来源恢复断言均通过。

未发现本阶段仍待修的确定缺陷。Node fixture 验证了实际生产解析、写入、渲染及格式化逻辑；不能替代 ArkTS 编译、实际 Web 引擎点击、原生文件分享、真实 Provider 输出或真机验收。
