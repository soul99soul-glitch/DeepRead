# Phase 3：正文图与自定义模板占位符

日期：2026-10-07。处理 H08、H09；没有修改其他 worker 的源码、共享 Markdown parser 或用户迁移内容。已读取本仓库 ArkTS skill、audit-reader.md 与 findings.md。

## H08：正文图候选门控

在 `section_writer_tools.ts` 的 NARRATIVE 写入 owner 增加局部 URL 认可函数，复用 `candidateForUrl`：候选池中不存在或 confidence 为 reject 的 URL 被置空。timeline 与 core_points 的文本、日期、支撑内容和来源仍正常写入；只有被拒绝图片的图注同时清空，合法 inline 图及图注保留。

该规则在生产解析器构造 NARRATIVE 数据时执行。没有增加 renderer/export 防御，没有为旧输出增加 metadata，也没有网络探测、重试或图片状态机。

回归从生产 `writeStructuredStage` 进入真实 writer，额外 image_url 字段同时覆盖候选池外、reject 与合法 inline 图。核对存储 output、editorial/custom HTML、Markdown 导出：非法 URL 消失，正文保留，合法图和对应图注保留。

## H09：CSS 示例不消费正文

在 `DeepReadTemplate.ets` 的自定义模板渲染中保留捕获的 style block，只对其余模板片段执行原有一次占位符替换。缺失章节补全依据实际被替换的正文 content slot 判断；style 中的 summary/content 不再消费文章章节。

没有引入 HTML parser 或占位符 sentinel/index 状态。原有 HTML 注释移除、模板安全清洗、shell 包装与章节补全继续复用。生成的文章文本不会再被当作第二轮模板输入替换。

回归覆盖小写/大写 style 标签、CSS comment 中 summary/content、CSS 配合无正文槽/真实 summary 槽/真实 content 槽。核对 CSS 保留、可见正文完整、每个主要章只出现一次，以及标题中槽样文字仍原样显示。

## 实际验证

修复前新增两条回归后：59 项中 57 通过、2 失败。失败分别为候选池外/reject URL 仍写入 output，以及 CSS comment 被替换导致 CSS 原文被污染。日志：`/tmp/deepread-harmony-review-20261007/phase3-reader-red.log`。

修复后同一聚焦测试入口：59/59 通过。日志：`/tmp/deepread-harmony-review-20261007/phase3-reader-green.log`。

```sh
cd harmony/deepread
./node_modules/.bin/tsx --tsconfig ../chat/tsconfig.json --test \
  src/test/section_writer_tools.test.ts src/test/deepread_template.test.cjs
```

本 worker 未运行全量测试、类型检查、ArkTS lint、SDK 构建、模拟器/真机或 Provider 验收；由主 agent 在 Phase 3 整合后执行。59/59 表示真实生产函数的 Node 回归通过，不代表设备行为或 Web 引擎验收。
