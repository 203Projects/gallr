import CoreImage.CIFilterBuiltins
import CoreLocation
import MapKit
import SwiftUI
import UIKit
import composeApp

struct RouteMapPoint {
    let coordinate: CLLocationCoordinate2D
    let title: String
    var coverURL: URL? = nil

    init(point: GeoPoint, title: String, cover: String? = nil) {
        coordinate = CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
        self.title = title
        coverURL = cover.flatMap(URL.init(string:))
    }
}

struct NativeRouteMapPanel: View {
    @Environment(\.gallrKorean) private var korean
    let points: [RouteMapPoint]
    let legs: [EstimatedRouteLeg]
    var origin: GeoPoint? = nil
    @State private var isExpanded = false

    var body: some View {
        if !points.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                NativeRouteMap(points: points, legs: legs, origin: origin, interactive: false)
                    .frame(height: 220)
                    .accessibilityHidden(true)
                RouteAction(title: korean ? "지도 크게 보기" : "EXPAND MAP") {
                    isExpanded = true
                }
                if legs.contains(where: { $0.quality != .routed }) {
                    RouteInfo(text: korean ? "점선은 경로 추정치예요." : "Dashed lines show estimated paths.")
                }
            }
            .fullScreenCover(isPresented: $isExpanded) {
                ExpandedRouteMap(points: points, legs: legs, origin: origin)
            }
        }
    }
}

private struct ExpandedRouteMap: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.gallrKorean) private var korean
    let points: [RouteMapPoint]
    let legs: [EstimatedRouteLeg]
    let origin: GeoPoint?
    @State private var fitRequest = 0

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(korean ? "닫기" : "CLOSE") { dismiss() }
                    .frame(minHeight: 44)
                Spacer()
                Button(korean ? "경로 맞추기" : "FIT ROUTE") { fitRequest += 1 }
                    .frame(minHeight: 44)
            }
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 16)
            NativeRouteMap(
                points: points,
                legs: legs,
                origin: origin,
                interactive: true,
                fitRequest: fitRequest
            )
        }
        .foregroundStyle(GallrStyle.foreground)
        .background(GallrStyle.background)
    }
}

private struct NativeRouteMap: UIViewRepresentable {
    let points: [RouteMapPoint]
    let legs: [EstimatedRouteLeg]
    let origin: GeoPoint?
    let interactive: Bool
    var fitRequest = 0

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        let configuration = MKStandardMapConfiguration(elevationStyle: .flat, emphasisStyle: .muted)
        configuration.pointOfInterestFilter = .excludingAll
        map.preferredConfiguration = configuration
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.isScrollEnabled = interactive
        map.isZoomEnabled = interactive
        map.isUserInteractionEnabled = interactive
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let coordinator = context.coordinator
        let changed = coordinator.pointIDs != points.map { "\($0.coordinate.latitude),\($0.coordinate.longitude),\($0.title)" }
            || coordinator.legs != legs || coordinator.origin != origin
        if changed {
            coordinator.pointIDs = points.map { "\($0.coordinate.latitude),\($0.coordinate.longitude),\($0.title)" }
            coordinator.legs = legs
            coordinator.origin = origin
            map.removeAnnotations(map.annotations)
            map.removeOverlays(map.overlays)
            coordinator.estimated.removeAll()
            let annotations = points.enumerated().map { offset, point in
                RouteStopAnnotation(point: point, number: offset + 1)
            }
            map.addAnnotations(annotations)
            if let origin {
                let start = MKPointAnnotation()
                start.coordinate = CLLocationCoordinate2D(latitude: origin.latitude, longitude: origin.longitude)
                map.addAnnotation(start)
            }
            for leg in legs {
                let coordinates = leg.geometry.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                guard coordinates.count > 1 else { continue }
                let line = MKPolyline(coordinates: coordinates, count: coordinates.count)
                if leg.quality != .routed {
                    coordinator.estimated.insert(ObjectIdentifier(line))
                }
                map.addOverlay(line, level: .aboveRoads)
            }
        }

        if changed || coordinator.fitRequest != fitRequest {
            coordinator.fitRequest = fitRequest
            var bounds = map.overlays.reduce(MKMapRect.null) { $0.union($1.boundingMapRect) }
            for annotation in map.annotations {
                let point = MKMapPoint(annotation.coordinate)
                bounds = bounds.union(MKMapRect(x: point.x - 256, y: point.y - 256, width: 512, height: 512))
            }
            if !bounds.isNull {
                map.setVisibleMapRect(
                    bounds,
                    edgePadding: UIEdgeInsets(top: 36, left: 32, bottom: 36, right: 32),
                    animated: interactive
                )
            }
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var pointIDs: [String] = []
        var legs: [EstimatedRouteLeg] = []
        var origin: GeoPoint?
        var fitRequest: Int?
        var estimated: Set<ObjectIdentifier> = []

        func mapView(_ mapView: MKMapView, rendererFor overlay: any MKOverlay) -> MKOverlayRenderer {
            guard let line = overlay as? MKPolyline else { return MKOverlayRenderer(overlay: overlay) }
            let renderer = MKPolylineRenderer(polyline: line)
            renderer.strokeColor = UIColor { traits in traits.userInterfaceStyle == .dark ? .white : .black }
            renderer.lineWidth = 3
            if estimated.contains(ObjectIdentifier(line)) {
                renderer.lineDashPattern = [6, 5]
            }
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: any MKAnnotation) -> MKAnnotationView? {
            if let stop = annotation as? RouteStopAnnotation, stop.coverURL != nil {
                let identifier = "routeStopCover"
                let view = mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? RouteStopAnnotationView
                    ?? RouteStopAnnotationView(annotation: stop, reuseIdentifier: identifier)
                view.configure(with: stop)
                return view
            }
            let identifier = "routeStop"
            let view = mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: identifier)
            view.annotation = annotation
            view.markerTintColor = .black
            view.glyphTintColor = .white
            view.glyphText = annotation.title.flatMap { $0 }?.split(separator: " ").first.map(String.init)
            view.canShowCallout = true
            return view
        }
    }
}

