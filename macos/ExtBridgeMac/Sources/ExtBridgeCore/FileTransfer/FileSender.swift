import Foundation
import os

public enum FileTransferStatus: Sendable, Equatable {
    case sending
    case awaitingAck
    case completed(remotePath: String)
    case failed(reason: String)
    case cancelled
}

public struct FileTransferProgress: Sendable, Equatable, Identifiable {
    public var id: UInt32 { transferId }
    public let transferId: UInt32
    public let fileName: String
    public let totalBytes: Int64
    public var sentBytes: Int64
    public var status: FileTransferStatus

    public var fractionCompleted: Double {
        guard totalBytes > 0 else { return status == .sending ? 0 : 1 }
        return min(max(Double(sentBytes) / Double(totalBytes), 0), 1)
    }

    public init(
        transferId: UInt32,
        fileName: String,
        totalBytes: Int64,
        sentBytes: Int64 = 0,
        status: FileTransferStatus = .sending
    ) {
        self.transferId = transferId
        self.fileName = fileName
        self.totalBytes = totalBytes
        self.sentBytes = sentBytes
        self.status = status
    }
}

public enum FileSenderError: Error, Sendable, LocalizedError {
    case directoryNotSupported(String)
    case unreadableFile(String)

    public var errorDescription: String? {
        switch self {
        case .directoryNotSupported(let name):
            return "暂不支持直接发送文件夹：\(name)"
        case .unreadableFile(let name):
            return "无法读取文件：\(name)"
        }
    }
}

/// Streams local macOS files to Android over ExtCast `0x28 FILE_TRANSFER_CHUNK` in 256 KiB slices
/// without loading full files into memory.
public final class FileSender: Sendable {
    private struct MutableState {
        var nextTransferId: UInt32 = 1
        var activeTransfers: [UInt32: FileTransferProgress] = [:]
        var activeTasks: [UInt32: Task<Void, Never>] = [:]
    }

    private let chunkSize: Int
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init(chunkSize: Int = ExtCastProtocol.defaultFileChunkSize) {
        self.chunkSize = max(chunkSize, 4096)
    }

    /// Starts streaming a local file URL through `sendFrame`, emitting progress snapshots via `onProgress`.
    @discardableResult
    public func sendFile(
        at url: URL,
        sendFrame: @escaping @Sendable (ExtCastFrame) -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) throws -> UInt32 {
        let resourceValues = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
        if resourceValues.isDirectory == true {
            throw FileSenderError.directoryNotSupported(url.lastPathComponent)
        }
        guard FileManager.default.isReadableFile(atPath: url.path) else {
            throw FileSenderError.unreadableFile(url.lastPathComponent)
        }

        let fileName = url.lastPathComponent
        let totalSize = Int64(resourceValues.fileSize ?? 0)

        let transferId: UInt32 = state.withLock { s in
            let id = s.nextTransferId
            s.nextTransferId &+= 1
            if s.nextTransferId == 0 { s.nextTransferId = 1 }
            s.activeTransfers[id] = FileTransferProgress(
                transferId: id,
                fileName: fileName,
                totalBytes: totalSize,
                sentBytes: 0,
                status: .sending
            )
            return id
        }

        let chunkSize = self.chunkSize
        let task = Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            await self.streamFile(
                transferId: transferId,
                url: url,
                fileName: fileName,
                totalSize: totalSize,
                chunkSize: chunkSize,
                sendFrame: sendFrame,
                onProgress: onProgress
            )
        }

