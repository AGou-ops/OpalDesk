import OpalDeskCore
import SwiftUI

/// Smart warning banner shown when Android blocks default `INJECT_EVENTS`, offering one-click UHID switch.
struct InjectDeniedSmartBanner: View {
    let onSwitchToUHID: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.shield.fill")
                .font(.system(size: 16))
                .foregroundStyle(.orange)

            VStack(alignment: .leading, spacing: 2) {
                Text("手机系统阻止了默认输入注入（如安全限制 / 未开模拟点击）")
                    .font(.system(size: 12, weight: .semibold))
                Text("切换至 UHID 虚拟键鼠外设可原生绕过限制，获得低延迟完整控屏体验。")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }

            Button(action: onSwitchToUHID) {
                Label("一键切换 UHID 虚拟键鼠", systemImage: "keyboard.badge.ellipsis")
                    .font(.system(size: 11, weight: .semibold))
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .help("暂时隐藏此提示")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.orange.opacity(0.4), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.32), radius: 12, y: 5)
    }
}

/// Bottom-right stack of active and recently completed file transfer progress cards.
struct FileTransferToastStack: View {
    let transfers: [FileTransferProgress]
    let onCancel: (UInt32) -> Void
    let onDismiss: (UInt32) -> Void

    var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            ForEach(transfers) { transfer in
                transferRow(transfer)
            }
        }
    }

    private func transferRow(_ transfer: FileTransferProgress) -> some View {
        HStack(spacing: 10) {
            Image(systemName: transferIcon(for: transfer.status))
                .foregroundStyle(transferColor(for: transfer.status))

            VStack(alignment: .leading, spacing: 3) {
                Text(transfer.fileName)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)

                switch transfer.status {
                case .sending:
                    ProgressView(value: transfer.fractionCompleted)
                        .frame(width: 170)
                case .awaitingAck:
                    Text("等待手机端落盘确认...")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                case .completed(let remotePath):
                    Text(remotePath.isEmpty ? "已发送到手机" : "已保存：\(remotePath)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                case .failed(let reason):
                    Text("失败：\(reason)")
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lineLimit(2)
                case .cancelled:
                    Text("已取消")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if transfer.status == .sending {
                Button {
                    onCancel(transfer.transferId)
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
                .help("取消传输")
            } else {
                Button {
                    onDismiss(transfer.transferId)
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
                .help("关闭提示")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.8)
        )
    }

    private func transferIcon(for status: FileTransferStatus) -> String {
        switch status {
        case .sending, .awaitingAck: return "arrow.up.circle.fill"
        case .completed: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .cancelled: return "slash.circle"
        }
    }

    private func transferColor(for status: FileTransferStatus) -> Color {
        switch status {
        case .sending, .awaitingAck: return .accentColor
        case .completed: return .green
        case .failed: return .red
        case .cancelled: return .secondary
        }
    }
}
