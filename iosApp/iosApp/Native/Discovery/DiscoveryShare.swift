import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit
import composeApp

struct DiscoverySharePreview: View {
    let exhibition: Exhibition
    let language: AppLanguage
    var onShare: () -> Void = {}
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var appearance
    @State private var image: UIImage?
    @State private var failed = false
    @State private var revision = 0
    @State private var sharePayload: DiscoverySharePayload?

    var body: some View {
        DiscoveryPage(title: title("미리보기", "Preview")) {
            Button(title("닫기", "Close")) { dismiss() }
                .buttonStyle(DiscoveryButtonStyle())
            if let image {
                // Square by design: the exported story preview is shown edge to edge within its own frame.
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 430)
                    .frame(maxWidth: .infinity)
                    .accessibilityLabel(title("전시 공유 이미지", "Exhibition share image"))
                Button {
                    let web = ExhibitionWebUrlKt.exhibitionWebUrl(nameEn: exhibition.nameEn, nameKo: exhibition.nameKo, id: exhibition.id)
                    onShare()
                    sharePayload = DiscoverySharePayload(image: image, url: URL(string: web))
                } label: {
                    Label(title("공유", "Share"), systemImage: "square.and.arrow.up")
                }
                .buttonStyle(DiscoveryButtonStyle(prominent: true))
            } else if failed {
                DiscoveryMessage(
                    title: title("공유 이미지를 만들 수 없어요.", "Couldn’t create the share image."),
                    actionTitle: title("다시 시도", "Retry"),
                    action: { revision += 1 }
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 270)
            }
        }
        .discoveryStyle(language: language)
        .task(id: revision) { await render() }
        .sheet(item: $sharePayload) { DiscoveryShareActivity(payload: $0) }
    }

    private func title(_ ko: String, _ en: String) -> String { language == .ko ? ko : en }

    @MainActor
    private func render() async {
        failed = false
        guard let today = discoveryToday() else {
            failed = true
            return
        }
        let content = ExhibitionStoryShareContent.companion.from(
            exhibition: exhibition, lang: language, today: today
        )
        var poster: UIImage?
        if let address = content.coverImageUrl, let url = URL(string: address) {
            do {
                let (bytes, _) = try await URLSession.shared.data(from: url)
                guard !Task.isCancelled else { return }
                guard let artwork = UIImage(data: bytes) else {
                    failed = true
                    return
                }
                poster = artwork
            } catch {
                if !Task.isCancelled { failed = true }
                return
            }
        }
        let palette = DiscoverySharePalette(poster: poster, dark: appearance == .dark)
        let output = ImageRenderer(content: DiscoveryStory(
            poster: poster,
            content: content,
            qr: content.webUrl.flatMap { discoveryQR($0, ink: palette.qrInk) },
            palette: palette,
            korean: language == .ko
        )
        .environment(\.dynamicTypeSize, .medium))
        output.scale = 3
        image = output.uiImage
        failed = image == nil
    }
}