private final class RouteStopAnnotation: MKPointAnnotation {
    let coverURL: URL?
    let number: Int

    init(point: RouteMapPoint, number: Int) {
        coverURL = point.coverURL
        self.number = number
        super.init()
        coordinate = point.coordinate
        title = "\(number) · \(point.title)"
    }
}

private final class RouteStopAnnotationView: MKAnnotationView {
    private let cover = UIImageView(frame: CGRect(x: 0, y: 0, width: 32, height: 42))
    private let number = UILabel(frame: CGRect(x: 14, y: 24, width: 18, height: 18))
    private var loading: URLSessionDataTask?

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame.size = cover.frame.size
        centerOffset = CGPoint(x: 0, y: -21)
        canShowCallout = true
        cover.backgroundColor = .black
        cover.contentMode = .scaleAspectFill
        cover.clipsToBounds = true
        addSubview(cover)
        number.backgroundColor = .black
        number.textColor = .white
        number.font = .monospacedDigitSystemFont(ofSize: 11, weight: .semibold)
        number.textAlignment = .center
        addSubview(number)
    }

    required init?(coder: NSCoder) { nil }

    override func prepareForReuse() {
        super.prepareForReuse()
        loading?.cancel()
        loading = nil
        cover.image = nil
    }

    func configure(with stop: RouteStopAnnotation) {
        annotation = stop
        accessibilityLabel = stop.title
        number.text = String(stop.number)
        loading?.cancel()
        cover.image = nil
        guard let url = stop.coverURL, url.scheme == "https" else { return }
        loading = URLSession.shared.dataTask(with: url) { [weak self, weak stop] data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self, let stop, self.annotation === stop else { return }
                self.cover.image = image
            }
        }
        loading?.resume()
    }
}

@MainActor
func routeShareItems(
    payload: RouteSharePayload,
    language: AppLanguage,
    walkingLegs: WalkingLegs = WalkingLegs.companion.EMPTY,
    dark: Bool
) -> [Any] {
    guard let url = URL(string: payload.link) else { return [payload.link] }
    let content = RouteShareCardContent.companion.from(
        route: payload.route,
        link: payload.link,
        language: language,
        legEstimator: RoutedLegEstimator(legs: walkingLegs, fallback: LocalApproximateRouteLegEstimator())
    )
    let renderer = ImageRenderer(content: NativeRouteShareCard(content: content, korean: language == .ko, dark: dark))
    renderer.scale = 3
    if let image = renderer.uiImage {
        return [image, url]
    }
    return [url]
}

private struct NativeRouteShareCard: View {
    let content: RouteShareCardContent
    let korean: Bool
    let dark: Bool
    private var ink: Color { dark ? .white : .black }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(content.eyebrow).font(.subheadline.weight(.medium))
            Text(content.name).font(.title.bold()).lineLimit(2)
            Text(content.summary).font(.subheadline.weight(.medium))
            Canvas { context, size in
                guard let first = content.drawing.first else { return }
                var route = Path()
                route.move(to: CGPoint(x: first.x * size.width, y: first.y * size.height))
                for point in content.drawing.dropFirst() {
                    route.addLine(to: CGPoint(x: point.x * size.width, y: point.y * size.height))
                }
                context.stroke(route, with: .color(ink), lineWidth: 1.5)
                for point in content.drawing {
                    context.fill(
                        Path(ellipseIn: CGRect(x: point.x * size.width - 3, y: point.y * size.height - 3, width: 6, height: 6)),
                        with: .color(ink)
                    )
                }
            }
            .frame(width: 144, height: 144)
            .padding(12)
            ForEach(Array(content.stopLines.enumerated()), id: \.offset) { _, stop in
                Text(stop).font(.subheadline).lineLimit(1)
            }
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("gallr").font(.title2.bold())
                    Text(content.caption).font(.caption)
                }
                Spacer()
                if let qr = routeQR(content.qrTarget) {
                    Image(uiImage: qr).interpolation(.none).resizable().frame(width: 68, height: 68)
                        .padding(8)
                        .background(.white)
                }
            }
            .padding(.top, 8)
        }
        .frame(width: 304, alignment: .leading)
        .padding(28)
        .foregroundStyle(ink)
        .background(dark ? Color(white: 0.07) : .white)
        .environment(\.locale, Locale(identifier: korean ? "ko" : "en"))
    }

    private func routeQR(_ target: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(target.utf8)
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}

struct NativeRouteActivitySheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
