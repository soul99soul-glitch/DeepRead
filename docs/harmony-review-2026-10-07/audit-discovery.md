# 发现与独立设置审查

审查范围：独立产品的 DeepReadRootPage / BoardPage、HotListState / Aggregator / RSS / Translator、DeepReadDiscoverySettingsPage、SettingDeepReadPage、DeepReadSettingsForm、DeepReadSearchServiceEditorPage、DeepReadModelSelection、搜索草稿、子页面 Dock 与模型配置解析。只读审查，未修改生产代码或仓库测试。

## 确认问题

### D1：隐藏挂载的发现页首次可见时不加载榜单（P1）

- 定位：`BoardPage.ets:243–260`；`DeepReadRootPage.ets:42–43,149–152`。
- 触发：根页面以阅读库或设置为初始 tab（包括路由 tab 参数以及子页 Dock 的替换根页面路径），此时发现内容已挂载、rootVisible=false。第一次点击发现。
- 调用链：Root 保留三个内容 → Board.aboutToAppear 不调用 loadHotlist → rootVisible 改变 → Board.onPageShow → firstShowDone=false，仅设置 true。
- 影响：首次进入发现没有缓存投影或网络加载；必须再次切换回来或下拉刷新。
- 二次复核：firstShowDone 原先假定 aboutToAppear 已开始加载；隐藏挂载破坏该前提。不是缓存刷新策略、设计选择或不可达宿主路径。主 agent 已独立核对。
- 真实方法复现：以 `deepread_ui_fixture.cjs` 的 method extractor 提取 BoardPageContent 的 aboutToAppear/onPageShow，embeddedRoot=true/rootVisible=false；挂载 fetch=0，首次可见 fetch=0、pageVisible=true、firstShowDone=true，第二次可见 fetch=1。
- 最小修复：每次进入可见状态调用 loadHotlist(false)，复用已有 loading 防并发，无需新增重试或新状态机。
- 回归：隐藏挂载→首次显示必须启动加载；初始可见不能并行双抓取；离页取消后再次显示正常。
- 测试陷阱：整个 BoardPage 文件首个 aboutToAppear 属于 spinner，不能用普通 first-match extractor 冒充 Board 生命周期。先按 `export struct BoardPageContent` 切片再提取。

### D2：打开原文失败时虚报链接已复制（P3）

- 定位：`BoardPage.ets:502–515,1050`。
- 触发：热点操作菜单“查看原文”调用 startAbility，同步抛错或异步拒绝，例如浏览器不可用。
- 影响：提示“已复制链接”，但剪贴板仍为用户原有内容，用户按提示粘贴会得到错误内容。
- 二次复核：openOriginal 没有任何 pasteboard 写入；shareTopic 是另一个显式动作，没有前置复制。不是隐藏兜底或 by design。
- 真实方法复现：用 actualPage 执行 openOriginal，让 startAbility reject；输出 `copies=0`、`toasts=["无法打开浏览器，已复制链接"]`。同步错误分支同样没有写入。
- 最小修复：改为准确的失败提示。主 agent 已决定采用该方案，不增加自动复制兜底。
- 回归：异步 reject 与同步 throw 都只报告打开失败；成功调用不额外复制。

### D3：发现设置写队列未结束即可离页，返回发现会读到较早值（P2，待主 agent 最终归类）

- 定位：`DeepReadDiscoverySettingsPage.ets:104–129,273,293,306,317`；`PreferencesStorage.ets:32–35`；`BoardPage.ets:530–555`。
- 触发：连续修改即时保存设置（例如关键词 A→AI），首写正在 await flush，第二个值在 writeQueue 等待；立即经 Header、系统返回或 Dock 返回发现。
- 调用链：onChange 逐次 persistPreference → 第一写 flush 延迟、第二写排队 → 默认 Header/Dock 不等待 saving → Board 返回时读取 A → 第二写随后保存 AI，但没有通知 Board 重新投影。
- 影响：存储最终正确，但当前发现页继续按旧关注词或开关显示；再次离开并返回才应用最新值，违背页面“返回发现页后应用”的说明。
- 二次复核：writeQueue 确实消除逆序写入，问题不是逆序；独立页面没有宿主页的 enabled(!saving)，也没有离页前等待。单个 put 的内存及时可见不能消除连续编辑时后续值尚未 put 的路径。
- 真实方法复现：提取 persistPreference，用与 PreferencesStorage 相同的 put 先可见、flush 后完成顺序；依次提交 A/AI，保持首个 flush pending。立即返回时持久内存值=A、pendingWrites=2；完成队列后值=AI、pendingWrites=0。页面没有完成通知可触发已经返回的 Board 重读。
- 最小修复建议：离页前等待现有 writeQueue，或写队列完成后通知当前可见发现重读。只在这两个 owner 间修复，避免扩展成通用跨页面保存框架。
- 回归：首写 flush 被暂停、后写排队后立即返回，发现最终必须按最新值投影；失败必须可见；离页后不得把错误写到已销毁组件。
- 未验证：没有使用模拟器人为制造慢 flush。Node 已取得真实方法的可观测顺序，但用户设备上的时序频率尚未测量。

## 排除与设计核对

- 固定模型失效不会偷偷退到 auto：DeepReadModelSelection 和 resolveDeepReadRuntimeCore 精确读取 provider/model；auto 跟随当前选择是设计。Legacy RunConfig 读取独立 ai_* 键不是当前文本 runtime 的 owner，未据此报告错误服务商。
- 热榜翻译保持原始标题、URL 和聚合身份；筛选在聚合后应用，并在取前 10 前应用关注顺序，相关已有回归与当前代码相符。Top 10 / 各来源前 12 是呈现设计。
- 原始缓存保留禁用来源是设计，投影始终按最新 enabledIds 过滤；失败保留旧条目并标 stale，也不应误报为“未清空过期数据”。
- RSS 解析器明确只处理官方 RSS item/title/link 子集；没有提出通用 Atom/HTML parser 或任意结构容错。
- 搜索凭据草稿仅保存在 AppStorage 进程内，路由仅传 serviceId；返回后“保存并应用”是明确设计。阅读字号/模型选择草稿不应提前应用到文章。
- Root 的三个页保持挂载、隐藏页 hitTest None、tab animation 只影响呈现，是有意保留滚动与草稿的设计。
- Search 首选服务为空时保存归一到索引 0，与 search prefs 的现有模型一致，未将其扩大为新 schema 改造。
- 模型 providerOverwrite 使用有效 provider 的认证；固定模型解析没有仅按父 provider key 判断可用性的误报。
- 未将概率性的实体聚合错误、任意损坏缓存、假想恶意 JSON、额外兼容分支或备用 Provider 视为可直接修复问题。

## 验证边界

本分工完成源码调用链核对以及 D1/D2/D3 的生产非 UI 方法 Node 复现。未编辑、未全量测试、未 SDK 构建、未设备操作。基线全量与 SDK 构建由主 agent 统一负责；ArkUI 行为仍需要主 agent 的集成/UI 验证。
