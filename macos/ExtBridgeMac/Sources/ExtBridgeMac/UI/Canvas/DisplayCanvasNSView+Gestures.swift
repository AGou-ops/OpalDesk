import AppKit
import ExtBridgeCore

// MARK: - Trackpad Pinch-to-Zoom & Three-Finger Navigation Gestures

extension DisplayCanvasNSView {
    private enum GestureMetrics {
        static let pinchBaseHalfSpan: CGFloat = 140
        static let pinchMinScale: CGFloat = 0.25
        static let pinchMaxScale: CGFloat = 4.0
        static let threeFingerCount: Int = 3
        static let threeFingerSwipeThreshold: CGFloat = 0.14
        static let threeFingerCooldownNs: UInt64 = 450_000_000
    }

    // MARK: - Pinch-to-Zoom -> Synthetic 2-Finger Android Touch

    public override func magnify(with event: NSEvent) {
        let pt = mapEventPoint(event)
        switch event.phase {
        case .began:
            isPinching = true
            pinchCenter = pt
            pinchScale = 1.0
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            callbacks.onTouch(InputTouchPayload(action: .down, actionIndex: 0, pointers: [p1]))
            callbacks.onTouch(InputTouchPayload(action: .pointerDown, actionIndex: 1, pointers: [p1, p2]))

        case .changed:
            guard isPinching else { return }
            pinchScale = min(max(pinchScale * (1.0 + event.magnification), GestureMetrics.pinchMinScale), GestureMetrics.pinchMaxScale)
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            callbacks.onTouch(InputTouchPayload(action: .move, actionIndex: 0, pointers: [p1, p2]))

        case .ended:
            guard isPinching else { return }
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            callbacks.onTouch(InputTouchPayload(action: .pointerUp, actionIndex: 1, pointers: [p1, p2]))
            callbacks.onTouch(InputTouchPayload(action: .up, actionIndex: 0, pointers: [p1]))
            isPinching = false

        case .cancelled:
            guard isPinching else { return }
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            callbacks.onTouch(InputTouchPayload(action: .cancel, actionIndex: 0, pointers: [p1, p2]))
            isPinching = false

        default:
            break
        }
    }

    private func pinchPointers(center: CGPoint, scale: CGFloat) -> (TouchPointer, TouchPointer) {
        let halfSpan = GestureMetrics.pinchBaseHalfSpan * scale
        let maxX = CGFloat(max(config.videoWidth - 1, 1))
        let maxY = CGFloat(max(config.videoHeight - 1, 1))
        let x1 = min(max(center.x - halfSpan, 0), maxX)
        let x2 = min(max(center.x + halfSpan, 0), maxX)
        let y = min(max(center.y, 0), maxY)
        return (
            TouchPointer(pointerId: 1, x: Float(x1), y: Float(y), pressure: 1.0),
            TouchPointer(pointerId: 2, x: Float(x2), y: Float(y), pressure: 1.0)
        )
    }

    // MARK: - Three-Finger Trackpad Swipes

    public override func touchesBegan(with event: NSEvent) {
        super.touchesBegan(with: event)
        let active = event.touches(matching: .touching, in: self)
        if active.count == GestureMetrics.threeFingerCount {
            threeFingerStartCentroid = centroid(of: active)
            threeFingerDidTrigger = false
        } else {
            threeFingerStartCentroid = nil
            threeFingerDidTrigger = false
        }
    }

    public override func touchesMoved(with event: NSEvent) {
        super.touchesMoved(with: event)
        let active = event.touches(matching: .touching, in: self)
        guard active.count == GestureMetrics.threeFingerCount else {
            threeFingerStartCentroid = nil
            return
        }
        let current = centroid(of: active)
        guard let start = threeFingerStartCentroid else {
            threeFingerStartCentroid = current
            threeFingerDidTrigger = false
            return
        }
        guard !threeFingerDidTrigger else { return }

        let dx = current.x - start.x
        let dy = current.y - start.y
        if abs(dx) >= GestureMetrics.threeFingerSwipeThreshold || abs(dy) >= GestureMetrics.threeFingerSwipeThreshold {
            threeFingerDidTrigger = true
            let direction: ThreeFingerSwipeDirection
            if abs(dx) >= abs(dy) {
                direction = dx > 0 ? .right : .left
            } else {
                direction = dy > 0 ? .up : .down
            }
            emitThreeFingerSwipe(direction)
        }
    }

    public override func touchesEnded(with event: NSEvent) {
        super.touchesEnded(with: event)
        if event.touches(matching: .touching, in: self).count < GestureMetrics.threeFingerCount {
            threeFingerStartCentroid = nil
            threeFingerDidTrigger = false
        }
    }

    public override func touchesCancelled(with event: NSEvent) {
        super.touchesCancelled(with: event)
        threeFingerStartCentroid = nil
        threeFingerDidTrigger = false
    }

    public override func swipe(with event: NSEvent) {
        if event.deltaX > 0 {
            emitThreeFingerSwipe(.right)
        } else if event.deltaX < 0 {
            emitThreeFingerSwipe(.left)
        } else if event.deltaY > 0 {
            emitThreeFingerSwipe(.up)
        } else if event.deltaY < 0 {
            emitThreeFingerSwipe(.down)
        }
    }

    private func centroid(of touches: Set<NSTouch>) -> CGPoint {
        guard !touches.isEmpty else { return .zero }
        var sumX: CGFloat = 0
        var sumY: CGFloat = 0
        for touch in touches {
            sumX += touch.normalizedPosition.x
            sumY += touch.normalizedPosition.y
        }
        let count = CGFloat(touches.count)
        return CGPoint(x: sumX / count, y: sumY / count)
    }

    private func emitThreeFingerSwipe(_ direction: ThreeFingerSwipeDirection) {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        if nowNs >= lastThreeFingerTriggerNs && (nowNs - lastThreeFingerTriggerNs) < GestureMetrics.threeFingerCooldownNs {
            return
        }
        lastThreeFingerTriggerNs = nowNs
        callbacks.onThreeFingerSwipe?(direction)
    }
}
