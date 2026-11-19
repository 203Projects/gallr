import SwiftUI
import composeApp

struct CatalogueRootView: View {
    let tab: GallrTab
    let app: NativeGallrApp

    var body: some View {
        switch tab {
        case .home: DiscoveryHome(app: app)
        case .list: DiscoveryList(app: app)
        case .map: DiscoveryMap(app: app)
        default: EmptyView()
        }
    }
}

struct CatalogueDestinationView: View {
    let route: GallrRoute
    let app: NativeGallrApp

    var body: some View {
        switch route {
        case .exhibition(let id), .exhibitionNote(let id), .exhibitionShare(let id),
             .exhibitionNotification(let id), .recommendationExhibition(let id), .recommendationShare(let id):
            DiscoveryExhibitionRoute(app: app, id: id, route: route)
        case .gallery(let id): DiscoveryGalleryRoute(app: app, id: id)
        case .event(let id): DiscoveryEvent(app: app, id: id)
        case .editors: DiscoveryEditors(app: app)
        case .editor(let id): DiscoveryEditor(app: app, id: id)
        case .collection(let id): DiscoveryCollection(app: app, id: id)
        case .recommendations: DiscoveryRecommendations(app: app)
        default: EmptyView()
        }
    }
}

@MainActor
final class DiscoveryContext: ObservableObject {
    @Published private(set) var language = AppLanguage.ko
    @Published private(set) var bookmarks = Set<String>()
    private var impressions = Set<String>()
    private var observations: [NativeObservation] = []

    func connect(_ app: NativeGallrApp) {
        guard observations.isEmpty else { return }
        observations = [
            app.observeLanguage { [weak self] in self?.language = $0 },
            app.observeBookmarks { [weak self] in self?.bookmarks = $0 },
        ]
    }

    func disconnect() {
        observations.forEach { $0.cancel() }
        observations.removeAll()
    }

    func text(_ ko: String, _ en: String) -> String {
        language == .ko ? ko : en
    }

    func font(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        discoveryFont(style, weight: weight, korean: language == .ko)
    }

    func recordImpression(_ id: String, surface: AnalyticsSurface, attribution: DiscoveryCardAttribution, app: NativeGallrApp) {
        let key = "\(surface.name)/\(attribution.kind.name)/\(id)"
        guard impressions.insert(key).inserted else { return }
        app.recordExhibitionImpression(id: id, surface: surface, kind: attribution.kind, position: attribution.position)
    }
}

struct DiscoveryPage<Content: View>: View {
    var title: String? = nil
    var eagerSections = false
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            Group {
                if eagerSections {
                    VStack(alignment: .leading, spacing: 24) { sections }
                } else {
                    LazyVStack(alignment: .leading, spacing: 24) { sections }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
            .frame(maxWidth: 960)
            .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private var sections: some View {
        if let title {
            Text(title)
                .font(discoveryFont(.title2, weight: .bold, korean: title.containsHangul))
                .accessibilityAddTraits(.isHeader)
        }
        content
    }
}

struct DiscoverySectionTitle: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(discoveryFont(.headline, weight: .bold, korean: title.containsHangul)).accessibilityAddTraits(.isHeader)
            if let subtitle { Text(subtitle).font(discoveryFont(.caption, korean: subtitle.containsHangul)).foregroundStyle(GallrStyle.secondary) }
        }
    }
}

struct DiscoveryMessage: View {
    let title: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 16) {
            Text(title).font(discoveryFont(.body, korean: title.containsHangul)).multilineTextAlignment(.center)
            if let actionTitle, let action {
                Button(actionTitle, action: action).buttonStyle(DiscoveryButtonStyle(prominent: true, accent: true))
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, minHeight: 160)
    }
}

struct DiscoveryButtonStyle: ButtonStyle {
    var prominent = false
    var accent = false
    @Environment(\.colorScheme) private var appearance
    @Environment(\.locale) private var locale

    func makeBody(configuration: Configuration) -> some View {
        let ink = appearance == .dark ? Color.white : .black
        let paper = appearance == .dark ? Color.black : .white
        configuration.label
            .font(discoveryFont(.subheadline, weight: .semibold, korean: locale.identifier.hasPrefix("ko")))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minWidth: 44, minHeight: 44)
            .foregroundStyle(accent ? .black : (prominent ? paper : ink))
            .background(accent ? GallrStyle.accent : (prominent ? ink : Color.clear), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(ink.opacity(accent ? 0 : (prominent ? 1 : 0.24))))
            .opacity(configuration.isPressed ? 0.64 : 1)
    }
}

