import MetalKit
import CoreImage
import SwiftUI

// Metal-backed view for zero-copy CoreImage display
final class MetalPreviewView: MTKView {
    private let commandQueue: MTLCommandQueue
    private let ciContext: CIContext
    private(set) var currentCI: CIImage?

    override var intrinsicContentSize: CGSize { .zero }

    init() {
        let device = MTLCreateSystemDefaultDevice()!
        commandQueue = device.makeCommandQueue()!
        ciContext = CIContext(mtlCommandQueue: commandQueue, options: [
            .workingColorSpace: NSNull(),
            .outputColorSpace: NSNull()
        ])
        super.init(frame: .zero, device: device)
        framebufferOnly = false
        isPaused = false
        enableSetNeedsDisplay = false
        colorPixelFormat = .bgra8Unorm
        clearColor = MTLClearColorMake(0, 0, 0, 1)
        setContentHuggingPriority(.defaultLow, for: .horizontal)
        setContentHuggingPriority(.defaultLow, for: .vertical)
        setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        delegate = self
    }

    required init(coder: NSCoder) { fatalError() }

    func update(_ image: CIImage) {
        currentCI = image
        // Pin drawableSize to the CI image dimensions so CAMetalLayer
        // scales the texture to fill the view (avoids mismatched-size artefacts).
        let s = image.extent.size
        if drawableSize != s { drawableSize = s }
    }
}

extension MetalPreviewView: MTKViewDelegate {
    func draw(in view: MTKView) {
        guard let ci = currentCI,
              let drawable = currentDrawable,
              let buffer = commandQueue.makeCommandBuffer() else { return }

        guard ci.extent.width > 0, ci.extent.height > 0 else { return }

        // drawableSize is pinned to ci.extent.size in update().
        // Render the full CI image into the (same-size) drawable texture;
        // CAMetalLayer scales the texture to fill the MTKView on screen.
        ciContext.render(ci,
                         to: drawable.texture,
                         commandBuffer: buffer,
                         bounds: ci.extent,
                         colorSpace: CGColorSpaceCreateDeviceRGB())
        buffer.present(drawable)
        buffer.commit()
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}
}

