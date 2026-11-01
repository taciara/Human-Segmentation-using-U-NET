import UIKit
import CoreGraphics

enum PolaroidExporter {
    static func wrap(_ inner: UIImage) -> UIImage {
        let pad: CGFloat = 36
        let innerW = inner.size.width
        let innerH = inner.size.height

        let logo = UIImage(contentsOfFile: Bundle.main.path(forResource: "logo_fanta", ofType: "png") ?? "")
        let lw = innerW * 0.58
        let lh: CGFloat
        if let logo = logo {
            lh = logo.size.height * lw / max(1, logo.size.width)
        } else {
            lh = 100
        }
        let overlap = lh * 0.32
        let footer = lh - overlap + pad
        let cardW = innerW + pad * 2
        let cardH = pad + innerH + footer

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: cardW, height: cardH))
        return renderer.image { ctx in
            let cgCtx = ctx.cgContext
            // Paper background
            UIColor(red: 230/255, green: 223/255, blue: 210/255, alpha: 1).setFill()
            cgCtx.fill(CGRect(x: 0, y: 0, width: cardW, height: cardH))

            // Inner photo
            inner.draw(at: CGPoint(x: pad, y: pad))

            // Logo
            if let logo = logo {
                let lx = (cardW - lw) / 2
                let ly = pad + innerH - overlap
                logo.draw(in: CGRect(x: lx, y: ly, width: lw, height: lh))
            }
        }
    }

    private static func loadBundleImage(_ name: String, ext: String, subdir: String) -> UIImage? {
        guard let url = Bundle.main.url(forResource: name, withExtension: ext, subdirectory: subdir) else { return nil }
        return UIImage(contentsOfFile: url.path)
    }
}
