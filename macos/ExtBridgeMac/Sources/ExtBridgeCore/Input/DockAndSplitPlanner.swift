import Foundation

/// Grouped app items rendered inside the macOS-style floating Dock:
/// user-pinned favorites on the left and unpinned active desktop apps on the right.
public struct DockPartition: Sendable, Equatable {
    public let pinned: [RemoteAppInfo]
    public let running: [RemoteAppInfo]

    public init(pinned: [RemoteAppInfo], running: [RemoteAppInfo]) {
        self.pinned = pinned
        self.running = running
    }

    public var isEmpty: Bool {
        pinned.isEmpty && running.isEmpty
    }
}

/// Candidate app groups surfaced inside an unassigned or replacing split-screen slot.
public struct SplitCandidateGroups: Sendable, Equatable {
    public let recommended: [RemoteAppInfo]
    public let allMatches: [RemoteAppInfo]

    public init(recommended: [RemoteAppInfo], allMatches: [RemoteAppInfo]) {
        self.recommended = recommended
        self.allMatches = allMatches
    }
}

/// Pure, testable planning logic for macOS Dock item partitioning and split-screen candidate ranking.
public enum DockAndSplitPlanner {
    private static let maxRecommendedCandidates = 8

    /// Partitions Dock items into ordered pinned favorites (with live running metadata merged)
    /// and unpinned running desktop apps (excluding standalone popout apps).
    public static func partitionDockApps(
        remoteApps: [RemoteAppInfo],
        pinnedPackages: [String],
        runningDesktopApps: [RemoteAppInfo],
        poppedOutPackages: Set<String>
    ) -> DockPartition {
        var metadataByPkg: [String: RemoteAppInfo] = [:]
        for app in remoteApps where !app.pkg.isEmpty {
            metadataByPkg[app.pkg] = app
        }
        var runningByPkg: [String: RemoteAppInfo] = [:]
        for app in runningDesktopApps where !app.pkg.isEmpty && !poppedOutPackages.contains(app.pkg) {
            runningByPkg[app.pkg] = app
            if metadataByPkg[app.pkg] == nil {
                metadataByPkg[app.pkg] = app
            }
        }

        var pinned: [RemoteAppInfo] = []
        var pinnedSeen = Set<String>()
        for rawPkg in pinnedPackages {
            let pkg = rawPkg.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !pkg.isEmpty, pinnedSeen.insert(pkg).inserted else { continue }
            var resolved = metadataByPkg[pkg] ?? RemoteAppInfo(pkg: pkg, label: pkg, runningOnExt: false)
            if let live = runningByPkg[pkg] {
                resolved.runningOnExt = true
                if live.taskId > 0 { resolved.taskId = live.taskId }
            } else if poppedOutPackages.contains(pkg) {
                resolved.runningOnExt = false
            }
            pinned.append(resolved)
        }

        var running: [RemoteAppInfo] = []
        var runningSeen = Set<String>()
        for app in runningDesktopApps {
            let pkg = app.pkg.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !pkg.isEmpty,
                  !pinnedSeen.contains(pkg),
                  !poppedOutPackages.contains(pkg),
                  runningSeen.insert(pkg).inserted else {
                continue
            }
            running.append(app)
        }

        return DockPartition(pinned: pinned, running: running)
    }

    /// Computes quick-pick recommended candidates (running & pinned apps not already occupying
    /// another split slot) alongside fuzzy-filtered installed apps.
    public static func splitCandidateGroups(
        remoteApps: [RemoteAppInfo],
        pinnedPackages: [String],
        occupiedSlots: [String],
        query: String
    ) -> SplitCandidateGroups {
        let occupiedSet = Set(
            occupiedSlots
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        )
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let pinnedRank = Dictionary(
            uniqueKeysWithValues: pinnedPackages.enumerated().compactMap { idx, raw -> (String, Int)? in
                let pkg = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                return pkg.isEmpty ? nil : (pkg, idx)
            }
        )

        guard trimmedQuery.isEmpty else {
            let filtered = AppFuzzyMatcher.filter(remoteApps, query: trimmedQuery)
            let prioritized = filtered.sorted { lhs, rhs in
                let lhsOccupied = occupiedSet.contains(lhs.pkg)
                let rhsOccupied = occupiedSet.contains(rhs.pkg)
                if lhsOccupied != rhsOccupied { return !lhsOccupied }
                return false
            }
            return SplitCandidateGroups(recommended: [], allMatches: prioritized)
        }

        var recommended: [RemoteAppInfo] = []
        var recSeen = Set<String>()

        // 1. Running desktop apps not yet assigned to any split slot
        for app in remoteApps where app.runningOnExt && !occupiedSet.contains(app.pkg) {
            if recSeen.insert(app.pkg).inserted {
                recommended.append(app)
            }
        }
        // 2. Pinned Dock favorites not yet assigned
        let pinnedCandidates = remoteApps
            .filter { pinnedRank[$0.pkg] != nil && !occupiedSet.contains($0.pkg) && !recSeen.contains($0.pkg) }
            .sorted { (pinnedRank[$0.pkg] ?? Int.max) < (pinnedRank[$1.pkg] ?? Int.max) }
        for app in pinnedCandidates {
            if recSeen.insert(app.pkg).inserted {
                recommended.append(app)
            }
        }
        if recommended.count > maxRecommendedCandidates {
            recommended = Array(recommended.prefix(maxRecommendedCandidates))
        }

        let orderedAll = remoteApps.sorted { lhs, rhs in
            let lhsOccupied = occupiedSet.contains(lhs.pkg)
            let rhsOccupied = occupiedSet.contains(rhs.pkg)
            if lhsOccupied != rhsOccupied { return !lhsOccupied }
            if lhs.runningOnExt != rhs.runningOnExt { return lhs.runningOnExt }
            let lhsPinned = pinnedRank[lhs.pkg] != nil
            let rhsPinned = pinnedRank[rhs.pkg] != nil
            if lhsPinned != rhsPinned { return lhsPinned }
            return lhs.displayLabel.localizedCaseInsensitiveCompare(rhs.displayLabel) == .orderedAscending
        }

        return SplitCandidateGroups(recommended: recommended, allMatches: orderedAll)
    }
}
