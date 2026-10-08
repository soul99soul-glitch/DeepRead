# Phase 2 独立复审

结论：**通过；本次范围内未发现需要修正的可达问题。** H03–H07 的修复与确认的触发路径对应，没有扩大到额外解析框架、重试、自动复制或跨页面状态机。该结论是源代码与 Node 回归层面的阶段验收，SDK 构建和设备行为由主任务另行验证。

## 范围与依据

读取本仓库 `harmony-arkts/SKILL.md`，将下面文件与本轮开始快照 `/tmp/deepread-harmony-review-20261007/baseline/harmony/` 比较：

- `PdfDocumentParser.ets`、`DeepReadSourceImporter.ets`。
- `DeepReadSourcesPage.ets`、`BoardPage.ets`、`DeepReadDiscoverySettingsPage.ets`。
- `deepread/tsconfig.json`。
- 新 PDF、Phase 2 UI 回归及既有 Sources UI 回归的本次新增断言。

只读取必要调用方以确认入口和合同，包括 `discoveryHotspotInputs`、来源归一化、原有 Board 活动订阅、Settings Header 和 SubpageDock/TabBar。未审查 Phase 1、iOS 或产品迁移 WIP，未修改实现文件。

## 逐项复核

| 项目 | 复核结果 |
|---|---|
| H03 原始预算与保存预算 | parser 仍保留最多 40001 个原始字符，domain 仍清洗并保留最多 40000 字符。二者独立记录：只有清洗后的保存正文超限时沿用原有保存预算说明；否则仅当确有非空后缀/object/page 被遗漏才设置提取截断说明。 |
| H03 边界误报 | 恰好达到预算不直接标截断；遗漏后缀只含空白、后续 object/page 只含空白或图片时不误报。分隔符耗尽剩余预算时，随后的非空正文仍被识别。探查遇到第一份遗漏正文就结束，不拼接预算后的正文。 |
| H03 资源与旧 Chat 合同 | 每个已打开 page 的原有 finally 保持有效，yield 前释放 page；document 和 preview 临时文件也由 finally 释放。`parsePdfPath/Entry/PreviewBytes` 均返回 string，且关闭探查，保持原来在原始预算耗尽后停止的行为。 |
| H04 来源正文归属 | 真实 `discoveryHotspotInputs` 为每份热点 text 来源附加 researchSource，新的选择条件让它们各自展示自己的 content。Composer 的单一粘贴 text 不带该字段，继续展示 inputText 的 40000 字符预览。两种 UI 分支共用同一选择方法。 |
| H05 首次显示与订阅 | 隐藏挂载不读取；首次显示必调用原 loadHotlist。初始可见的双入口调用由 loadHotlist 在首个 await 前置 loading 的既有门禁去重。observeRuns 保持 unsubscribe 与 token 检查，隐藏时取消且清理订阅，回显再订阅；此改动未引入第二个订阅 owner。 |
| H06 离页与写失败 | Header 和系统返回共用 canLeave，Dock 将该门禁传到 TabBar 的导航副作用之前。pendingWrites 在排队前同步增加，saving 在失败后的 loadSettings 完成前保持 true，因此也阻止恢复期间离页。保持原有失败提示与存储值恢复，不添加自动离页。 |
| H07 文案 | 同步和异步浏览器启动失败都准确说明打开失败，不再声称完成未发生的剪贴板写入；成功无错误提示。 |
| 验证入口 alias | 自身包名映射指向现有 `src/main/ets/index.ts`，与现有 Chat tsconfig 指向同一生产 barrel 的方式一致，strict/noEmit 等检查约束未放宽。该配置只影响独立 TS 检查；未替换运行时模块解析或 Harmony 依赖配置。 |

## 旧行为证据的独立核对

新增方法不存在造成的 red 不能证明旧 bug。为避开该混淆，本次重跑 H04/H06 的旧行为 probe 时，直接将读取路径替换为**本轮开始快照**：

- H04 执行 baseline `SourceSection` 的真实 Text 表达式。A/B 来源的 content 分别是“热点 A 正文”和“热点 B 正文”，两个显示值都得到“热点 A 正文\n热点 B 正文”，确认旧正文归属错误。
- H06 执行 baseline `persistPreference`。A→AI 连续编辑，第一份 flush pending 时读到 A，pendingWrites=2；队列完成读到 AI，pendingWrites=0。结合 baseline Header/Dock 没有门禁、系统未拦截，可以确认提前返回读取旧值的原路径；该生产方法本轮保持不变，修复位于离页入口。

这两项复核不是依赖恢复的临时页面快照，也不是仅依赖新 helper 缺失的失败。H03/H05/H07 的修改前失败与原代码分支也相符。

## 本次实际验证

独立重跑以下两组，全部通过：

```sh
cd harmony/deepread
node --import tsx --test src/test/deepread_pdf_import.test.cjs \
  src/test/input_sources.test.ts src/test/deepread_sources_ui.test.cjs
# 23/23

cd ../..
node --test harmony/entry/src/test/deepread_phase2_ui_regression.test.cjs \
  harmony/entry/src/test/deepread_board_ui.test.cjs
# 14/14
```

这些回归执行生产提取/导入/归一化及生产页面方法，SDK/导航等依赖注入；它们证明预算、文本归属、写入队列及相应清理逻辑，不证明真实 PDF 引擎或实际 ArkUI 生命周期。

本复审未执行全量测试、lint、类型检查、SDK 构建、签名、安装或模拟器/真机 UX 验收，避免与主 agent 的统一阶段检查重复。仍应由集成阶段验证隐藏首次切换、实际设置返回和慢存储时的页面门禁。
