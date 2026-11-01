import AVFoundation
import Vision
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

final class CameraSession: NSObject, ObservableObject {
    // MARK: - Published state
    @Published var hasLiveFeed = false
    @Published var statusText = "Iniciando câmera…"

    // MARK: - Render callback (called on processQueue)
    var onComposedFrame: ((CIImage) -> Void)?

    // MARK: - Look toggle
    var personBw: Bool = true {
        didSet { if oldValue != personBw { sceneLock.lock(); cachedScene = nil; sceneLock.unlock() } }
    }

    // MARK: - Capture
    private(set) var lastComposedFrame: CIImage?

    // MARK: - Internals
    private let captureSession = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let processQueue = DispatchQueue(label: "booth.camera", qos: .userInteractive)
    private let switchQueue  = DispatchQueue(label: "booth.switch",  qos: .userInitiated)
    private var currentCameraPosition: AVCaptureDevice.Position = .front

    private let segRequest: VNGeneratePersonSegmentationRequest = {
        let r = VNGeneratePersonSegmentationRequest()
        r.qualityLevel = .balanced
        r.outputPixelFormat = kCVPixelFormatType_OneComponent8
        return r
    }()

    // Scene cache
    private var cachedScene: CIImage?
    private var cachedSceneSize: CGSize = .zero
    private let sceneLock = NSLock()

    // MARK: - Start / Stop

    func start() {
        processQueue.async { [weak self] in self?.configure() }
    }

    func stop() {
        captureSession.stopRunning()
    }

