# 鸿蒙深度阅读审查与修复交付

2026-10-07。本轮确认 10 项真实问题，全部完成修复；三个修复阶段均经过独立 subagent review。第三阶段复审还找出初版链接解析的新回归，已在同一阶段补失败测试、修正并复核。没有剩余已确认但未修复的问题。

这表示本轮确认问题已闭环，并不表示所有设备、网络和 Provider 组合都已验收。

## 范围与依据

以鸿蒙独立 DeepRead 的真实入口为范围，分别审查生成调度与结构化写作、资料导入与持久化、阅读模板与导出、发现与设置导航、AI 协议与后台生命周期。审查记录包含调用链、可观测复现、二次判断及排除理由：

- [生成链路](audit-pipeline.md)
- [资料与存储](audit-storage.md)
- [阅读与导出](audit-reader.md)
- [发现与设置](audit-discovery.md)
- [Provider 与生命周期](audit-provider.md)

仓库开始 HEAD 为 `fcfbc3bf086558c9793716c5144ed1d06189452b`；已有 iOS/Harmony 迁移 WIP，harmony/ 整体未跟踪。所有修复与开始时的鸿蒙文件快照比较，未把迁移当成本轮 diff，也没有 reset、stash、clean、提交、推送或发布。

## 确认问题与最终行为

| ID | 级别 | 原来的真实问题 | 修复后行为 |
|---|---|---|---|
| H01 | P1 | Responses 成功终态提前关闭 native HTTP，status=0 被误判成网络失败。 | 仅协议终态成立且状态为 0 时允许成功；已知 HTTP 失败、Provider/transport 错误及取消继续拒绝，工具发布和 checkpoint 顺序保留。 |
| H02 | P2 | A 的 native stop 尚未完成，B 注册；stop 完成后没有恢复 B 的后台租约。 | stop 成功后按最新活动和后台状态重新求值；前台、销毁及 start/stop 失败不会形成自动重试循环。 |
| H03 | P2 | PDF 原始文字先截取再清洗，已丢尾部却显示未截断。 | extractor 将真实遗漏事实传给 importer；预算边界、仅遗漏空白、图片或空页不会误报，Chat 原字符串入口和预算合同保留。 |
| H04 | P2 | 多热点来源的正文披露都显示全局 A+B，正文与来源标题不对应。 | Discovery 每份资料显示自己的正文；Composer 保留完整粘贴预览。 |
| H05 | P2 | 发现根内容隐藏挂载后第一次显示不加载热点。 | 每次可见调用原加载入口，使用已有 loading 门防止并发重复。 |
| H06 | P2 | 设置写队列未完成便离页，发现读取中间配置后不再刷新。 | Header、系统返回和 Dock 共用离页判断，等待保存及失败恢复完成再返回。 |
| H07 | P3 | 原文打开失败提示“已复制链接”，实际未复制。 | 准确提示浏览器打开失败，不增加自动复制操作。 |
| H08 | P2 | NARRATIVE 配图绕过既有候选池/reject 限制。 | 写入时复用候选查找；池外和 reject 图及对应图注清空，正文、合法图和图注保留。 |
| H09 | P2 | CSS 注释中的正文占位符消费章节，正文进入 style 后不可见。 | style 原文保留，仅其他模板段替换占位符；依实际正文槽补回缺章，保持一次替换。 |
| H10 | P2 | Markdown 平衡括号 URL 被截短，角括号 URL 丢失。 | 链接和图片共用有限向前扫描，完整保留合法目标；既有 scheme 白名单、代码和公式优先级保留。复审修正失败目标吞掉后续合法来源的回归。 |

逐项分类与阶段安排见 [findings.md](findings.md)。未新增依赖、权限、重试框架或旧稿迁移；没有删除标记为不可达的共享代码。

## 阶段执行与独立复核

| 阶段 | 范围 | 实施证据 | 独立 review |
|---|---|---|---|
| Phase 1 | H01/H02，协议与后台活动 | 先失败回归后修复，49 项聚焦检查通过；构建及保活 ETS lint 通过。 | [通过](phase1-review.md)，确认错误/取消/工具顺序与 stop 竞争的真实边界。 |
| Phase 2 | H03–H07，资料与发现 | PDF 注入真实生产 parser/importer 的回归；来源、导航、串行保存回归；5 份修改 ETS lint 与构建通过。 | [通过](phase2-review.md)，独立 37 项聚焦检查通过，直接对照旧源码确认正文归属及中间配置问题。 |
| Phase 3 | H08–H10，阅读与导出 | writer/template 59 项、Markdown 最终聚焦 107 项通过；实际 Web/native inline/TXT 入口回归。 | [通过](phase3-review.md)，独立 166 项检查通过；记录并闭环初版失败链接目标吞掉后续来源的问题。 |

实施报告：[Phase 1](phase1-implementation.md)、[Phase 2 PDF](phase2-pdf-implementation.md)、[Phase 2 UI](phase2-ui-implementation.md)、[Phase 3 reader](phase3-reader-implementation.md)、[Phase 3 Markdown](phase3-markdown-implementation.md)。

