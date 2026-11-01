import SwiftUI
import CoreImage

// MARK: - Font helpers

private extension Font {
    static func fantaPop(_ size: CGFloat) -> Font {
        .custom("FantaPop-Condensed", size: size)
    }
    static func fantaBold(_ size: CGFloat) -> Font {
        .custom("FantaSans-Bold", size: size)
    }
    static func fantaRegular(_ size: CGFloat) -> Font {
        .custom("FantaSans-Regular", size: size)
    }
}

// MARK: - Sizing constants (iPad portrait 768×1024)

private enum Layout {
    static var sw: CGFloat { UIScreen.main.bounds.width }
    static var sh: CGFloat { UIScreen.main.bounds.height }

    // Logo
    static var logoH: CGFloat  { sh * 0.10 }   // ~102pt

    // Polaroid photo area: 65% of screen height, 3:4 ratio
    static var photoH: CGFloat { sh * 0.62 }
    static var photoW: CGFloat { photoH * 3 / 4 }

    // Polaroid card (photo + 10pt border all sides + 72pt footer)
    static var cardW: CGFloat  { photoW + 20 }
    static var cardH: CGFloat  { photoH + 10 + 72 }  // top border + footer

    // COMO FUNCIONA panel
    static var panelH: CGFloat { 90 }
}

// MARK: - App root

struct ContentView: View {
    @StateObject private var session = CameraSession()
    @State private var screen: AppScreen = .splash
    @State private var personBw = true
    @State private var countdown: Int? = nil
    @State private var capturedImage: UIImage? = nil
    @State private var polaroidImage: UIImage? = nil
    @State private var qrImage: UIImage? = nil
    @State private var shareUrl: String? = nil
    @State private var isProcessing = false
    @State private var adminTaps = 0
    @State private var adminTapAt: Date = .distantPast
    @State private var showAdminPin = false
    @State private var showAdminMenu = false
    @State private var adminPinInput = ""

    private let previewHolder = CameraPreviewViewHolder()

    private let fantaOrange = Color(red: 1,    green: 0.416, blue: 0)
    private let fantaBlue   = Color(red: 0.18, green: 0.35,  blue: 0.82)
    private let paperColor  = Color(red: 230/255, green: 223/255, blue: 210/255)

    enum AppScreen { case splash, capture, result, ready }

    var body: some View {
        ZStack {
            appBackground
            switch screen {
            case .splash:  splashScreen
            case .capture: captureScreen
            case .result:  resultScreen
            case .ready:   readyScreen
            }
        }
        .ignoresSafeArea()
        .onAppear {
            session.onComposedFrame = { [holder = previewHolder] ci in
                holder.metalView.update(ci)
            }
            session.personBw = personBw
            session.start()
        }
        .onChange(of: session.hasLiveFeed) { has in
            if has && screen == .splash { screen = .capture }
        }
        .alert("Configuração", isPresented: $showAdminPin) {
            SecureField("Senha", text: $adminPinInput)
            Button("Entrar") {
                if adminPinInput == "1425" { showAdminMenu = true }
                adminPinInput = ""
            }
            Button("Cancelar", role: .cancel) { adminPinInput = "" }
        }
        .confirmationDialog("Configuração", isPresented: $showAdminMenu, titleVisibility: .visible) {
            Button("Alternar câmera") { session.toggleCamera() }
            Button("Cancelar", role: .cancel) {}
        }
    }

    // MARK: - Background (bg_orange fills screen; bg_2 overlaid at top)

