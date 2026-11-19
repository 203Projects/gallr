import CoreLocation
import MapKit
import SwiftUI
import composeApp

struct DiscoveryMap: View {
    let app: NativeGallrApp
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @StateObject private var context = DiscoveryContext()
    @StateObject private var location = DiscoveryLocation()
    @State private var map: PersonalMapUiState?
    @State private var observation: NativeObservation?
    @State private var command: DiscoveryMapCommand?
    @State private var selection: MapSelection?
    @State private var visibleCenter = CLLocationCoordinate2D(latitude: 37.5665, longitude: 126.978)

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                mode(.all, context.text("전체 전시", "All exhibitions")).accessibilityIdentifier("map.mode.all")
                mode(.toVisit, context.text("내 전시", "My exhibitions")).accessibilityIdentifier("map.mode.saved")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            Divider()
            ZStack(alignment: .topTrailing) {
                // Square by design: the basemap meets the window edges.
                DiscoveryMapSurface(
                    pins: surfaceVisible ? pins : [],
                    command: command,
                    isActive: surfaceVisible,
                    showLocation: surfaceVisible && location.isAuthorized,
                    onSelect: openPins,
                    onMove: { if surfaceVisible { visibleCenter = $0 } }
                )
                controls
                if let map, map.isLoading {
                    ProgressView().padding(12)
                        .background(GallrStyle.background, in: RoundedRectangle(cornerRadius: 12))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                        .allowsHitTesting(false)
                } else if map?.errorMessage != nil {
                    Button(context.text("전시 다시 불러오기", "Retry exhibitions")) { app.tabs.loadAllExhibitions() }
                        .buttonStyle(DiscoveryButtonStyle(prominent: true))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                }
                mapFooter
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear {
            context.connect(app)
            if surfaceVisible { app.recordSurface(surface: .map, entryPoint: .tab) }
            observation = app.observePersonalMap { map = $0 }
            if surfaceVisible { location.request() }
        }
        .onChange(of: surfaceVisible) { visible in
            if visible {
                app.recordSurface(surface: .map, entryPoint: .tab)
                location.request()
            }
        }
        .onDisappear {
            observation?.cancel()
            context.disconnect()
        }
        .onReceive(location.$coordinate.compactMap { $0 }) { coordinate in
            if surfaceVisible { command = DiscoveryMapCommand(kind: .center(coordinate)) }
        }
        .sheet(item: $selection) { selection in
            DiscoveryPage {
                DiscoverySectionTitle(title: context.text("전시", "Exhibitions"))
                    .accessibilityIdentifier("map.selection.title")
                ForEach(selection.pins) { pin in
                    DiscoveryExhibitionCard(
                        exhibition: pin.exhibition, context: context, app: app,
                        open: {
                            self.selection = nil
                            open(pin)
                        },
                        surface: .map
                    )
                }
            }
        }
    }

    private var pins: [DiscoveryMapPin] {
        (map?.resultExhibitions ?? []).compactMap { exhibition in
            guard let latitude = exhibition.latitude?.doubleValue,
                  let longitude = exhibition.longitude?.doubleValue else { return nil }
            let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
            guard CLLocationCoordinate2DIsValid(coordinate) else { return nil }
            return DiscoveryMapPin(
                exhibition: exhibition, coordinate: coordinate,
                title: exhibition.localizedName(lang: context.language),
                venue: exhibition.localizedVenueName(lang: context.language),
                saved: context.bookmarks.contains(exhibition.id)
            )
        }
    }