struct DiscoveryArtwork: View {
    let url: String?
    var caption: String = ""
    var fill = true

    var body: some View {
        Rectangle()
            .fill(GallrStyle.mutedSurface)
            .overlay {
                AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
                    if let image = phase.image {
                        image.resizable().aspectRatio(contentMode: fill ? .fill : .fit)
                    }
                }
                .allowsHitTesting(false)
            }
            .clipped()
            .contentShape(Rectangle())
            .accessibilityLabel(caption)
            .accessibilityHidden(caption.isEmpty)
    }
}

struct DiscoveryExhibitionCard: View {
    let exhibition: Exhibition
    @ObservedObject var context: DiscoveryContext
    @Environment(\.dynamicTypeSize) private var typeSize
    let app: NativeGallrApp
    let open: () -> Void
    var reason: String? = nil
    var surface: AnalyticsSurface = .list
    var attribution: DiscoveryCardAttribution? = nil
    var permitsBookmark = true
    var visited = false
    var large = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Button(action: open) {
                if typeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 0) {
                        DiscoveryArtwork(url: exhibition.coverImageUrl).aspectRatio(1.2, contentMode: .fit)
                        cardCaption
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .background(GallrStyle.mutedSurface)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .contentShape(RoundedRectangle(cornerRadius: 16))
                } else {
                    artworkCard
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("exhibition.row.\(exhibition.id)")
            if permitsBookmark {
                DiscoveryBookmark(app: app, context: context, exhibition: exhibition, surface: surface)
                    .padding(8)
                    .zIndex(1)
            }
        }
        .background {
            if let attribution {
                DiscoveryVisibility {
                    context.recordImpression(exhibition.id, surface: surface, attribution: attribution, app: app)
                }
            }
        }
    }

    private var artworkCard: some View {
        DiscoveryArtwork(url: exhibition.coverImageUrl)
            .aspectRatio(large ? 0.88 : 1.18, contentMode: .fit)
            .overlay(alignment: .bottomLeading) {
                cardCaption
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .padding(.top, 20)
                    .background(
                        LinearGradient(
                            stops: [.init(color: .clear, location: 0), .init(color: .black.opacity(0.74), location: 0.47)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
            }
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    private var cardCaption: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(exhibition.localizedName(lang: context.language))
                .font(GallrStyle.font(16, weight: .bold, korean: context.language == .ko, relativeTo: .headline))
            Text(exhibition.localizedVenueName(lang: context.language)).font(context.font(.subheadline))
            Text(exhibition.localizedDateRange(lang: context.language)).font(context.font(.caption))
            if let reason, !reason.isEmpty { Text(reason).font(context.font(.caption)) }
            if visited { Text(context.text("방문함", "Visited")).font(context.font(.caption, weight: .semibold)) }
        }
    }
}

struct DiscoveryBookmark: View {
    let app: NativeGallrApp
    @ObservedObject var context: DiscoveryContext
    let exhibition: Exhibition
    var surface: AnalyticsSurface = .exhibitionDetail

    var body: some View {
        let saved = context.bookmarks.contains(exhibition.id)
        Button {
            app.toggleBookmark(id: exhibition.id, surface: surface)
        } label: {
            Image(systemName: saved ? "bookmark.fill" : "bookmark")
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(saved ? context.text("북마크 삭제", "Remove bookmark") : context.text("북마크 추가", "Add bookmark"))
        .accessibilityIdentifier("exhibition.bookmark.\(exhibition.id)")
    }
}

final class DiscoveryModels: ObservableObject {
    private var releases: [() -> Void] = []

    func retain<T: Lifecycle_viewmodelViewModel>(_ model: T, app: NativeGallrApp) -> T {
        releases.append { app.releaseViewModel(viewModel: model) }
        return model
    }

    deinit {
        releases.forEach { $0() }
    }
}

func discoveryToday() -> Kotlinx_datetimeLocalDate? {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Asia/Seoul") ?? .current
    let components = calendar.dateComponents([.year, .month, .day], from: Date())
    guard let year = components.year, let month = components.month, let day = components.day else { return nil }
    return Kotlinx_datetimeLocalDate(year: Int32(year), month: Int32(month), day: Int32(day))
}

struct DiscoveryCardAttribution {
    let kind: DiscoveryKind
    let position: PositionBucket

    init(_ kind: DiscoveryKind, index: Int? = nil) {
        self.kind = kind
        position = MobileAnalyticsEventFactoryKt.positionBucket(index: index.map { KotlinInt(value: Int32($0)) })
    }
}

struct DiscoveryVisibility: UIViewRepresentable {
    let onVisible: () -> Void
    @Environment(\.scenePhase) private var scene
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible

    func makeUIView(context: Context) -> DiscoveryVisibilityProbe {
        DiscoveryVisibilityProbe()
    }

    func updateUIView(_ view: DiscoveryVisibilityProbe, context: Context) {
        view.observe(enabled: surfaceVisible && scene == .active, onVisible: onVisible)
    }

    static func dismantleUIView(_ view: DiscoveryVisibilityProbe, coordinator: ()) {
        view.observe(enabled: false, onVisible: {})
    }
}

/// Follow the UIKit clip rectangles of nested scroll views without invalidating the SwiftUI layout.
final class DiscoveryVisibilityProbe: UIView {
    private var timer: Timer?
    private var isObserving = false
    private var exposed = false
    private var onVisible: () -> Void = {}

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
    }

    required init?(coder: NSCoder) { nil }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateSampling()
    }

    func observe(enabled: Bool, onVisible: @escaping () -> Void) {
        self.onVisible = onVisible
        isObserving = enabled
        updateSampling()
    }

    private func updateSampling() {
        guard window != nil, isObserving, !exposed else {
            timer?.invalidate()
            timer = nil
            return
        }
        guard timer == nil else { return }
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in self?.sample() }
        self.timer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func sample() {
        guard let window, isObserving, !exposed else { return }
        let frame = convert(bounds, to: window)
        guard !frame.isEmpty else { return }
        var clipped = frame.intersection(window.bounds)
        var ancestor = superview
        while let parent = ancestor, parent !== window {
            guard !parent.isHidden, parent.alpha > 0.01 else { return }
            if let scroll = parent as? UIScrollView {
                clipped = clipped.intersection(scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window))
            } else if parent.clipsToBounds {
                clipped = clipped.intersection(parent.convert(parent.bounds, to: window))
            }
            guard !clipped.isEmpty else { return }
            ancestor = parent.superview
        }
        guard clipped.width * clipped.height >= frame.width * frame.height * 0.5 else { return }
        exposed = true
        updateSampling()
        onVisible()
    }
}