收尾仅对本轮差异做一次有限可维护性检查：核对入口、状态 owner、错误与副作用顺序及重复更新风险。来源正文共用一个私有方法；设置的三条离页路径共用已有保存状态；图片限制在写入 owner；PDF 新结果保留旧字符串包装入口；模板保留一次替换；新增链接扫描使用向前游标。没有发现需要额外整理的具体障碍，未追加架构改造。

## 最终验证

| 层级 | 实际结果 | 证据与限制 |
|---|---|---|
| 完整 Node TS/CJS 测试 | 4459/4459，167 suites，0 失败/取消/跳过 | [最终日志](evidence/final-tests.log)。入口为 `cd harmony && npm test`，执行根 `scripts/test-harmony.mjs`，不是旧包内 TS-only 测试。基线 4425/4425。 |
| 类型检查 | Chat、DeepRead 两个 `tsc --noEmit` 都通过 | [Chat](evidence/final-chat-typecheck.log)、[DeepRead](evidence/final-domain-typecheck.log)。DeepRead 独立配置补自身包名路径映射，修复基线的三条 TS2307；无新依赖。通过日志为空。 |
| ArkTS lint | 本轮修改的 7 份 ETS 分阶段检查通过 | [Phase 1](evidence/phase1-lint.log)、[Phase 2 PDF](evidence/phase2-pdf-lint.log)、[Phase 2 UI](evidence/phase2-ui-lint.log)、[Phase 3](evidence/phase3-lint.log)，均无缺陷。没有宣称全仓 lint。 |
| SDK 构建与签名 | 最终 `npm run build` 成功，18.378 秒，SignHap/SignApp 完成 | [最终构建日志](evidence/final-build.log)。构建存在弃用 API、打包等告警，未将构建成功描述为零告警；本轮未为这些告警迁移 API。 |
| 差异格式 | 与任务开始快照逐文件 whitespace 检查通过 | [本轮差异](evidence/task.diff)、[文件列表](evidence/changed-files.json)、[检查结果](evidence/final-diff-check.log)。10 份生产源码、1 份检查配置、9 份测试；不包含原迁移 WIP。 |
| 模拟器安装与启动 | 最终签名 HAP 覆盖安装成功，EntryAbility 启动成功 | `app.amber.deepread.reader`，HarmonyOS 6.1.1 / API 24，任务启动的 Amber DeepRead QA。见 [设备记录](evidence/simulator-validation.md)。 |
| 发现与设置 UI | 冷启动发现展示热点；关注词写入、返回、重进读取通过，并恢复为空 | [发现最终截图](evidence/final-discovery.jpeg)、[保存后布局](evidence/phase2-persisted.json)、[恢复后布局](evidence/phase2-restored-final.json)。 |
| 实际 ArkWeb 模板预览 | 仅在 style CSS 注释中放 content 槽，正文仍可见 | [预览截图](evidence/phase3-css-preview-settled.jpeg)。草稿实际为 `<style>/* {{content}} */</style>>`，末尾多出的 `>` 是输入工具产生的可见文字，不影响有效 style/占位符问题；正文已在 style 外补全。测试草稿已放弃，未保存模板。 |

最终 HAP 位于 `harmony/entry/build/default/outputs/default/entry-default-signed.hap`。本轮临时启动的模拟器在取证后停止，其他模拟器保持原状态，应用数据与安装产物保留。

## 二次复核后排除的主要候选

- supervisor 超时/abort 竞争：直接替身可以失败，但真实 AI adapter、HTTP listener 及生产 collectRun 异步链复核为 timeout 与预期 FAILED，排除假阳性。
- 模板成功没有经典四段 READY、部分成功 ok=true、重读/再生成失败保留旧稿：符合实际模板与可读部分结果合同，不擅自改变。
- PDF 导出隐藏图片：现有策略及 iOS 行为一致，判为设计。
- 未调用 RDB 更新方法丢新字段、未调用 purge：没有当前生产调用，不按可达数据丢失修复。
- 固定通知 requestCode 串文章：官方 Want 参数匹配源码不能支持 Android 类比，排除未被证明的结论；不将其当作所有系统版本的设备验收。
- data: 拦截导致模板预览空白：真实模拟器待 ArkWeb 渲染完成后正文完整，排除把初始空帧当故障。
- tools 模式、小说和 council 的共享能力：当前独立 DeepRead 入口不可达，不扩大审查修复范围。

## 尚未验收的边界

真实 OpenAI/Claude/Gemini/Responses 服务全流程、原生 PDF 引擎处理真实文件、真机长期后台系统调度与通知交互、原生文件选择器/系统分享/PDF 导出，以及所有屏幕形态没有在本轮完成现场验收。对应 Node 回归、编译签名和模拟器 UI 已完成，但这些证据不能替代上述行为。

本轮没有增加权限、凭据或发布操作来绕过这些验收边界。源码、测试和文档保持未提交、未推送状态。
