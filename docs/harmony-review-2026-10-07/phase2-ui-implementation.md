# Phase 2：来源披露与发现 UI 修复

本分工仅修改 `DeepReadSourcesPage.ets`、`BoardPage.ets`、`DeepReadDiscoverySettingsPage.ets`，处理 H04/H05/H06/H07；PDF 截断与领域检查配置由另一分工负责。保留原有产品迁移 WIP，未提交、推送或删除无关代码。

## 修复与行为

- **H04 来源正文归属：** 在 Sources 页面增加私有 `sourceBody`，两个呈现分支复用。带 `researchSource` 的 Discovery 文本返回自己的 `content`；Composer 粘贴保留完整 `inputText` 前 40,000 字符；文件、网页、搜索继续展示自己的内容。没有新增领域接口或来源分类状态。
- **H05 首次可见加载：** Board 每次 `onPageShow` 调用原 `loadHotlist(false)`；初始可见挂载由原 `loading` 防并发。移除造成隐藏初始页首次不加载的 `firstShowDone` 判断，保留现有缓存、刷新间隔、取消和活动订阅行为。
- **H06 保存中离页门禁：** 系统返回、Header 返回和 SubpageDock 均使用同一个私有 `canLeave`，只复用现有 `saving`/`pendingWrites`。保存中提示“正在保存设置，请稍候再返回”，完成后再次返回正常放行。写失败仍由现有 `loadSettings` 恢复存储值并显示原错误，恢复完成前 `saving` 同样阻止离页。未增加跨页面通知、自动导航、重试或状态机。宿主 Header 也复用该门禁，显式保存期间不会绕过已有保存状态。
- **H07 原文打开失败：** 同步抛错、异步拒绝的提示均改为“无法打开浏览器，请稍后重试”。成功行为不变，未增加剪贴板兜底。

## 旧行为证据与 red

`/tmp/deepread-harmony-review-20261007/phase2-ui-red.log` 保留初次回归结果。H05 执行实际 BoardPageContent 的生命周期与 `loadHotlist`：隐藏挂载后首次显示仍没有发起读取；H07 执行实际 `openOriginal` 得到虚报复制的 toast。

H04/H06 最初新增方法测试因 `sourceBody`/`canLeave` 缺失而 red，**该结果只表示新回归尚未被实现，不单独作为缺陷成立证据**。日志随后追加可观测旧行为复现：

- H04：执行修改前源文件读取中记录的实际 `SourceSection` Text 表达式。独立 A/B 来源分别是“热点 A 正文”和“热点 B 正文”，实际两个披露结果均为 `热点 A 正文\n热点 B 正文`。临时 probe 文件按已记录的原 Text 表达式恢复，仅用于评估该旧表达式，并非 Git 基线快照。
- H06：执行未改动的真实 `persistPreference`，模拟 PreferencesStorage 的 put 即可见、flush 延迟顺序。A→AI 两次编辑在首 flush pending 时，立即返回读取为 A，`pendingWrites=2`；队列完成为 AI，`pendingWrites=0`。原 Header/Dock 不带门禁、系统没有拦截，故返回时的发现页得到 A。先前 audit-discovery.md 已独立记录同一真实方法顺序。

probe 脚本在 `/tmp/deepread-harmony-review-20261007/phase2-ui-before/old-behavior.cjs`；原 Text 表达式与实际输出均写入 red 日志。

## 验证

`phase2-ui-green.log` 包含两组聚焦检查：

```sh
node --test harmony/deepread/src/test/deepread_sources_ui.test.cjs \
  harmony/entry/src/test/deepread_phase2_ui_regression.test.cjs \
  harmony/entry/src/test/deepread_ios_discovery_navigation.test.cjs \
  harmony/entry/src/test/deepread_library_settings_motion.test.cjs
node --test harmony/deepread/src/test/hotlist_board.test.cjs \
  harmony/entry/src/test/deepread_board_ui.test.cjs
```

第一组 25/25 通过，覆盖各热点正文归属、Composer 预览、隐藏首次显示、初始可见防重、取消后回显、真实浏览器失败、三个离页门禁、慢 flush 队列最新值与写失败恢复。第二组 16/16 通过，验证实际 Board 投影、缓存刷新、取消、热点路由和可见活动订阅。

仅三个修改的 ArkTS 页面经 `harmony/scripts/lint-arkts.sh` 检查通过（No defects，exit 0），日志为 `phase2-ui-lint.log`。分工做了一次有限可维护性检查，三个入口共用门禁、两种来源呈现共用正文选择，状态仍归现有 owner。

本分工未运行完整测试、SDK 构建、签名、安装或模拟器/真机 UX 验收；这些属于主 agent 的阶段与集成交付验证。Node 方法替身不能证明实际 ArkUI 生命周期和设备上的慢 flush 频率。