private struct DiscoveryStory: View {
    let poster: UIImage?
    let content: ExhibitionStoryShareContent
    let qr: UIImage?
    let palette: DiscoverySharePalette
    let korean: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(content.shareDescriptor).font(GallrStyle.font(12, weight: .semibold, korean: korean))
                Spacer(minLength: 4)
                if let status = content.status {
                    HStack(spacing: 5) {
                        if status.emphasized { Circle().fill(GallrStyle.accent).frame(width: 5, height: 5) }
                        Text(status.text).font(GallrStyle.font(11, weight: .medium, korean: korean))
                    }
                }
            }
            // Square by design: the story image is a share export.
            ZStack {
                Rectangle().fill(palette.ink.opacity(0.07))
                if let poster {
                    Image(uiImage: poster).resizable().scaledToFill()
                } else {
                    Text("gallr").font(GallrStyle.font(28, weight: .semibold))
                }
            }
            .frame(width: 300, height: 300)
            .clipped()
            Spacer().frame(height: 8)
            Text(content.title)
                .font(GallrStyle.font(20, weight: .bold, korean: korean))
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(content.venueLine).font(GallrStyle.font(13, weight: .medium, korean: korean)).lineLimit(2)
            Rectangle().fill(palette.ink.opacity(0.30)).frame(width: 120, height: 1)
                .padding(.vertical, 4)
            Text(content.dateRange).font(GallrStyle.font(13, korean: korean)).lineLimit(1)
            if let line = content.detailLine {
                Text(line).font(GallrStyle.font(11, korean: korean)).lineLimit(2)
            }
            Spacer(minLength: 4)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 3) {
                        ForEach(Array(palette.swatches.enumerated()), id: \.offset) { _, swatch in
                            Rectangle().fill(swatch).frame(width: 9, height: 9)
                        }
                    }
                    Text("gallr").font(GallrStyle.font(20, weight: .bold))
                    Text(content.qrCaption).font(GallrStyle.font(10, korean: korean)).lineLimit(2)
                }
                Spacer()
                if let qr {
                    Image(uiImage: qr).interpolation(.none).resizable().frame(width: 76, height: 76)
                        .background(.white)
                }
            }
        }
        .padding(30)
        .frame(width: 360, height: 640)
        .foregroundStyle(palette.ink)
        .background(palette.paper)
    }
}

private struct DiscoverySharePalette {
    let paper: Color
    let ink: Color
    let qrInk: CIColor
    let swatches: [Color]

    init(poster: UIImage?, dark: Bool) {
        let rgb = Self.average(poster) ?? [0.36, 0.36, 0.36]
        let darkRGB = rgb.map { $0 * 0.2 }
        ink = dark ? Color(white: 0.93) : Color(red: darkRGB[0], green: darkRGB[1], blue: darkRGB[2])
        let paperRGB = rgb.map { dark ? 0.09 + $0 * 0.08 : 0.94 + $0 * 0.06 }
        paper = Color(red: paperRGB[0], green: paperRGB[1], blue: paperRGB[2])
        qrInk = CIColor(red: darkRGB[0], green: darkRGB[1], blue: darkRGB[2])
        swatches = [0.2, 0.4, 0.64, 0.8, 0.94].map { lightness in
            let sample = rgb.map { $0 * 0.24 + lightness * 0.76 }
            return Color(red: sample[0], green: sample[1], blue: sample[2])
        }
    }

    private static func average(_ poster: UIImage?) -> [Double]? {
        guard let cg = poster?.cgImage else { return nil }
        let input = CIImage(cgImage: cg)
        let filter = CIFilter.areaAverage()
        filter.inputImage = input
        filter.extent = input.extent
        guard let pixel = filter.outputImage else { return nil }
        var rgba = [UInt8](repeating: 0, count: 4)
        CIContext().render(pixel, toBitmap: &rgba, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
                           format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        return rgba.prefix(3).map { Double($0) / 255 }
    }
}

private func discoveryQR(_ value: String, ink: CIColor) -> UIImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(value.utf8)
    filter.correctionLevel = "M"
    guard let output = filter.outputImage else { return nil }
    let falseColor = CIFilter.falseColor()
    falseColor.inputImage = output
    falseColor.color0 = ink
    falseColor.color1 = CIColor(red: 1, green: 1, blue: 1)
    guard let colored = falseColor.outputImage else { return nil }
    guard let qr = CIContext().createCGImage(colored, from: colored.extent) else { return nil }
    let quietZone = 4
    let width = qr.width + quietZone * 2
    let height = qr.height + quietZone * 2
    guard let context = CGContext(
        data: nil, width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    ) else { return nil }
    context.setFillColor(UIColor.white.cgColor)
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.draw(qr, in: CGRect(x: quietZone, y: quietZone, width: qr.width, height: qr.height))
    return context.makeImage().map { UIImage(cgImage: $0) }
}

private struct DiscoverySharePayload: Identifiable {
    let image: UIImage
    let url: URL?
    let id = UUID()
}

private struct DiscoveryShareActivity: UIViewControllerRepresentable {
    let payload: DiscoverySharePayload

    func makeUIViewController(context: Context) -> UIActivityViewController {
        var items: [Any] = [payload.image]
        if let url = payload.url { items.append(url) }
        return UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
