# 深度阅读 DeepRead

一款 iOS 深度阅读应用：输入主题、链接、文本或文件，自动检索并采集来源，分阶段生成结构化的杂志式长文，也可对原文做逐段精读。支持 iPhone 和 iPad，最低 iOS 26。

DeepRead 由 [AmberAgent-iOS](https://github.com/soul99soul-glitch/AmberAgent-iOS) 的深度阅读功能拆分而来，使用独立的沙盒、设置和 Keychain，不读取 AmberAgent 的数据。

## 构建

依赖：Xcode 26、[XcodeGen](https://github.com/yonaskolb/XcodeGen)、JDK 17 或 21（构建 Kotlin 共享框架）。

```sh
git clone --recurse-submodules https://github.com/soul99soul-glitch/DeepRead.git
cd DeepRead
xcodegen generate
xcodebuild -project AmberDeepRead.xcodeproj -scheme DeepRead \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

模型调用、服务商与搜索配置等类型来自 AmberAgent 的 Kotlin Multiplatform 模块，以子模块 `amber/` 引入：构建时由 Xcode 的预构建脚本调用 Gradle 编出 `Shared.framework`（首次需几分钟），Markdown 解析使用子模块中预编译的 `AmberNative.xcframework`，字体也取自子模块。未设置 `JAVA_HOME` 时脚本会尝试 Homebrew 的 `openjdk@17`。真机构建需在 Xcode 中设置开发团队和签名。

## 功能与使用

- 发现：原热榜来源、跨榜主题聚合、缓存、关键词过滤、Wi-Fi 刷新限制、可选标题翻译。
- 创建：主题、粘贴文本、多网页链接、多文件。文件支持 txt、md、json、csv、文本 PDF、DOCX 等；内容超过 40,000 字符时明确记录截断。扫描 PDF 无可提取文本时显示错误。
- 阅读库：完整本机历史、标题和正文搜索、任务状态筛选。
- 阅读器：原结构化杂志排版、原始来源与采集状态、失败与部分章节重试、保留段落和列表的文本/Markdown/PDF 分享，随系统深浅外观显示。
- 设置：多个模型和搜索服务、凭据保存、阅读字体字号、内置及自定义模板；支持模型生成模板草稿，再编辑、预览及保存。字号范围为 70%–180%；新建模板按手机宽度展示，并适配系统深色外观。

首次使用先在“设置”配置并选定阅读模型，点击“保存并应用”。当前模型入口支持 API Key 的 OpenAI 兼容 API（Chat Completions 或 Responses）与 Claude API。原应用的 OAuth 登录入口、Gemini 独立协议入口及 Live Activity 尚未迁入这个 application target。

搜索继续使用原 `IOSSearchExecutor`：免费聚合、Tavily、Exa、智谱、Brave、Serper、SerpAPI、Jina。原 Shared 搜索配置类型能够保存，执行器尚未实现的类型在界面中明确标注。免费搜索保留原多引擎并发、Google WebView 补充搜索、Jina Reader 正文补充读取及相关开关。阅读管线保留多角度查询、来源去重、正文及图片采集、分阶段生成。每个搜索角度使用已保存的结果数量设置；全部用户来源持久化，生成沿用最多 10 条有效来源的消费预算。补充搜索失败会保存并展示原因，重试只清理上一轮自动搜索警告。

文章任务由应用级 runtime 持有；离开页面不取消。iOS 后台执行为 best effort，系统期限到达时保存中断状态并释放执行权。冷启动保留已有文章、标记中断并提供重试，不宣称跨进程自动续生成。

## 许可

沿用 AmberAgent 的许可方式，见 [LICENSE](LICENSE)。