    @ViewBuilder
    private var appBackground: some View {
        ZStack(alignment: .top) {
            // Base: orange fills entire screen
            if let url = Bundle.main.url(forResource: "bg_orange", withExtension: "png"),
               let img = UIImage(contentsOfFile: url.path) {
                Image(uiImage: img).resizable().scaledToFill()
                    .frame(width: Layout.sw, height: Layout.sh)
                    .clipped()
            } else {
                fantaOrange.frame(width: Layout.sw, height: Layout.sh)
            }

            // Overlay: bg_2 anchored to top, fills width
            if let url = Bundle.main.url(forResource: "bg_2", withExtension: "png"),
               let img = UIImage(contentsOfFile: url.path) {
                Image(uiImage: img).resizable().scaledToFit()
                    .frame(width: Layout.sw)
            }
        }
    }

    @ViewBuilder
    private func bgLayer(_ name: String) -> some View {
        if let url = Bundle.main.url(forResource: name, withExtension: "png"),
           let img = UIImage(contentsOfFile: url.path) {
            Image(uiImage: img).resizable().scaledToFill()
                .frame(width: Layout.sw, height: Layout.sh)
                .clipped()
        } else {
            fantaOrange
        }
    }

    // MARK: - Logo

    @ViewBuilder
    private var logoView: some View {
        if let url = Bundle.main.url(forResource: "logo_fanta", withExtension: "png"),
           let img = UIImage(contentsOfFile: url.path) {
            Image(uiImage: img).resizable().scaledToFit()
                .frame(height: Layout.logoH)
        } else {
            Text("FANTA").font(.fantaPop(36)).foregroundColor(fantaOrange)
                .frame(height: Layout.logoH)
        }
    }

    // MARK: - Splash

    private var splashScreen: some View {
        ZStack {
            fantaOrange
            VStack(spacing: 16) {
                logoView.frame(height: Layout.logoH * 1.5)
                Text("Carregando…").font(.fantaRegular(16)).foregroundColor(.white)
            }
        }
    }

    // MARK: - Capture

    private var captureScreen: some View {
        VStack(spacing: 0) {
            logoView
                .contentShape(Rectangle())
                .onTapGesture { handleAdminTap() }
                .padding(.top, 6)

            toggleView
                .padding(.top, 8)

            // Polaroid
            polaroidCard
                .padding(.top, 8)
                .frame(maxWidth: .infinity)

            Spacer(minLength: 6)

            howPanel
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
        }
        .overlay {
            if let n = countdown { countdownBubble(n) }
        }
    }

    // MARK: - Polaroid card (capture)

