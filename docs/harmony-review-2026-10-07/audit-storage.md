# 资料与持久化审查

审查范围：独立 DeepRead 的 Composer → RDB → run repository → 来源采集/合并 → 原始来源页面，以及完成文章的 Workspace 产物。只读审查实现；本文件记录复核证据，不表示问题已经修复。

## 确认项

### B1 / P2：PDF 已停止提取，但保存资料未记录截断

- 文件：`harmony/entry/src/main/ets/platform_impl/DeepReadSourceImporter.ets:54`、`PdfDocumentParser.ets:27-41`、`harmony/deepread/src/main/ets/domain/input_sources.ts:31-37`。
- 触发：PDF 超出原始提取预算，并含行末空格、多余空行等清洗会移除的字符。
- 调用链：`DeepReadComposerSheet.pickFiles` → `importDeepReadSource` → `parsePdfPreviewBytes(bytes, 40001)` → `parsePdfPath` 先限制原始字符 → `makeInputSource` 清洗，再按清洗后字符数判断 `truncated`。
- 影响：正文实际只保存前缀，尾部证据丢弃，但资料返回 `ready`、`truncated=false`、`note=null`，用户无法判断导入不完整。当前每来源保存预算本身是明确设计；缺陷是已经丢弃文本却不记录。
- 复现：通过 TypeScript transpile + VM 执行生产 `PdfDocumentParser.ets`、`DeepReadSourceImporter.ets` 和 `input_sources.ts`，仅注入原生 `fileIo`/`pdfService`。一个 PDF text object 内容为 `'正文内容  \n'.repeat(8000) + '此处为 PDF 尾部关键证据。'`。结果：`rawLength=56015`，`contentLength=28573`，`truncated=false`，`note=null`，`tailSaved=false`，`status=ready`。
- 二次复核：这不要求读取全本 PDF，也不要求扩大现有预算；清洗前与清洗后预算不同造成标记遗漏。仅按返回字符串长度达到 40001 就标记截断会产生边界假阳性：原始文本恰好结束、清洗后低于 40000 时可能并未丢弃文本。修复应由提取器保留“还有正文但因预算停止”的事实，或精确记录当前 text object 存在未读取字符；需要兼顾旧 chat 调用方的字符串 contract。
- 最小修复方向：为 DeepRead 导入提供准确提取截断事实，`makeInputSource` 的保存截断与提取截断合并使用既有 `truncated`/`note`；说明正文已截断，不宣称已保存满 40000 个清洗后字符。
- 回归：长 PDF 含清洗字符时尾部丢弃且明确标记；短 PDF 正文完整、无截断；原始长度刚好等于边界、清洗后低于预算时无丢弃且不误报；超预算且清洗后仍超预算沿用保存截断。

### B2 / P2：多热点来源卡片展开后显示整批热点正文

- 文件：`harmony/entry/src/main/ets/pages/DeepReadSourcesPage.ets:233-235`（host 分支同样在 160）；`DeepReadArticlePage.ets:426-428`；`harmony/deepread/src/main/ets/domain/discovery_input.ts:12-20`。
- 触发：从多热点来源创建文章，其中至少两个 text 来源没有 URL，或对应网页采集失败而保留榜单文本。
- 调用链：`discoveryHotspotInputs` 每个榜项建立独立 `kind='text'` 来源，并带 `researchSource` 排名归属 → `saveDiscoveryInputs` 将各 text 来源正文拼接到全局 `output.inputText` → Sources 页面针对每个 text 来源都优先显示全局 `inputText.substring(0,40000)`。
- 影响：每张来源卡片标题/字符数表示单个热点，但展开正文都是整批热点，来源归属和正文不一致。采集成功后变成 web 来源的卡片不触发；单一 Composer 粘贴文本使用完整 `inputText` 是正确设计。
- 二次复核：不是需要统一来源样式，而是错误选择了文章级完整文本。真实 owner 已明确：全局 `inputText` 同时承载 Composer 完整粘贴和 Discovery 多来源合并文本。
- 最小修复方向：保留真正粘贴来源的完整文本预览；Discovery text 来源使用各自 `source.content`。当前实际 Discovery 来源均有 `researchSource`，Composer 粘贴来源没有；这可作为小范围业务条件。主 agent 应根据项目约定二次裁定最终判据。
- 回归：两个无 URL 榜项 A/B，展开 A 只见 A，展开 B 只见 B；单一 Composer 粘贴超过 40000 字符仍显示原文前 40000；文件/web 来源仍使用自身正文。

## 排除项

- `RdbRepository.outputWithSectionState:132-165` 构造输出时遗漏 `bottomLine`、`impacts`、`watch`、`sources`、`uncertainties`：静态上会丢字段，但唯一调用 `updateSectionState` 在当前生产代码没有调用方。独立 DeepRead 调度直接通过 RunRepository adapter 保存完整输出，故不列可达缺陷、不为此扩改。
- `RdbRepository.updatePhase` 只更新 entry phase、不更新 output phase：当前没有生产调用，排除。
- `platform/database.ts` schema 名为 `deep_read_cache`，实际 RDB owner 为 `deepread_cache`：schema 常量当前不被实际 RDB 使用，不是运行时表名错误。
- `purgeExpired`/`purgeHistoryRetention` 会删除过期文章：当前无生产调用，TTL 仅供刷新标记，不能据接口存在声称用户文章自动丢失。
- 每来源保存 40000 字符，生成最多前 10 个 ready 来源：Composer/来源页面明确告知，且全部来源另存，属于设计。
- force 采集失败保留已有 ready 正文：`mergeCollectedSources:63-64,74` 明确实现“成功重读才替换”；复核为保留已验证正文的设计，不据此添加通用重试/状态机。
- prefetch 返回最多 MAX_SOURCES，但 `onCollected` 传全部去重正文：run context 使用全部回调结果保存再按 generation budget 筛选，因此并非额外资料丢失。
- Workspace 产物失败不回滚文章 RDB：source of truth 仍为 RDB，产物状态独立记录且提供重试；原子写保留旧文件，属于设计。
- 通用 HTML fallback 会包含部分导航、页脚，DOCX regex 假定 `w:` 前缀：没有本轮真实输入/可观测失败支持具体独立应用缺陷，不追加宽泛解析防御。

## 已执行验证

在 `harmony/deepread` 运行：

```sh
node --import tsx --test src/test/input_sources.test.ts src/test/deepread_sources_ui.test.cjs src/test/deepread_artifact_store.test.cjs src/test/source_prefetcher_entry.test.ts
```

24 项通过，覆盖现有来源持久化/状态、浏览器打开与页面生命周期、实际原子文件写失败保留旧文件、产物重试，以及实际 Entry abort/HTTP 取消。它们未覆盖 B1/B2，不能据绿色测试否认上述缺陷。本审查未修改实现、未启动全量测试/SDK 构建、未执行原生 PDF 真机或 UI 验收。
