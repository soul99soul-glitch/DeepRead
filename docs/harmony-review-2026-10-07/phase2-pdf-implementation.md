# Phase 2：PDF 提取截断事实（H03）

## 已完成的行为

PDF 导入仍提取最多 `MAX_INPUT_SOURCE_CHARS + 1` 个原始字符，仍由 `makeInputSource` 清洗并保存最多 40000 字符。新增的 `PdfTextExtraction` 只保留 `{ text, truncated }` 两个字段，DeepRead importer 合并提取截断事实和既有保存截断结果。

`PdfDocumentParser.ets` 的 DeepRead 路径在达到原始字符预算后，仅探查有没有尚未保存的非空正文：当前 text object 的非空后缀、下一个非空 text object、或者后续页面的首个非空 text object。发现即停止，不读取之后的 object 或页面、不拼接后续正文。只有空白、图片、空页时继续确认，不能仅凭字符串长度达到 40001 就认定截断。每个打开的 native page 都在 `finally` 中释放；需要读取下一页时先释放、再沿用现有 `setTimeout(0)` yield。

`DeepReadSourceImporter.ets` 对清洗后不足 40000 字符、但实际丢弃了 PDF 后续正文的来源设置既有 `truncated=true`，并说明“内容已截断：PDF 正文超过提取预算，后续正文未保存。”若清洗后正文也超过保存预算，则保留 `makeInputSource` 已有的 40000 字符限制说明。

`parsePdfPath`、`parsePdfEntry`、`parsePdfPreviewBytes` 继续返回字符串，保留 Chat 在预算耗尽后停止提取的行为。实际调用依据是 `DocumentSupport.ets` 的 PDF document / preview 分支：预览把 path / bytes 的返回字符串传给 `truncatePreview`，文档提取调用 `parsePdfEntry`。只有新的 DeepRead `parsePdfPreviewWithTruncation` 路径启用边界探查。未新增 SDK API、完整文档提取或解析框架。

## 复现与回归证据

新增 `harmony/deepread/src/test/deepread_pdf_import.test.cjs`，执行生产 parser、importer 和 domain normalizer，只注入原生 `pdfService` / `fileIo` 等 SDK 服务；临时 PDF preview 在真实 OS 文件系统中写入并清理。

修改实现前先运行 9 项测试，5 项通过、4 项失败。失败包括：含清洗字符的长 PDF 丢弃证据但没有截断标记、预算后的下一 object / 下一页没有被识别。其余测试已证明旧代码对短 PDF 和原始文本恰好达到预算时不应被宽泛标记。日志：`/tmp/deepread-harmony-review-20261007/phase2-pdf-red.log`。

最终新增 10 项 PDF 回归，涵盖：

- 含行末空格的长 PDF 清洗后不足保存预算，仍明确记录尾部证据被丢弃。
- 短 PDF 全文、段落与页面分隔保持，无截断提示。
- 原始正文恰好 40001 字符且清洗后低于保存预算时不误报。
- 当前 object 仅剩未保存空白时不误报。
- 下一非空 object / 下一页证实正文遗漏后停止，空白 / 图片不触发。
- 剩余原始预算恰好被分隔符消耗时，当前 object 或页面的非空正文遗漏仍被记录。
- 预算后的所有页面仅含空白 / 图片时保持完整状态。
- 清洗后仍超保存预算时沿用既有保存截断说明。
- Chat 的字符串返回 contract、原始预算停止行为保持。
- 原生 page/document 释放、yield 前无 native page 持有、preview 临时文件清理。

聚焦测试：

```sh
cd harmony/deepread
node --import tsx --test src/test/deepread_pdf_import.test.cjs src/test/input_sources.test.ts
```

结果 15/15 通过，日志：`/tmp/deepread-harmony-review-20261007/phase2-pdf-green.log`。

两份改动 `.ets` 的 focused lint 已通过：

```sh
harmony/scripts/lint-arkts.sh \
  harmony/entry/src/main/ets/platform_impl/PdfDocumentParser.ets \
  harmony/entry/src/main/ets/platform_impl/DeepReadSourceImporter.ets
```

输出 `No defects found in your code.`，退出码 0，日志：`/tmp/deepread-harmony-review-20261007/phase2-pdf-lint.log`。

按 `code-simplifier` 对本次修改做过一次有限可维护性检查：提取状态由 parser 唯一持有、来源标记由 importer 合并；旧 Chat contract 独立保留；没有需要额外抽象或删除的代码。

## 验证边界

此子任务未运行全量测试或 SDK build，也未安装应用、执行原生 PDF 引擎真机提取或 UI 验收。Node regression 是生产逻辑和资源释放路径的注入验证，focused lint 不能替代 ArkTS 构建。阶段独立 review 和最终构建 / 设备验证由主任务整合执行。
