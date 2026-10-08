#if DEBUG
import Foundation

/// Debug-only: inserts a finished 观点交锋 reading so the Jupiter view can be checked without a model.
enum DeepReadDebugSeed {
    /// Imports a finished reading exported from a device (`-DeepReadImportTask <path to task JSON>`).
    @MainActor
    static func importTask(from path: String, into store: IOSDeepReadStore) {
        guard let data = FileManager.default.contents(atPath: path),
              let task = try? JSONDecoder().decode(IOSDeepReadTask.self, from: data),
              !store.tasks.contains(where: { $0.title == task.title && $0.status == .succeeded }),
              let created = try? store.createTask(title: task.title, sources: task.sources, templateId: task.templateId),
              store.markRunning(id: created.id) else { return }
        _ = store.complete(id: created.id, markdown: task.resultMarkdown, structuredJSON: task.structuredJSON, missingSections: task.missingSections)
    }

    @MainActor
    static func debate(into store: IOSDeepReadStore) {
        let title = "国足 0-5 巴勒斯坦：一场意外，还是体系崩盘？"
        guard !store.tasks.contains(where: { $0.title == title }) else { return }
        var article = DeepReadTemplateArticle(
            template: DeepReadSynthesisTemplate.debate.rawValue, title: title,
            lede: "10 月 2 日重庆龙兴，中国男足在 CFA 邀请赛上 0 比 5 负于巴勒斯坦，此前七次交手五胜两平的不败纪录就此终结。",
            sources: [
                .init(id: 1, title: "国足溃败！0比5不敌巴勒斯坦", url: "https://news.qq.com/rain/a/20261002A0ALMB00", site: "腾讯新闻"),
                .init(id: 2, title: "60年首败，国足0:5输给巴勒斯坦队", url: "https://www.guancha.cn/TiYu/2026_10_02_903042.shtml", site: "观察者网"),
                .init(id: 3, title: "0-5输巴勒斯坦，国足这场友谊赛暴露了什么", url: "https://m.toutiao.com/article/7692085914240762408/", site: "今日头条"),
                .init(id: 4, title: "现场的中国球迷很气愤", url: "https://www.bilibili.com/video/BV1XpHa6AENy/", site: "哔哩哔哩"),
            ])
        article.debate = .init(
            dispute: "这记 0 比 5，是一场注意力涣散的友谊赛意外，还是防守组织与精神状态集体失守的体系性写照？",
            camps: [
                .init(stance: "pro", label: "体系崩盘论", holders: ["观察者网", "今日头条评论者"],
                      argument: "对手并非传统强队却被打成这样，三种失球指向同一处专注度与组织问题；传球成功率仅 72%，攻防两端是同一条断裂的链条。",
                      quote: "", quoteBy: "", sources: [2, 3]),
                .init(stance: "pro", label: "主场失望派", holders: ["现场球迷"],
                      argument: "主场作战却看不到有效回应，赛后谢场遭遇嘘声，对比赛安排和观赛体验表达强烈不满。",
                      quote: "以后逢年过节，国足别搞比赛了，看得闹心。", quoteBy: "现场观众", sources: [4]),
                .init(stance: "neutral", label: "担责不辩解", holders: ["主帅邵佳一"],
                      argument: "主教练承担主要责任、接受一切批评，只把希望放在下一场；但战术准备与临场应对是否失效仍被追问。",
                      quote: "我接受一切批评，希望打好下一场比赛。", quoteBy: "邵佳一", sources: [1, 2]),
                .init(stance: "con", label: "一场不宜定论", holders: ["部分评论者"],
                      argument: "这终究是友谊赛，两队排名只差一位，历史口径与关键数据都不完整，结果能否等同真实实力仍需辨析。",
                      quote: "", quoteBy: "", sources: [3]),
            ],
            takeaway: "别把一场友谊赛当终审判决，也别当偶然：若只道歉换帅、不触及防守体系与进攻创造力，同类崩盘很难避免。")
        let source = IOSDeepReadSource(kind: .manualText, title: title, content: article.lede)
        guard let task = try? store.createTask(title: title, sources: [source], templateId: DeepReadSynthesisTemplate.debate.rawValue),
              store.markRunning(id: task.id) else { return }
        _ = store.complete(id: task.id, markdown: DeepReadTemplateWriter.markdown(article), structuredJSON: article.encoded())
    }
}
#endif