        state.withLock { $0.activeTasks[transferId] = task }
        return transferId
    }

    /// Cancels an active transfer and notifies Android with `ABORT`.
    public func cancel(
        transferId: UInt32,
        sendFrame: @escaping @Sendable (ExtCastFrame) -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) {
        let (task, updated): (Task<Void, Never>?, FileTransferProgress?) = state.withLock { s in
            let t = s.activeTasks.removeValue(forKey: transferId)
            guard var progress = s.activeTransfers[transferId] else {
                return (t, nil)
            }
            progress.status = .cancelled
            s.activeTransfers[transferId] = progress
            return (t, progress)
        }
        task?.cancel()
        let abortPayload = FileTransferChunkPayload(transferId: transferId, chunkFlags: .abort)
        sendFrame(.fileTransferChunk(abortPayload))
        if let updated {
            onProgress(updated)
        }
    }

    /// Processes an incoming `0x13 FILE_TRANSFER_ACK` from Android.
    public func handleAck(
        _ ack: FileTransferAckPayload,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) {
        let updated: FileTransferProgress? = state.withLock { s in
            guard var progress = s.activeTransfers[ack.transferId] else { return nil }
            switch ack.status {
            case .ok:
                progress.sentBytes = progress.totalBytes
                progress.status = .completed(remotePath: ack.pathOrError)
            case .error:
                progress.status = .failed(reason: ack.pathOrError)
            }
            s.activeTransfers[ack.transferId] = progress
            s.activeTasks.removeValue(forKey: ack.transferId)
            return progress
        }
        if let updated {
            onProgress(updated)
        }
    }

    private func streamFile(
        transferId: UInt32,
        url: URL,
        fileName: String,
        totalSize: Int64,
        chunkSize: Int,
        sendFrame: @escaping @Sendable (ExtCastFrame) -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) async {
        let startPayload = FileTransferChunkPayload(
            transferId: transferId,
            chunkFlags: .start,
            fileName: fileName,
            totalSize: totalSize
        )
        sendFrame(.fileTransferChunk(startPayload))

        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            markFailed(transferId: transferId, reason: error.localizedDescription, sendFrame: sendFrame, onProgress: onProgress)
            return
        }
        defer { try? handle.close() }

        var sentBytes: Int64 = 0
        do {
            while !Task.isCancelled {
                guard let chunk = try handle.read(upToCount: chunkSize), !chunk.isEmpty else {
                    break
                }
                let dataPayload = FileTransferChunkPayload(
                    transferId: transferId,
                    chunkFlags: .data,
                    data: chunk
                )
                sendFrame(.fileTransferChunk(dataPayload))
                sentBytes += Int64(chunk.count)
                let currentSentBytes = sentBytes

                let snapshot: FileTransferProgress? = state.withLock { s in
                    guard var p = s.activeTransfers[transferId] else { return nil }
                    p.sentBytes = currentSentBytes
                    s.activeTransfers[transferId] = p
                    return p
                }
                if let snapshot {
                    onProgress(snapshot)
                }

                // Gentle yield every chunk so real-time input/ping frames are never starved.
                await Task.yield()
            }
        } catch {
            markFailed(transferId: transferId, reason: error.localizedDescription, sendFrame: sendFrame, onProgress: onProgress)
            return
        }

        if Task.isCancelled {
            return
        }

        let endPayload = FileTransferChunkPayload(transferId: transferId, chunkFlags: .end)
        sendFrame(.fileTransferChunk(endPayload))

        let awaiting: FileTransferProgress? = state.withLock { s in
            s.activeTasks.removeValue(forKey: transferId)
            guard var p = s.activeTransfers[transferId] else { return nil }
            p.sentBytes = totalSize
            p.status = .awaitingAck
            s.activeTransfers[transferId] = p
            return p
        }
        if let awaiting {
            onProgress(awaiting)
        }
    }

    private func markFailed(
        transferId: UInt32,
        reason: String,
        sendFrame: @escaping @Sendable (ExtCastFrame) -> Void,
        onProgress: @escaping @Sendable (FileTransferProgress) -> Void
    ) {
        let abortPayload = FileTransferChunkPayload(transferId: transferId, chunkFlags: .abort)
        sendFrame(.fileTransferChunk(abortPayload))
        let updated: FileTransferProgress? = state.withLock { s in
            s.activeTasks.removeValue(forKey: transferId)
            guard var p = s.activeTransfers[transferId] else { return nil }
            p.status = .failed(reason: reason)
            s.activeTransfers[transferId] = p
            return p
        }
        if let updated {
            onProgress(updated)
        }
    }
}