    private var polaroidCard: some View {
        VStack(spacing: 0) {
            // Photo area
            ZStack {
                Color(white: 0.07)
                LiveCameraView(holder: previewHolder)
                if !session.hasLiveFeed {
                    VStack(spacing: 8) {
                        ProgressView().tint(fantaOrange).scaleEffect(1.4)
                        Text(session.statusText)
                            .font(.system(size: 13))
                            .foregroundColor(.white.opacity(0.85))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 10)
                    }
                }
            }
            .frame(width: Layout.photoW, height: Layout.photoH)
            .clipped()

            // Footer with button
            Button(action: startCountdown) {
                Text("TIRAR FOTO")
                    .font(.fantaPop(20))
                    .foregroundColor(.black)
                    .frame(width: Layout.photoW - 32, height: 48)
                    .background(fantaOrange)
                    .clipShape(Capsule())
            }
            .disabled(countdown != nil)
            .frame(height: 72)
        }
        .padding(10)
        .background(paperColor)
        .frame(width: Layout.cardW)
        .shadow(color: .black.opacity(0.45), radius: 16, y: 6)
    }

    // MARK: - Toggle

    private var toggleView: some View {
        HStack(spacing: 10) {
            HStack(spacing: 4) {
                chipBtn(label: "COLORIDO",      active: !personBw) { personBw = false; session.personBw = false }
                chipBtn(label: "PRETO E BRANCO", active:  personBw) { personBw = true;  session.personBw = true  }
            }
            .padding(4)
            .background(Color.white.opacity(0.20))
            .clipShape(Capsule())

            Button(action: { session.toggleCamera() }) {
                Image(systemName: "arrow.triangle.2.circlepath.camera")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundColor(.white)
                    .frame(width: 40, height: 40)
                    .background(Color.white.opacity(0.20))
                    .clipShape(Circle())
            }
            .disabled(countdown != nil)
        }
    }

    private func chipBtn(label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.fantaPop(13))
                .foregroundColor(active ? .black : .white.opacity(0.85))
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .background(active ? fantaOrange : Color.clear)
                .clipShape(Capsule())
        }
    }

    // MARK: - COMO FUNCIONA panel

    private var howPanel: some View {
        HStack(alignment: .center, spacing: 0) {
            Text("COMO\nFUNCIONA?")
                .font(.fantaPop(20))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .frame(width: 110)

            Rectangle()
                .fill(Color.white.opacity(0.35))
                .frame(width: 1, height: 56)
                .padding(.horizontal, 10)

            VStack(alignment: .leading, spacing: 6) {
                Text("1. POSICIONE-SE NO CENTRO DA TELA")
                Text("2. TOQUE EM TIRAR FOTO")
                Text("3. SORRIA E PREPARE-SE")
            }
            .font(.system(size: 12, weight: .bold))
            .foregroundColor(.white)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: Layout.panelH, maxHeight: Layout.panelH)
        .background(fantaBlue)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Countdown bubble

    private func countdownBubble(_ n: Int) -> some View {
        Text("\(n)")
            .font(.fantaPop(48))
            .foregroundColor(.black)
            .frame(width: 90, height: 90)
            .background(fantaOrange)
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.4), radius: 10)
            .id(n)
            .transition(.scale)
    }

    // MARK: - Result screen

    private var resultScreen: some View {
        VStack(spacing: 0) {
            logoView.padding(.top, 6)

            resultCard
                .padding(.top, 10)
                .frame(maxWidth: .infinity)

            Button(action: { if !isProcessing { screen = .ready } }) {
                Text("COMPARTILHAR")
                    .font(.fantaPop(20))
                    .foregroundColor(.black)
                    .frame(width: 260, height: 52)
                    .background(isProcessing ? Color.gray.opacity(0.4) : fantaOrange)
                    .clipShape(Capsule())
                    .shadow(color: .black.opacity(0.5), radius: 6, x: 0, y: 3)
            }
            .disabled(isProcessing)
            .padding(.top, 14)
            .padding(.bottom, 20)
        }
    }

    private var resultCard: some View {
        // Card + logo overlapping bottom
        let overlapH: CGFloat = 55

        return ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                ZStack {
                    Color(white: 0.07)
                    if let img = capturedImage {
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFill()
                            .frame(width: Layout.photoW, height: Layout.photoH)
                            .clipped()
                    }
                    if isProcessing {
                        ZStack {
                            Color.black.opacity(0.55)
                            VStack(spacing: 10) {
                                ProgressView().tint(fantaOrange).scaleEffect(1.4)
                                Text("Preparando sua foto…")
                                    .font(.system(size: 15))
                                    .foregroundColor(.white)
                            }
                        }
                    }
                    // Retake button
                    VStack {
                        HStack {
                            Spacer()
                            Button(action: resetToCapture) {
                                Image(systemName: "arrow.counterclockwise")
                                    .font(.system(size: 18, weight: .semibold))
                                    .foregroundColor(.white)
                                    .frame(width: 44, height: 44)
                                    .background(Color.black.opacity(0.45))
                                    .clipShape(Circle())
                                    .padding(8)
                            }
                        }
                        Spacer()
                    }
                }
                .frame(width: Layout.photoW, height: Layout.photoH)
                .clipped()
                // Footer space for logo
                Color.clear.frame(height: overlapH)
            }
            .padding(10)
            .background(paperColor)
            .frame(width: Layout.cardW)
            .shadow(color: .black.opacity(0.45), radius: 16, y: 6)

            // Logo overlapping bottom of polaroid
            logoView
                .frame(width: Layout.cardW * 0.65)
                .offset(y: overlapH * 0.5)
                .zIndex(10)
        }
        .padding(.bottom, overlapH * 0.5)
    }

    // MARK: - Ready screen

    private var readyScreen: some View {
        VStack(spacing: 0) {
            logoView.padding(.top, 6)
            Spacer()
            Text("SUA FOTO ESTÁ PRONTA!")
                .font(.fantaPop(28))
                .foregroundColor(fantaOrange)
                .multilineTextAlignment(.center)
            Text("ESCANEIE O QR CODE PARA ACESSAR E COMPARTILHAR SUA FOTO.")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)
                .frame(maxWidth: 340)
                .padding(.top, 8)
            ZStack {
                if let qr = qrImage {
                    Image(uiImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .padding(10)
                } else {
                    ProgressView().tint(fantaOrange)
                }
            }
            .frame(width: 220, height: 220)
            .background(Color.white)
            .cornerRadius(12)
            .padding(.top, 20)
            Button(action: resetToCapture) {
                Text("RECOMEÇAR")
                    .font(.fantaPop(18))
                    .foregroundColor(.black)
                    .frame(width: 200, height: 52)
                    .background(Color.white)
                    .clipShape(Capsule())
            }
            .padding(.top, 24)
            Spacer()
        }
    }

    // MARK: - Actions

    private func handleAdminTap() {
        guard screen == .capture else { return }
        let now = Date()
        if now.timeIntervalSince(adminTapAt) > 2.5 { adminTaps = 0 }
        adminTapAt = now
        adminTaps += 1
        if adminTaps >= 3 { adminTaps = 0; showAdminPin = true }
    }

    private func startCountdown() {
        guard countdown == nil else { return }
        runStep(5)
    }

    private func runStep(_ n: Int) {
        countdown = n
        if n == 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                countdown = nil; capturePhoto()
            }
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { runStep(n - 1) }
        }
    }

    private func capturePhoto() {
        guard let ci = session.lastComposedFrame else { return }
        isProcessing = true
        screen = .result
        Task.detached(priority: .userInitiated) {
            let ctx = CIContext()
            let cgImg = ctx.createCGImage(ci, from: ci.extent)!
            let photo = UIImage(cgImage: cgImg)
            let polaroid = PolaroidExporter.wrap(photo)
            let stamp = DateFormatter().apply { $0.dateFormat = "yyyyMMdd_HHmmss" }.string(from: Date())
            let fileName = "foto_\(stamp).jpg"
            let url = "\(ShareApi.shareOrigin)/p/\(fileName)"
            let qr = QrEncoder.encode(url, size: 280)
            let jpeg = polaroid.jpegData(compressionQuality: 0.93) ?? Data()
            await MainActor.run {
                self.capturedImage = photo
                self.polaroidImage = polaroid
                self.qrImage = qr
                self.shareUrl = url
                self.isProcessing = false
            }
            if !jpeg.isEmpty { try? await ShareApi.uploadPolaroid(jpeg, fileName: fileName) }
        }
    }

    private func resetToCapture() {
        capturedImage = nil; polaroidImage = nil; qrImage = nil
        shareUrl = nil; isProcessing = false; screen = .capture
    }
}

// MARK: - Helpers

extension DateFormatter {
    func apply(_ configure: (DateFormatter) -> Void) -> DateFormatter {
        configure(self); return self
    }
}

final class CameraPreviewViewHolder {
    let metalView = MetalPreviewView()
}

// Wrap MetalPreviewView (MTKView) in a UIView container so AutoLayout controls its size
struct LiveCameraView: UIViewRepresentable {
    let holder: CameraPreviewViewHolder

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        container.isUserInteractionEnabled = false
        let metal = holder.metalView
        metal.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(metal)
        NSLayoutConstraint.activate([
            metal.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            metal.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            metal.topAnchor.constraint(equalTo: container.topAnchor),
            metal.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