func discoveryFont(_ style: Font.TextStyle, weight: Font.Weight = .regular, korean: Bool) -> Font {
    let size: CGFloat
    switch style {
    case .largeTitle: size = 32
    case .title: size = 28
    case .title2: size = 24
    case .title3: size = 20
    case .headline: size = 18
    case .body: size = 16
    case .subheadline: size = 14
    case .caption, .caption2, .footnote: size = 12
    default: size = 16
    }
    return GallrStyle.font(size, weight: weight, korean: korean, relativeTo: style)
}

extension String {
    var containsHangul: Bool {
        unicodeScalars.contains { scalar in
            (0xAC00...0xD7A3).contains(scalar.value) || (0x3130...0x318F).contains(scalar.value)
        }
    }
}

extension View {
    func discoveryStyle(language: AppLanguage) -> some View {
        let korean = language == .ko
        return self
            .environment(\.locale, Locale(identifier: korean ? "ko_KR" : "en_US"))
            .font(discoveryFont(.body, korean: korean))
            .foregroundStyle(GallrStyle.foreground)
    }
}

func discoveryDateRange(start: Kotlinx_datetimeLocalDate, end: Kotlinx_datetimeLocalDate, language: AppLanguage) -> String {
    let locale = Locale(identifier: language == .ko ? "ko_KR" : "en_US")
    let formatter = DateIntervalFormatter()
    formatter.locale = locale
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateStyle = .medium
    formatter.timeStyle = .none
    let date = { (value: Kotlinx_datetimeLocalDate) in
        Date(timeIntervalSince1970: Double(value.toEpochDays()) * 86_400)
    }
    return formatter.string(from: date(start), to: date(end))
}
