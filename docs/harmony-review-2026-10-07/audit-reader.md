# 阅读、模板与导出审查

日期：2026-10-07。范围为独立鸿蒙 DeepRead 的阅读页、原生/Web 正文、模板工坊、模板渲染、TXT/Markdown/PDF 导出，以及它们真实调用的共享 Markdown AST。审查阶段只读实现；本文件记录修复前证据，行号可能随后续修复变化。

阅读了 `.agents/skills/harmony-arkts/SKILL.md` 和本轮 `plan.md`。未运行全量测试或 SDK 构建，未做设备 UI 验收。主 agent 已二次核对以下三项成立。

## 确认问题

### R1 / P2：正文图片绕过候选池与 reject 门控

写入链：`agent/structured_stage.ts:57` 的 `writeStructuredStage` → NARRATIVE writer → `agent/section_writer_tools.ts:366` 的 `parseTimeline` / `parseCorePoints` → `:745` / `:759` 直接保留模型的 `image_url`。下游 `platform_impl/DeepReadTemplate.ets:144` / `:153` 直接渲染图，`domain/export.ts:97` / `:107` 直接导出图片链接。

实际复现：调用生产 `createSectionWriterTools`，分别设置空候选池及 matching candidate 的 `confidence=reject`，向 NARRATIVE 输入包含该 URL 的 timeline/core point。最终 output 两处都保留 URL；实际 `renderEditorialSlantHtml` 产生 `src`，`deepReadToMarkdown` 产生 Markdown 图片。主 agent 另行通过 `writeStructuredStage` 复核：结构化响应额外返回 `image_url` 会进入同一生产链，不能因为 prompt schema 没列出此字段就认定不可达。

二次复核：已有 visuals / extended_reading writer 对候选池外及 reject 图有明确拒绝逻辑；`verifiedImageUrls`、hero/gallery 回归也明确 reject 不得显示。这是正文图片入口遗漏，不是允许模型自由配图的设计。普通杂志不显示图属于既有设计，但 editorial/custom 阅读和 Markdown 导出会受影响。

修复建议：在 NARRATIVE 写入 owner 使用现有候选证据规则验证 timeline/core point 图片；非法图片不影响正文保留。对旧持久输出按实际需要复用图片认可规则。无需网络探测、重试或新图片状态机。

回归建议：候选池外、reject、合法 inline 三种情况，验证存储 output、editorial/custom HTML 及 Markdown；经 `writeStructuredStage` 验证额外字段不会绕过门控。

### R2 / P2：CSS 注释中的模板占位符吞掉可见正文

位置：`platform_impl/DeepReadTemplate.ets:475`、`:477`、`:483` 的注释处理、content 判定及全串替换。代码仅排除 HTML 注释，仍把 `<style>` 内占位符视为可见章节槽。

实际复现模板：

```html
<style>/* documented {{summary}} */</style><h1>{{title}}</h1>
```

`validateTemplateHtml` 返回空字符串，表示通过。`renderCustomTemplateHtml` 将 summary 插入 CSS 注释，又把该章记为已经消费；最终可见正文没有 summary。将注释改为 `{{content}}`，全部章节都进入 `<style>`，且缺失章节补全整体关闭，页面仅剩标题。

二次复核：既有 HTML 注释测试明确要求“注释示例不消费可见章节”，模板工坊也承诺缺少章节补在末尾；CSS 注释同样属于有效模板中的说明文字。校验接受却导致正文消失是真问题。

修复建议：保留 CSS，让 `<style>` 内容不参与文章占位符消费与替换，正文仍保持一次替换及现有缺失章节补全。不要为此引入通用 HTML parser。

回归建议：CSS comment 中的 summary/content、CSS 与真实正文槽同时存在、CSS 内容保留、各正文章节只出现一次。

### R3 / P2：Markdown 正文的来源链接截断或消失

共享 owner：`chat/markdown_blocks.ts:228` / `:243` 的图片和链接 destination 正则使用 `[^)]+`，不能消费配对圆括号；也没有去掉标准角括号 destination 包装。使用者包括 DeepRead Web 的 `DeepReadMarkdownHtml`、原生 `MarkdownText` 和 TXT 格式化。

生产 parser / renderer 实际复现：

```md
[报告](https://example.com/report_(2026))
[来源](<https://example.com/source>)
```

第一条生成 `href="https://example.com/report_(2026"`，可见正文末尾多一个 `)`；原生阅读同 AST，打开的地址也被截断。第二条 token URL 带 `< >`，Web 的 `safeDeepReadLink` 拒绝，导致文章里的链接消失。

二次复核：来源列表专用 renderer 和 synthesis 来源序列化已对括号 URL 作处理，但 answer/summary/立场等 Markdown 正文仍直接走共享 parser。括号真实存在于报道路径和 Wikipedia 地址，属于链接完整性缺陷；不是要求增加新 Markdown 排版能力。

修复建议：对链接与图片 destination 做有限扫描，支持配对括号、转义及角括号包装，拆出真实 URL 后继续既有安全 scheme 校验。不迁移 Markdown 框架，不追加无关嵌套样式特性。

回归建议：括号路径和查询、角括号 destination、escaped label、unsafe scheme；同时核对 parser token、最终 Web href 与 TXT 的完整 URL。

## 已排除或未确认的候选

| 候选 | 结论 | 证据与边界 |
| --- | --- | --- |
| 独立 PDF 隐藏图片 | by design | `deepread_export_panel.test.cjs` 明确测试；iOS `DeepReadDetailView.swift:299` 沿用相同打印策略。 |
| Workbench 经 ChatKvStore 读取数字字号 | 假阳性 | 虽静态返回类型是 string，真实 number 经 `parseFloat` 可正确读出；适配器复用同一个 Storage。 |
| 成功分享后文件留在 cache | by design | ShareController.show 仅表示面板打开，接收方仍可能需要读取；代码注释及独立 URI 测试要求保留文件。 |
| 精简 Markdown 的部分嵌套格式 | 本轮不追加 | parser 已有明确格式优先级；修复来源 destination 无需扩展整套嵌套解析。 |
| 阅读 Web/工坊预览拦截 data URL | 未确认 | loadData 完成事件可能是 data URL，但尚无设备证据证明内部 loadData 会触发 override 并被拦截，不列为确认缺陷。 |

## 实际验证与探针

临时探针 `/tmp/deepread-reader-audit-probe.ts` 记录 R1、R2 的生产函数复现，使用现有 `deepread_ui_fixture.cjs` 加载真实 renderer。运行方式：

```sh
cd harmony/deepread
./node_modules/.bin/tsx --tsconfig ../chat/tsconfig.json /tmp/deepread-reader-audit-probe.ts
```

R3 在只读 Node 探针中调用相同 fixture 加载生产 `parseInline` / `deepReadMarkdownHtml`，输出实际 token 与 HTML；关键输入和输出已记录在 R3，未另外保存脚本。

局部验证命令：

```sh
cd harmony/deepread
./node_modules/.bin/tsx --tsconfig ../chat/tsconfig.json --test \
  src/test/deepread_template.test.cjs \
  src/test/deepread_reader.test.cjs \
  src/test/deepread_export_files.test.cjs \
  src/test/deepread_export_panel.test.cjs \
  src/test/deepread_export.test.ts \
  src/test/synthesis_templates.test.ts
```

结果：126 项通过，0 失败。额外探针暴露的是上述现有测试未覆盖的输入；测试通过不代表这些问题不存在，也不代表 ArkTS 编译、真实 Web 引擎、设备导出或 Provider 已验收。
