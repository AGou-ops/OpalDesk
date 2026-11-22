import Testing
@testable import OpalDeskCore

@Suite("AppFuzzyMatcher Ranking, Subsequence & Stability")
struct AppFuzzyMatcherTests {

    private func makeApp(
        pkg: String,
        label: String,
        runningOnExt: Bool = false,
        cls: String = ""
    ) -> RemoteAppInfo {
        RemoteAppInfo(pkg: pkg, cls: cls, label: label, runningOnExt: runningOnExt)
    }

    @Test("Blank and whitespace-only queries preserve original array order unchanged")
    func emptyAndWhitespaceQueriesPreserveOrder() {
        let apps = [
            makeApp(pkg: "com.z.app", label: "Zebra", runningOnExt: false),
            makeApp(pkg: "com.a.app", label: "Alpha", runningOnExt: true),
            makeApp(pkg: "com.m.app", label: "Middle", runningOnExt: false)
        ]

        #expect(AppFuzzyMatcher.filter(apps, query: "") == apps)
        #expect(AppFuzzyMatcher.filter(apps, query: "   ") == apps)
        #expect(AppFuzzyMatcher.filter(apps, query: "\t \n ") == apps)

        #expect(AppFuzzyMatcher.score(needle: "", app: apps[0]) == 0)
        #expect(AppFuzzyMatcher.score(needle: "   \n", app: apps[0]) == 0)
    }

    @Test("Ranks exact, prefix, label-contains, package-contains, label-subsequence, and package-subsequence strictly")
    func strictTierOrdering() {
        let exact = makeApp(pkg: "com.tier1.one", label: "Ch")
        let prefix = makeApp(pkg: "com.tier2.two", label: "Chrome")
        let labelContains = makeApp(pkg: "com.tier3.three", label: "WeChat")
        let pkgContains = makeApp(pkg: "com.chat.notes", label: "Notes")
        let labelSubseq = makeApp(pkg: "com.tier5.five", label: "Code Hub")
        let pkgSubseq = makeApp(pkg: "com.c.hub", label: "Browser")
        let nonMatch = makeApp(pkg: "org.spotify.music", label: "Spotify")

        let scores = [exact, prefix, labelContains, pkgContains, labelSubseq, pkgSubseq].map {
            AppFuzzyMatcher.score(needle: "ch", app: $0)
        }
        for i in 0..<(scores.count - 1) {
            #expect(scores[i] > scores[i + 1])
        }
        #expect(scores.last ?? 0 > 0)
        #expect(AppFuzzyMatcher.score(needle: "ch", app: nonMatch) == 0)

        // Feed in reverse order to ensure ranking comes purely from score, not input order.
        let input = [nonMatch, pkgSubseq, labelSubseq, pkgContains, labelContains, prefix, exact]
        let ranked = AppFuzzyMatcher.filter(input, query: "ch")
        #expect(ranked.map(\.pkg) == [
            exact.pkg,
            prefix.pkg,
            labelContains.pkg,
            pkgContains.pkg,
            labelSubseq.pkg,
            pkgSubseq.pkg
        ])
    }

    @Test("Matching is case-insensitive and trims surrounding whitespace")
    func caseInsensitiveAndTrimmedQuery() {
        let wechat = makeApp(pkg: "com.tencent.mm", label: "WeChat")
        let apps = [
            makeApp(pkg: "com.google.chrome", label: "Chrome"),
            wechat
        ]

        let variants = ["wechat", "WECHAT", "WeChat", "  wEcHaT \t"]
        let expectedScore = AppFuzzyMatcher.score(needle: "wechat", app: wechat)
        for query in variants {
            #expect(AppFuzzyMatcher.score(needle: query, app: wechat) == expectedScore)
            #expect(AppFuzzyMatcher.filter(apps, query: query) == [wechat])
        }

        // Package name matching is also case-insensitive.
        #expect(AppFuzzyMatcher.filter(apps, query: "TENCENT") == [wechat])
    }

    @Test("Subsequence matching handles order, duplicates, length, and Unicode/CJK characters")
    func subsequenceEdgeCases() {
        #expect(AppFuzzyMatcher.isSubsequence("", of: "anything") == true)
        #expect(AppFuzzyMatcher.isSubsequence("", of: "") == true)
        #expect(AppFuzzyMatcher.isSubsequence("abc", of: "ab") == false)
        #expect(AppFuzzyMatcher.isSubsequence("ba", of: "ab") == false)
        #expect(AppFuzzyMatcher.isSubsequence("aab", of: "ab") == false)
        #expect(AppFuzzyMatcher.isSubsequence("aab", of: "aaxb") == true)
        #expect(AppFuzzyMatcher.isSubsequence("wct", of: "wechat") == true)
        #expect(AppFuzzyMatcher.isSubsequence("微聊", of: "微信聊天") == true)
        #expect(AppFuzzyMatcher.isSubsequence("聊微", of: "微信聊天") == false)
    }

    @Test("Filters out non-matching apps and falls back to package name when label is empty")
    func nonMatchFilteringAndEmptyLabelFallback() {
        let unlabeled = makeApp(pkg: "io.github.lengsukq.opaldesk", label: "")
        let apps = [
            unlabeled,
            makeApp(pkg: "com.android.settings", label: "Settings")
        ]

        #expect(unlabeled.displayLabel == "io.github.lengsukq.opaldesk")
        #expect(AppFuzzyMatcher.filter(apps, query: "opaldesk") == [unlabeled])
        #expect(AppFuzzyMatcher.filter(apps, query: "io.github.lengsukq.opaldesk") == [unlabeled])
        #expect(AppFuzzyMatcher.filter(apps, query: "nonexistent-xyz").isEmpty)
        #expect(AppFuzzyMatcher.filter([], query: "any").isEmpty)
    }

    @Test("Tie-breaking prioritizes runningOnExt then alphabetical label deterministically across permutations")
    func rankingStabilityAndTieBreakers() {
        let runBeta = makeApp(pkg: "com.app.b", label: "Chat Beta", runningOnExt: true)
        let runAlpha = makeApp(pkg: "com.app.a", label: "chat alpha", runningOnExt: true)
        let idleGamma = makeApp(pkg: "com.app.g", label: "Chat Gamma", runningOnExt: false)
        let idleDelta = makeApp(pkg: "com.app.d", label: "Chat Delta", runningOnExt: false)

        let expectedOrder = [runAlpha, runBeta, idleDelta, idleGamma].map(\.pkg)

        let permutations: [[RemoteAppInfo]] = [
            [runBeta, runAlpha, idleGamma, idleDelta],
            [idleGamma, idleDelta, runBeta, runAlpha],
            [idleDelta, runAlpha, idleGamma, runBeta],
            [runAlpha, idleGamma, runBeta, idleDelta]
        ]

        for permutation in permutations {
            let result = AppFuzzyMatcher.filter(permutation, query: "chat")
            #expect(result.map(\.pkg) == expectedOrder)
        }
    }
}
