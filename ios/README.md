# 深度阅读 DeepRead

一款 iOS 深度阅读应用：输入主题、链接、文本或文件，自动检索并采集来源，分阶段生成结构化的杂志式长文，也可对原文做逐段精读。支持 iPhone 和 iPad，最低 iOS 26。

DeepRead 由 [AmberAgent-iOS](https://github.com/soul99soul-glitch/AmberAgent-iOS) 的深度阅读功能拆分而来，使用独立的沙盒、设置和 Keychain，不读取 AmberAgent 的数据。

## 构建

依赖：Xcode 26、[XcodeGen](https://github.com/yonaskolb/XcodeGen)。

```sh
git clone https://github.com/soul99soul-glitch/DeepRead.git
cd DeepRead/ios
xcodegen generate
xcodebuild -project AmberDeepRead.xcodeproj -scheme DeepRead \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

模型调用为纯 Swift 实现（OpenAI 兼容 Chat Completions / Responses 与 Claude Messages），Markdown 解析使用 [swift-markdown](https://github.com/apple/swift-markdown)，字体（思源宋体、JetBrains Mono）随仓库内置，无外部子模块与脚本依赖。真机构建需在 Xcode 中设置开发团队和签名。

## 功能与使用

- 发现：原热榜来源、跨榜主题聚合、缓存、关键词过滤、Wi-Fi 刷新限制、可选标题翻译。
- 创建：主题、粘贴文本、多网页链接、多文件。文件支持 txt、md、json、csv、文本 PDF、DOCX 等；内容超过 40,000 字符时明确记录截断。扫描 PDF 无可提取文本时显示错误。
- 阅读库：完整本机历史、标题和正文搜索、任务状态筛选。
- 阅读器：原结构化杂志排版、原始来源与采集状态、失败与部分章节重试、保留段落和列表的文本/Markdown/PDF 分享，随系统深浅外观显示。
- 设置：多个模型和搜索服务、凭据保存、阅读字体字号、内置及自定义模板；支持模型生成模板草稿，再编辑、预览及保存。字号范围为 70%–180%；新建模板按手机宽度展示，并适配系统深色外观。

首次使用先在“设置”配置并选定阅读模型，点击“保存并应用”。当前模型入口支持 API Key 的 OpenAI 兼容 API（Chat Completions 或 Responses）与 Claude API。原应用的 OAuth 登录入口、Gemini 独立协议入口及自定义 Live Activity 尚未迁入这个 application target。

搜索继续使用原 `IOSSearchExecutor`：免费聚合、Tavily、Exa、智谱、Brave、Serper、SerpAPI、Jina。原 Shared 搜索配置类型能够保存，执行器尚未实现的类型在界面中明确标注。免费搜索保留原多引擎并发、Google WebView 补充搜索、Jina Reader 正文补充读取及相关开关。阅读管线保留多角度查询、来源去重、正文及图片采集、分阶段生成。每个搜索角度使用已保存的结果数量设置；全部用户来源持久化，生成沿用最多 10 条有效来源的消费预算。补充搜索失败会保存并展示原因，重试只清理上一轮自动搜索警告。

文章任务由应用级 runtime 持有；离开页面不取消。开始生成时提交 iOS 26 持续后台处理任务，系统接管后可在后台继续搜索、抓取和生成，并显示系统进度与取消入口。短时后台额度只用于等待接管；到期不取消已提交的持续任务。系统拒绝持续任务时仅有短时额度；系统到期或用户取消时保存中断状态并释放执行权。强制退出后，冷启动保留已有文章、标记中断并提供重试，不自动恢复模型请求。

模型生成默认使用流式响应，按实际收到的正文、推理和工具参数增量更新系统进度；阶段与字符数共同构成估计进度，不使用定时器虚增。OpenAI Chat、Responses 和 Claude 均保留完整结果解析。显式配置 `stream:false` 或服务返回普通 JSON 时仍等完整响应；首段输出等待过久、长时间无输出、资源限制或用户取消仍可能触发系统中断，持续后台处理不保证每次请求都能完成。

## 许可

沿用 AmberAgent 的许可方式，见 [LICENSE](../LICENSE)。
