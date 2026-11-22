import Testing
@testable import OpalDeskCore

@Suite("Dock Partitioning & Split Candidate App Planning")
struct DockAndSplitPlannerTests {

    private func app(_ pkg: String, _ label: String, running: Bool = false, taskId: Int = -1) -> RemoteAppInfo {
        RemoteAppInfo(pkg: pkg, cls: "", label: label, runningOnExt: running, taskId: taskId)
    }

    @Test("Dock partition keeps pinned apps in order, merges live state, and excludes popped-out packages")
    func dockPartitionOrderingAndFiltering() {
        let bili = app("tv.danmaku.bili", "哔哩哔哩", running: true, taskId: 12)
        let wechat = app("com.tencent.mm", "微信", running: false)
        let douyin = app("com.ss.android.ugc.aweme", "抖音", running: true, taskId: 15)
        let notes = app("com.miui.notes", "笔记", running: true, taskId: 18)

        let partition = DockAndSplitPlanner.partitionDockApps(
            remoteApps: [bili, wechat, douyin, notes],
            pinnedPackages: ["com.tencent.mm", "tv.danmaku.bili", "com.tencent.mm"],
            runningDesktopApps: [bili, douyin, notes],
            poppedOutPackages: ["com.ss.android.ugc.aweme"]
        )

        #expect(partition.pinned.map(\.pkg) == ["com.tencent.mm", "tv.danmaku.bili"])
        #expect(partition.pinned[0].runningOnExt == false)
        #expect(partition.pinned[1].runningOnExt == true)
        #expect(partition.pinned[1].taskId == 12)

        // Popped-out Douyin and pinned Bili are excluded from the unpinned running group
        #expect(partition.running.map(\.pkg) == ["com.miui.notes"])
    }

    @Test("Split candidate groups prioritize running and pinned apps while excluding other occupied slots")
    func splitCandidateGroupsRecommendations() {
        let bili = app("tv.danmaku.bili", "哔哩哔哩", running: true)
        let wechat = app("com.tencent.mm", "微信", running: false)
        let quark = app("com.quark.browser", "夸克", running: true)
        let pdd = app("com.xunmeng.pinduoduo", "拼多多", running: false)

        // Slot 0 already holds Bili; Slot 1 & 2 candidates should exclude Bili and recommend Quark (running) + WeChat (pinned)
        let groups = DockAndSplitPlanner.splitCandidateGroups(
            remoteApps: [pdd, wechat, quark, bili],
            pinnedPackages: ["com.tencent.mm"],
            occupiedSlots: ["tv.danmaku.bili"],
            query: ""
        )

        #expect(groups.recommended.map(\.pkg) == ["com.quark.browser", "com.tencent.mm"])
        #expect(!groups.recommended.contains(where: { $0.pkg == "tv.danmaku.bili" }))
        // Unoccupied apps appear before already-occupied slot apps in full grid
        #expect(groups.allMatches.last?.pkg == "tv.danmaku.bili")

        // Searching filters cleanly while still ranking unoccupied matches first
        let searched = DockAndSplitPlanner.splitCandidateGroups(
            remoteApps: [pdd, wechat, quark, bili],
            pinnedPackages: ["com.tencent.mm"],
            occupiedSlots: ["tv.danmaku.bili"],
            query: "微信"
        )
        #expect(searched.recommended.isEmpty)
        #expect(searched.allMatches.map(\.pkg) == ["com.tencent.mm"])
    }
}
