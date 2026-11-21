import ExtBridgeCore
import SwiftUI

/// Secure TOFU pairing dialog displaying the Android host's SHA-256 certificate fingerprint
/// alongside a 6-digit one-time PIN input field.
public struct PairingSheetView: View {
    public let context: PairingPromptContext
    public let onSubmit: (String) -> Void
    public let onCancel: () -> Void

    @State private var pinCode: String = ""
    @FocusState private var isPinFocused: Bool

    public init(
        context: PairingPromptContext,
        onSubmit: @escaping (String) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.context = context
        self.onSubmit = onSubmit
        self.onCancel = onCancel
    }

    private var pinLength: Int { DisplayPresets.pairingPinLength }

    public var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("安全无线配对")
                        .font(.title3.weight(.semibold))
                    Text("\(context.displayName)\(context.model.isEmpty ? "" : " · \(context.model)")")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }

            // Fingerprint verification card (TOFU)
            VStack(alignment: .leading, spacing: 8) {
                Label("请核对手机端显示的 TLS 证书短指纹", systemImage: "checkmark.seal")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                HStack {
                    Text(context.shortFingerprint)
                        .font(.system(size: 16, weight: .semibold, design: .monospaced))
                        .textSelection(.enabled)
                    Spacer()
                    StatusBadgeView(
                        text: "SHA-256",
                        color: .accentColor,
                        font: .caption2.weight(.medium),
                        horizontalPadding: 6
                    )
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .extBridgeCardContainer(cornerRadius: 16, borderOpacity: 0.18)

                Text("确认与 Android 端「Mac / 无线大屏」页面上的证书指纹一致后，输入下方 \(pinLength) 位一次性配对码。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // One-time PIN field
            VStack(alignment: .leading, spacing: 6) {
                Text("\(pinLength) 位一次性配对 PIN 码")
                    .font(.subheadline.weight(.medium))

                TextField(String(repeating: "0", count: pinLength), text: $pinCode)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 22, weight: .semibold, design: .monospaced))
                    .multilineTextAlignment(.center)
                    .focused($isPinFocused)
                    .onChange(of: pinCode) { _, newValue in
                        let digitsOnly = String(newValue.filter(\.isNumber).prefix(pinLength))
                        if digitsOnly != newValue {
                            pinCode = digitsOnly
                        }
                    }
                    .onSubmit {
                        if pinCode.count == pinLength && !context.isSubmitting {
                            onSubmit(pinCode)
                        }
                    }
            }

            if let errorMessage = context.errorMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Text(errorMessage)
                        .font(.callout)
                        .foregroundStyle(.red)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }

            HStack {
                Button("取消", role: .cancel) {
                    onCancel()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                if context.isSubmitting {
                    ProgressView()
                        .controlSize(.small)
                        .padding(.trailing, 4)
                }

                Button("信任并连接") {
                    onSubmit(pinCode)
                }
                .buttonStyle(.borderedProminent)
                .disabled(pinCode.count != pinLength || context.isSubmitting)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 410)
        .onAppear {
            isPinFocused = true
        }
    }
}
