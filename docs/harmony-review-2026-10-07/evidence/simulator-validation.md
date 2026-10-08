# 模拟器验证记录

设备：Amber DeepRead QA；HarmonyOS 6.1.1 / API 24；HDC 目标 `127.0.0.1:5555`；bundle `app.amber.deepread.reader`。任务开始全部模拟器 stopped，主 agent 只启动该模拟器。

最终构建后执行：

```sh
hdc -t 127.0.0.1:5555 install -r harmony/entry/build/default/outputs/default/entry-default-signed.hap
hdc -t 127.0.0.1:5555 shell aa force-stop app.amber.deepread.reader
hdc -t 127.0.0.1:5555 shell aa start -a EntryAbility -b app.amber.deepread.reader
```

实际输出：`install bundle successfully`、`force stop process successfully`、`start ability successfully`。

发现页冷启动已显示 GitHub 聚合热点：[最终截图](final-discovery.jpeg)。开始时空榜截图见 [before.jpeg](before.jpeg)。

设置页关注词从空值改为 `Review20261007`，关闭键盘、返回并重新进入，dumpLayout 确认已读取该值：[保存后](phase2-persisted.json)。随后清空、返回、重进确认空值：[恢复后](phase2-restored-final.json)。这个现场检查证明保存后重进读取，不替代 Node deferred-flush 测试中被阻止离页的竞态验收。

自定义模板使用未保存草稿 `<style>/* {{content}} */</style>>`，预览渲染后真实 ArkWeb 显示导语、关键判断与时间轴等正文：[预览截图](phase3-css-preview-settled.jpeg)。末尾 `>` 为 HDC 输入工具产生的可见字符；style 内容及 content 只处于 CSS 注释的条件成立。预览完成后取消编辑，在系统确认框选择“放弃并返回”，没有保存或选用模板。

实际模板预览使用 Phase 3 首次已构建产物；此后唯一生产改动是共享 Markdown 扫描恢复点的 review 修正。最终产物再次覆盖安装与启动，发现截图来自最终产物。没有把 Node renderer 检查描述为实际原生链接点击。

取证后强制停止应用，并执行：

```sh
bash harmony/scripts/devecocli.sh emulator stop 'Amber DeepRead QA'
```

再次查询全部模拟器 stopped。用户其他模拟器与数据保持原状态。未进行真实 Provider 调用、原生 PDF 引擎验收、真机后台调度或系统分享验收。