    private func mode(_ value: PersonalMapMode, _ label: String) -> some View {
        let selected = (map?.mode ?? .all) == value
        return Button(label) { app.personalMap.setMode(newMode: value) }
            .buttonStyle(DiscoveryButtonStyle(prominent: selected))
            .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var controls: some View {
        VStack(spacing: 6) {
            control("plus", context.text("확대", "Zoom in")) { command = DiscoveryMapCommand(kind: .zoom(0.5)) }
            control("minus", context.text("축소", "Zoom out")) { command = DiscoveryMapCommand(kind: .zoom(2)) }
            control("location", context.text("내 위치", "My location")) {
                if let coordinate = location.coordinate {
                    command = DiscoveryMapCommand(kind: .center(coordinate))
                }
                location.request(showSettingsIfDenied: true)
            }
        }
        .padding(12)
    }

    private var mapFooter: some View {
        VStack(alignment: .leading, spacing: 8) {
            if surfaceVisible, pins.contains(where: \.saved) {
                Label {
                    Text(context.text("저장한 전시", "Saved exhibition")).font(context.font(.caption))
                } icon: {
                    Image(systemName: "bookmark.fill").foregroundStyle(GallrStyle.accent)
                }
                .padding(8)
                .background(GallrStyle.background, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("map.legend.saved")
            }
            if app.routesEnabled {
                Button {
                    let coordinate = location.coordinate ?? visibleCenter
                    navigation.push(.routePlanner(latitude: coordinate.latitude, longitude: coordinate.longitude, mode: "NEIGHBORHOOD"))
                } label: {
                    Label(context.text("이 주변 동선 만들기", "Plan a route nearby"), systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                }
                .buttonStyle(DiscoveryButtonStyle(prominent: true))
            }
            Button {
                selection = MapSelection(pins: pins)
            } label: {
                Label(context.text("지도 전시 목록", "Map exhibition list"), systemImage: "list.bullet")
            }
            .buttonStyle(DiscoveryButtonStyle())
            .background(GallrStyle.background, in: RoundedRectangle(cornerRadius: 12))
            .disabled(!surfaceVisible || pins.isEmpty)
        }
        .padding(.leading, 16)
        .padding(.bottom, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
    }

    private func control(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .frame(width: 44, height: 44)
                .background(GallrStyle.background, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityIdentifier("map.control.\(icon)")
    }

    private func open(_ pin: DiscoveryMapPin) {
        let attribution = DiscoveryCardAttribution(.nearby)
        context.recordImpression(pin.id, surface: .map, attribution: attribution, app: app)
        app.recordExhibitionOpened(id: pin.id, surface: .map, kind: .nearby)
        navigation.push(.exhibition(pin.id))
    }

    private func openPins(_ pins: [DiscoveryMapPin]) {
        guard surfaceVisible else { return }
        if pins.count == 1, let pin = pins.first {
            open(pin)
        } else if !pins.isEmpty {
            selection = MapSelection(pins: pins)
        }
    }
}

private struct MapSelection: Identifiable {
    let id = UUID()
    let pins: [DiscoveryMapPin]
}

private struct DiscoveryMapPin: Identifiable {
    let exhibition: Exhibition
    let coordinate: CLLocationCoordinate2D
    let title: String
    let venue: String
    let saved: Bool
    var id: String { exhibition.id }
}

private struct DiscoveryMapCommand {
    enum Kind {
        case zoom(Double)
        case center(CLLocationCoordinate2D)
    }

    let kind: Kind
    let id = UUID()
}

private final class DiscoveryLocation: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var coordinate: CLLocationCoordinate2D?
    @Published private(set) var isAuthorized = false
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func request(showSettingsIfDenied: Bool = false) {
        let status = manager.authorizationStatus
        isAuthorized = status == .authorizedAlways || status == .authorizedWhenInUse
        if isAuthorized {
            manager.requestLocation()
        } else if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else if status == .denied, showSettingsIfDenied,
                  let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        isAuthorized = status == .authorizedAlways || status == .authorizedWhenInUse
        if isAuthorized { manager.requestLocation() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        coordinate = locations.last?.coordinate
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

private struct DiscoveryMapSurface: UIViewRepresentable {
    let pins: [DiscoveryMapPin]
    let command: DiscoveryMapCommand?
    let isActive: Bool
    let showLocation: Bool
    let onSelect: ([DiscoveryMapPin]) -> Void
    let onMove: (CLLocationCoordinate2D) -> Void

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.pointOfInterestFilter = .excludingAll
        map.showsTraffic = false
        map.showsCompass = false
        map.setRegion(MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 37.5665, longitude: 126.978),
            latitudinalMeters: 13_000, longitudinalMeters: 13_000
        ), animated: false)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        map.isHidden = !isActive
        if map.showsUserLocation != showLocation { map.showsUserLocation = showLocation }
        guard isActive else { return }

        let visibleIDs = Set(pins.map(\.id))
        let removed = coordinator.annotations.values.filter { !visibleIDs.contains($0.pin.id) }
        map.removeAnnotations(removed)
        for annotation in removed { coordinator.annotations.removeValue(forKey: annotation.pin.id) }

        for pin in pins {
            if let annotation = coordinator.annotations[pin.id] {
                let visualChange = annotation.pin.saved != pin.saved || annotation.pin.title != pin.title ||
                    annotation.pin.exhibition.coverImageUrl != pin.exhibition.coverImageUrl
                annotation.pin = pin
                if visualChange, let view = map.view(for: annotation) as? DiscoveryAnnotationView {
                    view.render(annotation: annotation)
                }
            } else {
                let annotation = DiscoveryAnnotation(pin: pin)
                coordinator.annotations[pin.id] = annotation
                map.addAnnotation(annotation)
            }
        }
        coordinator.positionOverlappingPins(on: map)
        guard let command, command.id != coordinator.lastCommand else { return }
        coordinator.lastCommand = command.id
        switch command.kind {
        case .zoom(let scale):
            var region = map.region
            region.span.latitudeDelta = min(150, max(0.001, region.span.latitudeDelta * scale))
            region.span.longitudeDelta = min(150, max(0.001, region.span.longitudeDelta * scale))
            map.setRegion(region, animated: true)
        case .center(let coordinate):
            map.setRegion(MKCoordinateRegion(
                center: coordinate, latitudinalMeters: 3_000, longitudinalMeters: 3_000
            ), animated: true)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: DiscoveryMapSurface
        var lastCommand: UUID?
        var annotations: [String: DiscoveryAnnotation] = [:]

        init(parent: DiscoveryMapSurface) { self.parent = parent }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard annotation is DiscoveryAnnotation || annotation is MKClusterAnnotation else { return nil }
            let id = annotation is MKClusterAnnotation ? "cluster" : "exhibition"
            let view = mapView.dequeueReusableAnnotationView(withIdentifier: id) as? DiscoveryAnnotationView
                ?? DiscoveryAnnotationView(annotation: annotation, reuseIdentifier: id)
            view.render(annotation: annotation)
            return view
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            guard let annotation = view.annotation else { return }
            mapView.deselectAnnotation(annotation, animated: false)
            if let pin = annotation as? DiscoveryAnnotation {
                parent.onSelect([pin.pin])
            } else if let cluster = annotation as? MKClusterAnnotation {
                let pins = cluster.memberAnnotations.compactMap { ($0 as? DiscoveryAnnotation)?.pin }
                let distinct = Set(pins.map { "\($0.coordinate.latitude)/\($0.coordinate.longitude)" })
                if distinct.count > 1, mapView.region.span.latitudeDelta > 0.018 {
                    mapView.showAnnotations(cluster.memberAnnotations, animated: true)
                } else {
                    parent.onSelect(pins.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending })
                }
            }
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            guard parent.isActive else { return }
            positionOverlappingPins(on: mapView)
            let center = mapView.centerCoordinate
            DispatchQueue.main.async { [weak self] in self?.parent.onMove(center) }
        }

        func positionOverlappingPins(on map: MKMapView) {
            let nearby = map.region.span.longitudeDelta <= 0.035
            let groups = Dictionary(grouping: annotations.values) { annotation in
                let coordinate = annotation.pin.coordinate
                return "\(coordinate.latitude)/\(coordinate.longitude)"
            }
            for group in groups.values {
                for (index, annotation) in group.sorted(by: { $0.pin.id < $1.pin.id }).enumerated() {
                    let source = annotation.pin.coordinate
                    var target = source
                    if nearby, group.count > 1 {
                        let angle = Double(index) * 2 * .pi / Double(group.count)
                        let radius = max(30.0, Double(group.count) * 8.0)
                        let center = map.convert(source, toPointTo: map)
                        target = map.convert(
                            CGPoint(x: center.x + cos(angle) * radius, y: center.y + sin(angle) * radius),
                            toCoordinateFrom: map
                        )
                    }
                    if abs(annotation.coordinate.latitude - target.latitude) > 0.0000001 ||
                        abs(annotation.coordinate.longitude - target.longitude) > 0.0000001 {
                        annotation.coordinate = target
                    }
                }
            }
        }
    }
}

private final class DiscoveryAnnotation: NSObject, MKAnnotation {
    var pin: DiscoveryMapPin
    var title: String? { pin.title }
    var subtitle: String? { pin.venue }
    @objc dynamic var coordinate: CLLocationCoordinate2D

    init(pin: DiscoveryMapPin) {
        self.pin = pin
        self.coordinate = pin.coordinate
    }
}

private final class DiscoveryAnnotationView: MKAnnotationView {
    private var imageTask: Task<Void, Never>?
    private var artwork: UIImage?
    private var ink = UIColor.black
    private var count: Int?
    private var renderingID: String?

    override func prepareForReuse() {
        super.prepareForReuse()
        imageTask?.cancel()
        renderingID = nil
    }

    func render(annotation: MKAnnotation) {
        self.annotation = annotation
        imageTask?.cancel()
        artwork = nil
        count = nil
        let pin = annotation as? DiscoveryAnnotation
        let cluster = annotation as? MKClusterAnnotation
        let saved = pin?.pin.saved ?? (cluster?.memberAnnotations.contains { ($0 as? DiscoveryAnnotation)?.pin.saved == true } ?? false)
        ink = saved ? UIColor(red: 1, green: 84.0 / 255, blue: 0, alpha: 1) : .black
        displayPriority = saved ? .defaultHigh : .defaultLow
        clusteringIdentifier = cluster == nil ? "exhibitions" : nil
        count = cluster?.memberAnnotations.count
        collisionMode = .rectangle
        centerOffset = CGPoint(x: 0, y: -18)
        isAccessibilityElement = true
        accessibilityTraits = .button
        accessibilityIdentifier = pin.map { "map.pin.\($0.pin.id)" } ?? "map.cluster"
        accessibilityLabel = pin?.pin.title ?? cluster?.memberAnnotations.compactMap(\.title).compactMap { $0 }.joined(separator: ", ")
        drawPin()
        guard let pin, let rawURL = pin.pin.exhibition.coverImageUrl, let url = URL(string: rawURL) else { return }
        let id = pin.pin.id
        renderingID = id
        imageTask = Task { [weak self] in
            guard let (bytes, _) = try? await URLSession.shared.data(from: url), !Task.isCancelled,
                  let self, self.renderingID == id, let cover = UIImage(data: bytes) else { return }
            self.artwork = cover
            self.drawPin()
        }
    }

    private func drawPin() {
        let size = CGSize(width: 44, height: 48)
        image = UIGraphicsImageRenderer(size: size).image { context in
            context.cgContext.translateBy(x: 3, y: 3)
            let body = CGRect(x: 2, y: 2, width: 34, height: 34)
            let pin = UIBezierPath()
            pin.move(to: CGPoint(x: 19, y: 40))
            pin.addCurve(to: CGPoint(x: 2, y: 19), controlPoint1: CGPoint(x: 9, y: 34), controlPoint2: CGPoint(x: 2, y: 27))
            pin.addArc(withCenter: CGPoint(x: 19, y: 19), radius: 17, startAngle: .pi, endAngle: 0, clockwise: true)
            pin.addCurve(to: CGPoint(x: 19, y: 40), controlPoint1: CGPoint(x: 36, y: 27), controlPoint2: CGPoint(x: 29, y: 34))
            pin.close()
            context.cgContext.saveGState()
            pin.addClip()
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            if let artwork {
                let crop = CGRect(x: 2, y: 2, width: 34, height: 38)
                let scale = max(crop.width / artwork.size.width, crop.height / artwork.size.height)
                let width = artwork.size.width * scale
                let height = artwork.size.height * scale
                context.cgContext.saveGState()
                context.cgContext.clip(to: crop)
                artwork.draw(in: CGRect(x: crop.midX - width / 2, y: crop.midY - height / 2, width: width, height: height))
                context.cgContext.restoreGState()
            }
            context.cgContext.restoreGState()
            ink.setStroke()
            pin.lineWidth = 3
            pin.stroke()
            if let count {
                let text = "\(count)" as NSString
                let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 12), .foregroundColor: UIColor.black]
                let label = text.size(withAttributes: attributes)
                text.draw(at: CGPoint(x: body.midX - label.width / 2, y: body.midY - label.height / 2), withAttributes: attributes)
            }
        }
    }
}