    func toggleCamera() {
        let newPos: AVCaptureDevice.Position = currentCameraPosition == .front ? .back : .front
        switchQueue.async { [weak self] in
            guard let self else { return }
            guard let currentInput = self.captureSession.inputs.first as? AVCaptureDeviceInput else { return }

            let discovery = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInWideAngleCamera, .builtInDualCamera, .builtInTelephotoCamera],
                mediaType: .video, position: newPos)
            guard let newDevice = discovery.devices.first
                    ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: newPos),
                  let newInput = try? AVCaptureDeviceInput(device: newDevice) else { return }

            // Stop → reconfigure → restart (most reliable on older iOS/iPad)
            self.captureSession.stopRunning()

            self.captureSession.beginConfiguration()
            self.captureSession.removeInput(currentInput)
            if self.captureSession.canAddInput(newInput) {
                self.captureSession.addInput(newInput)
                self.currentCameraPosition = newPos
            } else {
                _ = self.captureSession.canAddInput(currentInput) && { self.captureSession.addInput(currentInput); return true }()
                self.captureSession.commitConfiguration()
                self.captureSession.startRunning()
                return
            }
            self.captureSession.commitConfiguration()

            if let conn = self.videoOutput.connection(with: .video) {
                if #available(iOS 17.0, *) {
                    conn.videoRotationAngle = 90
                } else {
                    conn.videoOrientation = .portrait
                }
                if conn.isVideoMirroringSupported {
                    conn.isVideoMirrored = (newPos == .front)
                }
            }

            self.captureSession.startRunning()
            self.sceneLock.lock(); self.cachedScene = nil; self.sceneLock.unlock()
        }
    }

    private func configure() {
        captureSession.beginConfiguration()
        captureSession.sessionPreset = .hd1280x720

        guard let device = bestCamera() else {
            DispatchQueue.main.async { self.statusText = "Câmera não encontrada." }
            captureSession.commitConfiguration()
            return
        }
        currentCameraPosition = device.position

        do {
            let input = try AVCaptureDeviceInput(device: device)
            guard captureSession.canAddInput(input) else { throw NSError(domain: "booth", code: 1) }
            captureSession.addInput(input)
        } catch {
            DispatchQueue.main.async { self.statusText = "Erro câmera: \(error.localizedDescription)" }
            captureSession.commitConfiguration()
            return
        }

        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: processQueue)

        guard captureSession.canAddOutput(videoOutput) else {
            captureSession.commitConfiguration()
            return
        }
        captureSession.addOutput(videoOutput)

        if let conn = videoOutput.connection(with: .video) {
            if #available(iOS 17.0, *) {
                conn.videoRotationAngle = 90
            } else {
                conn.videoOrientation = .portrait
            }
            if conn.isVideoMirroringSupported {
                conn.isVideoMirrored = (device.position == .front)
            }
        }

        captureSession.commitConfiguration()
        captureSession.startRunning()
        DispatchQueue.main.async { self.statusText = "Câmera ativa" }
    }

    private func bestCamera() -> AVCaptureDevice? {
        if let front = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) {
            return front
        }
        return AVCaptureDevice.default(for: .video)
    }

    // MARK: - Frame rotation helper

    /// Ensures the CIImage is in portrait orientation (taller than wide).
    /// Uses explicit matrix transforms to avoid EXIF-orientation coordinate surprises.
    private func toPortrait(_ raw: CIImage) -> CIImage {
        guard raw.extent.width > raw.extent.height else { return raw }
        let W = raw.extent.width   // e.g. 1280
        // Rotate 90° CW in CIImage's Y-up space: (x,y) → (y, W-x)
        // Matrix: a=0, b=-1, c=1, d=0, tx=0, ty=W
        let rotation = CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: W)
        let rotated = raw.transformed(by: rotation)
        // For front camera, mirror horizontally so selfie looks natural
        if currentCameraPosition == .front {
            let H = raw.extent.height   // e.g. 720 (becomes new width after rotation)
            let mirror = CGAffineTransform(scaleX: -1, y: 1)
                .concatenating(CGAffineTransform(translationX: H, y: 0))
            return rotated.transformed(by: mirror)
        }
        return rotated
    }

    // MARK: - Scene building

    private func scene(for size: CGSize) -> CIImage {
        sceneLock.lock()
        defer { sceneLock.unlock() }

        if let cached = cachedScene, cachedSceneSize == size { return cached }

        let bgName = personBw ? "fundo_lockeroom_pb" : "fundo_lockeroom_color"
        var scene: CIImage

        if let url = Bundle.main.url(forResource: bgName, withExtension: "webp"),
           let uiImage = UIImage(contentsOfFile: url.path),
           let ci = CIImage(image: uiImage) {
            scene = fitCover(ci, to: size)
        } else {
            scene = CIImage(color: CIColor(red: 1, green: 0.416, blue: 0))
                .cropped(to: CGRect(origin: .zero, size: size))
        }

        // Overlay ghostface character centered, then crop back to canvas
        if let url = Bundle.main.url(forResource: "personagem", withExtension: "webp"),
           let uiImage = UIImage(contentsOfFile: url.path),
           let ci = CIImage(image: uiImage) {
            let composite = placeCharacter(ci, over: scene, canvasSize: size)
            // Crop to canvas so character doesn't extend extent beyond frame
            let canvas = CGRect(origin: .zero, size: size)
            scene = composite.cropped(to: canvas)
                              .transformed(by: CGAffineTransform(
                                  translationX: -composite.cropped(to: canvas).extent.minX,
                                  y: -composite.cropped(to: canvas).extent.minY))
        }

        cachedScene = scene
        cachedSceneSize = size
        return scene
    }

    private func fitCover(_ image: CIImage, to size: CGSize) -> CIImage {
        let iw = image.extent.width, ih = image.extent.height
        let scale = max(size.width / iw, size.height / ih)
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let x = (scaled.extent.width  - size.width)  / 2
        let y = (scaled.extent.height - size.height) / 2
        return scaled
            .cropped(to: CGRect(x: scaled.extent.minX + x, y: scaled.extent.minY + y,
                                width: size.width, height: size.height))
            .transformed(by: CGAffineTransform(translationX: -(scaled.extent.minX + x),
                                               y: -(scaled.extent.minY + y)))
    }

    private func placeCharacter(_ overlay: CIImage, over base: CIImage, canvasSize: CGSize) -> CIImage {
        let ow = overlay.extent.width, oh = overlay.extent.height
        let scale = (canvasSize.height * 0.98) / oh
        let nw = ow * scale
        let scaled = overlay.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let x = (canvasSize.width - nw) / 2
        let placed = scaled.transformed(by: CGAffineTransform(
            translationX: x - scaled.extent.minX,
            y: -scaled.extent.minY))
        return placed.composited(over: base)
    }

    // MARK: - Compose frame

    private func compose(pixelBuffer: CVPixelBuffer) -> CIImage {
        let rawFrame = CIImage(cvPixelBuffer: pixelBuffer)

        // Rotate to portrait if the hardware didn't rotate the buffer
        let fullFrame = toPortrait(rawFrame)

        // Run segmentation on full portrait frame (better quality)
        let handler = VNImageRequestHandler(ciImage: fullFrame, orientation: .up)
        try? handler.perform([segRequest])

        // Crop camera frame to 3:4 aspect ratio to match the polaroid.
        // Camera is 9:16 (720×1280); polaroid is 3:4. Crop top+bottom equally.
        let fw = fullFrame.extent.width                  // 720
        let targetH = (fw * 4 / 3).rounded()            // 960
        let cropOffsetY = ((fullFrame.extent.height - targetH) / 2).rounded()
        let frame: CIImage = {
            let cropRect = CGRect(x: fullFrame.extent.minX,
                                  y: fullFrame.extent.minY + cropOffsetY,
                                  width: fw,
                                  height: targetH)
            let c = fullFrame.cropped(to: cropRect)
            return c.transformed(by: CGAffineTransform(translationX: -c.extent.minX,
                                                       y: -c.extent.minY))
        }()

        let fh = frame.extent.height   // 960

        guard let maskBuffer = (segRequest.results?.first as? VNPixelBufferObservation)?.pixelBuffer else {
            return frame
        }

        let maskCI = CIImage(cvPixelBuffer: maskBuffer)
        let mw = maskCI.extent.width, mh = maskCI.extent.height

        // The Vision mask was produced from fullFrame (9:16 = 720×1280).
        // Crop the mask to the same 3:4 region and scale to frame size.
        let maskCropOffsetY = cropOffsetY * (mh / fullFrame.extent.height)
        let maskTargetH = targetH * (mh / fullFrame.extent.height)
        let maskCropRect = CGRect(x: 0, y: maskCropOffsetY, width: mw, height: maskTargetH)
        let maskCropped = maskCI.cropped(to: maskCropRect)
                                .transformed(by: CGAffineTransform(translationX: 0,
                                                                   y: -maskCropOffsetY))
        let scaledMask = maskCropped.transformed(by: CGAffineTransform(scaleX: fw / mw,
                                                                        y: fh / maskTargetH))

        // Optionally grayscale person
        let fg: CIImage = personBw
            ? frame.applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 0.0])
            : frame

        // Background scene at 3:4 size
        let bg = scene(for: CGSize(width: fw, height: fh))

        // CIBlendWithMask: mask=1 → foreground (person), mask=0 → background (scene)
        let blended = fg.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: bg,
            kCIInputMaskImageKey: scaledMask
        ])
        // Crop to exact frame size at origin
        let frameRect = CGRect(x: 0, y: 0, width: fw, height: fh)
        let cropped = blended.cropped(to: frameRect)
        return cropped.transformed(by: CGAffineTransform(
            translationX: -cropped.extent.minX,
            y: -cropped.extent.minY))
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension CameraSession: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let composed = compose(pixelBuffer: pixelBuffer)
        lastComposedFrame = composed
        onComposedFrame?(composed)

        if !hasLiveFeed {
            DispatchQueue.main.async { self.hasLiveFeed = true }
        }
    }
}
