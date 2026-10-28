---
name: amber-ui-design
version: 1.0.0
description: 当用户要生成或改造 Amber 网页卡片、结果卡、信息面板、仪表盘、交互小工具或 MiniApp，要求移动端优先、可访问、可交互或有自然动效时使用。负责选择 Amber 输出协议并交付完整可运行产物；不用于普通文字排版或真实照片生图。
---

# Amber UI Design

为手机上的 Amber 产物设计清晰、真实、可操作的界面。先判断输出出口，不要把两个协议混在同一个回复里。

## 先选输出协议

- **原生任务界面（`task-ui`）**：当用户需要比较决策、填写表单、分组浏览/探索参数（含数字联动指标/进度/图表）或在消息内翻卡复习/选择题抽考时，优先输出一个独立的 `task-ui` JSON 代码块。
- **静态网页卡片**：用户只要看结果、摘要、图表、流程或信息排版时，输出一个完整的 `show-widget` fenced JSON，包含 `title` 和 `widget_code`。普通 HTML 卡片的内容由宿主放进 `#root`；代码自包含、静态、可直接渲染，不要输出多个 widget、占位骨架或伪造控件。
- **一般交互网页**：用户说要网页、页面、可交互展示或轻量演示，但没有明确说「小应用 / MiniApp / 小程序」时，仍走 `show-widget` 的 `full_html` 路由。它要求 `spec.html` 是完整 HTML 文档，最小形状如下：

    ```show-widget
    {"title":"交互网页","renderer":"full_html","spec":{"html":"<!DOCTYPE html><html lang=\"zh-CN\"><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0}main{padding:24px}</style></head><body><main><h1>真实标题</h1><p>真实内容</p></main></body></html>"}}
    ```

    `full_html` 可以使用内联 CSS/脚本完成页面自身的轻量交互，但遵守宿主 validator：不要用 iframe、object、embed、form、`fetch`、XHR、WebSocket、外部 CDN 或外部 `<script src>`；仅在确有需要时使用 Amber 提供的 `https://amberagent.local/full-html/` 本地运行时。它没有 MiniApp 的宿主动作、存储、权限和 Amber bridge。
- **MiniApp**：只有用户明确说「小应用」「MiniApp」「mini app」「小程序」，或明确要求修改已有小应用时，才输出 MiniApp。一般的「做一个交互界面」不能自行升级到 MiniApp，否则运行端不会注入或消费 MiniApp 输出。明确 MiniApp 请求时优先遵守系统注入的 MiniApp 说明；若当前上下文没有该说明，使用这个精简 V3 形状，仍只输出一个严格 JSON 对象：

    ```json
    {"title":"1-20 字标题","description":"1-80 字描述","icon":"最多 2 个字符","category":"tool","permissions":[],"html":"<!DOCTYPE html><html lang=\"zh-CN\"><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body><main data-amber-reveal>真实结果</main></body></html>"}
    ```

    `category` 只能是 `tool`、`game`、`info` 或 `custom`；`permissions` 只列实际调用的 Amber 权限。需要宿主动作、持久化、网络、系统能力或跨轮状态时，先请用户明确要小应用，再按 V3 schema 生成；不要把这些能力偷偷塞进 `full_html`。

生成揭示由宿主负责。普通 HTML 卡片不需要自己添加揭示代码，宿主自动作用于 `#root`；新 MiniApp 将在 `[data-amber-reveal]`、`main` 或 `#app` 中选择主要结果。不要写骨架遮罩、假进度、Agent 日志、TOK/S、“正在生成”假数据或另一套揭示脚本。

## 移动优先的结构

- 先定一个清楚的主任务和视觉层级，再安排标题、关键数值、解释、次要信息和操作；少用装饰，避免把桌面两栏硬缩到手机。
- MiniApp 使用完整 HTML、`lang`、viewport、`body { margin: 0 }` 和 `system-ui`/`-apple-system`；内容在 320px 宽度下自动换行，不横向溢出。新闻、阅读、列表和长内容必须可纵向滚动。
- 每个可点击控件的触摸区域至少 44×44 CSS px；图标按钮有可访问名称，键盘焦点清楚，颜色不是唯一状态提示；支持深色模式和 `prefers-reduced-motion`。
- MiniApp 将主要结果放在唯一的 `<main data-amber-reveal>` 内，并让它适合一个手机视口；宿主首次打开时会选择该结果播放生成揭示。

## 日常动效配方

动效服务于层级、反馈和状态变化，不用来掩盖空内容。可用语义化 token：`quick` 约 150ms、`fast` 约 250ms、`slow` 约 400ms，出场优先 `cubic-bezier(.22,1,.36,1)`。

- `rise-in`：真实内容出现时用 opacity + 6–12px 位移；
- `stagger`：列表按 20–40ms 错开，最多错开少量首屏项；
- `press`：按下时轻微 scale（约 0.98）和颜色反馈，松开回弹；
- `state-change`：只过渡 opacity、transform、颜色或阴影，避免动画撑动布局；
- `reveal`：只表示真实内容已准备好。生成完成后的线框/点阵揭示由 Amber 宿主负责，模型不得自行计时或声称进度。

full_html 页面和 MiniApp 的自身交互可以使用内联脚本，但不得注入外部脚本、远程库或 CDN；3D 只可按各自运行时协议使用精确的本地 Three.js 资源，并声明 MiniApp 所需权限。尊重 reduced motion，减少或关闭非必要循环动画。

## 交付前自检

- 静态卡片/网页是否只有一个可解析的 `show-widget`，且 `full_html` 使用 `spec.html`；明确小应用请求时 MiniApp 是否只有一个完整 JSON；
- 交互、网络、存储和系统调用是否真的存在且权限最小；
- 320px 宽度、滚动、44px 触摸区、标签和焦点是否成立；
- 是否没有外部脚本、伪造生成进度、占位文案或不可操作的假按钮；
- 若是 MiniApp，是否保留 `<main data-amber-reveal>`；所有生成揭示是否都留给宿主。
