import SwiftUI
import composeApp

struct DiscoveryHome: View {
    let app: NativeGallrApp
    @EnvironmentObject private var navigation: GallrNavigation
    @EnvironmentObject private var accountProfile: NativeAccountProfile
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.openURL) private var openURL
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @ScaledMetric(relativeTo: .headline) private var eventHeight = 184.0
    @Environment(\.dynamicTypeSize) private var typeSize
    @StateObject private var context = DiscoveryContext()
    @State private var state: HomeUiState?
    @State private var listState: ListScreenUiState?
    @State private var recommendations: RecommendationUiState?
    @State private var taste: TastePickSheetState?
    @State private var dismissed: String?
    @State private var observations: [NativeObservation] = []
    @State private var eventPage = 0
    @State private var eventsScrolledAway = false
    @State private var didShowEvents = false
    @State private var updateAvailable: Bool?
    @State private var offerTaste = false

    var body: some View {
        ScrollViewReader { reader in
            DiscoveryPage(eagerSections: true) {
                if updateAvailable == true { updateNudge }
                if let events = listState?.activeEvents, !events.isEmpty {
                    eventPagerVisibility(events).id("home.events")
                }
                if let ready = state as? HomeUiStateReady {
                    let picks = recommendations as? RecommendationUiStateReady
                    let personal = picks?.items.filter(\.hasPersonalEvidence) ?? []
                    greeting(accountProfile.displayName)
                    if let picks, !personal.isEmpty {
                        recommendationRail(terms: picks.tasteTerms, personal: personal)
                    }
                    if !ready.feed.hero.isEmpty && personal.isEmpty {
                        exhibitionRail(context.text("추천 전시", "Featured exhibitions"), ready.feed.hero, large: true)
                    }
                    if !ready.feed.curatorEye.isEmpty { editorEye(ready.feed.curatorEye) }
                    if personal.isEmpty {
                        tastePrompt
                    }
                    if !ready.feed.routes.isEmpty { routesRail(ready.feed.routes) }
                    if !ready.feed.hero.isEmpty && !personal.isEmpty {
                        exhibitionRail(context.text("추천 전시", "Featured exhibitions"), ready.feed.hero)
                    }
                    exhibitionRail(context.text("에디터 추천", "Editor picks"), ready.feed.editorPicks, kind: .editor)
                    exhibitionRail(context.text("팔로잉 갤러리", "From galleries you follow"), ready.feed.fromFollowedGalleries, kind: .gallery)
                    collectionSections(ready.feed.collections)
                    if ready.feed.isEmpty && personal.isEmpty {
                        DiscoveryMessage(title: context.text("추천 전시가 없습니다.", "No featured exhibitions right now."))
                    }
                } else if state is HomeUiStateError {
                    DiscoveryMessage(
                        title: context.text("전시를 불러올 수 없어요.", "Couldn’t load exhibitions."),
                        actionTitle: context.text("다시 시도", "Retry"),
                        action: refresh
                    )
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 240)
                }
            }
            .overlay(alignment: .top) {
                if eventsScrolledAway, let count = listState?.activeEvents.count, count > 1 {
                    Button(context.text("이벤트 \(count)개", "\(count) events")) {
                        if reduceMotion { reader.scrollTo("home.events", anchor: .top) }
                        else { withAnimation { reader.scrollTo("home.events", anchor: .top) } }
                    }
                    .buttonStyle(DiscoveryButtonStyle())
                    .background(GallrStyle.background, in: RoundedRectangle(cornerRadius: 12))
                    .padding(.top, 8)
                    .accessibilityIdentifier("home.events.reveal")
                }
            }
        }
        .discoveryStyle(language: context.language)
        .environment(\.gallrSurfaceVisible, surfaceVisible && taste == nil)
        .refreshable { refresh() }
        .overlay(alignment: .bottom) {
            if let id = dismissed {
                DiscoveryUndo(context: context) {
                    app.localDiscovery.undoDismissal(exhibitionId: id)
                    dismissed = nil
                }
                .padding(16)
            }
        }
        .sheet(isPresented: Binding(get: { isHomeTop && taste != nil }, set: { if !$0 { app.tastePick.close() } })) {
            if let taste { DiscoveryTasteSheet(app: app, context: context, state: taste) }
        }
        .onAppear(perform: connect)
        .onDisappear(perform: disconnect)
        .onChange(of: offerTaste) { _ in updateTasteOffer() }
        .onChange(of: updateAvailable) { _ in updateTasteOffer() }
        .onChange(of: isHomeTop) { visible in
            updateTasteOffer()
            if visible { app.recordSurface(surface: .featured, entryPoint: .tab) }
        }
        .onChange(of: navigation.selected) { selected in
            if selected == .home { app.localDiscovery.refreshRecommendations() }
        }
        .task(id: CarouselTask(count: listState?.activeEvents.count ?? 0, paused: reduceMotion || voiceOver || !surfaceVisible || eventsScrolledAway)) {
            let key = CarouselTask(count: listState?.activeEvents.count ?? 0, paused: reduceMotion || voiceOver || !surfaceVisible || eventsScrolledAway)
            guard key.count > 1, !key.paused else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(4)) } catch { return }
                withAnimation(.easeInOut(duration: 0.2)) {
                    eventPage = (eventPage + 1) % key.count
                }
            }
        }
    }

    private func connect() {
        guard observations.isEmpty else { return }
        context.connect(app)
        observations = [
            app.observeHome { state = $0 },
            app.observeList { listState = $0 },
            app.observeRecommendations { recommendations = $0 },
            app.observeTastePick { taste = $0 },
            app.observeUpdateNudge { updateAvailable = $0.boolValue },
            app.observeTasteOffer { offerTaste = $0.boolValue },
        ]
        if isHomeTop { app.recordSurface(surface: .featured, entryPoint: .tab) }
        app.localDiscovery.refreshRecommendations()
        updateTasteOffer()
    }

    private var isHomeTop: Bool {
        surfaceVisible && navigation.selected == .home && (navigation.trails[.home]?.isEmpty ?? true)
    }

    private func updateTasteOffer() {
        if isHomeTop, offerTaste, updateAvailable == false { app.tastePick.offerFirstRun() }
        else { app.tastePick.withdrawOffer() }
    }

    private func disconnect() {
        observations.forEach { $0.cancel() }
        observations.removeAll()
        context.disconnect()
        app.tastePick.withdrawOffer()
    }

    private func refresh() {
        app.tabs.refresh()
        app.home.refreshEditorialReads()
        app.localDiscovery.refreshRecommendations()
    }

    private var updateNudge: some View {
        VStack(alignment: .leading, spacing: 8) {
            DiscoverySectionTitle(
                title: context.text("새 버전이 나왔어요", "A new version is available"),
                subtitle: context.text("최신 버전에서 gallr를 이용해 보세요.", "Get the latest version of gallr.")
            )
            HStack {
                Button(context.text("업데이트", "Update")) {
                    if let url = URL(string: app.updateStoreUrl()), !app.updateStoreUrl().isEmpty { openURL(url) }
                }
                .buttonStyle(DiscoveryButtonStyle(prominent: true))
                Button(context.text("나중에", "Later")) { app.dismissUpdateNudge() }
                    .buttonStyle(DiscoveryButtonStyle())
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
    }

    private func greeting(_ name: String?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Date.now.formatted(.dateTime.month(.wide).day().weekday(.wide)
                .locale(Locale(identifier: context.text("ko_KR", "en_US")))))
                .font(context.font(.caption))
                .foregroundStyle(GallrStyle.secondary)
            Text(name.map { context.text("\($0)님, 안녕하세요", "Hello, \($0)") } ?? context.text("오늘의 전시", "Exhibitions today"))
                .font(GallrStyle.font(28, weight: .bold, korean: context.language == .ko, relativeTo: .title))
                .accessibilityAddTraits(.isHeader)
        }
    }

    @ViewBuilder
    private func eventPagerVisibility(_ events: [Event]) -> some View {
        if #available(iOS 18.0, *) {
            eventPager(events).onScrollVisibilityChange(threshold: 0.01) { visible in
                if visible { didShowEvents = true }
                eventsScrolledAway = didShowEvents && !visible
            }
        } else {
            eventPager(events)
        }
    }

    private func eventPager(_ events: [Event]) -> some View {
        TabView(selection: $eventPage) {
            ForEach(Array(events.enumerated()), id: \.element.id) { index, event in
                Button {
                    navigation.push(.event(event.id))
                } label: {
                    DiscoveryBanner(
                        title: event.localizedName(lang: context.language),
                        subtitle: event.localizedLocationLabel(lang: context.language),
                        image: event.coverImageUrl
                    )
                    .accessibilityIdentifier("home.event.\(event.id)")
                }
                .buttonStyle(.plain)
                .padding(.bottom, events.count > 1 ? 22 : 0)
                .tag(index)
            }
        }
        .frame(height: eventHeight + (events.count > 1 ? 22 : 0))
        .tabViewStyle(.page(indexDisplayMode: events.count > 1 ? .always : .never))
    }

    private func recommendationRail(terms: [ArtTerm], personal: [ExhibitionRecommendation]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                DiscoverySectionTitle(title: context.text("내 취향", "For you"))
                Spacer()
                Button(context.text("모두 보기", "See all")) { navigation.push(.recommendations) }
                    .font(context.font(.subheadline, weight: .medium))
                    .frame(minHeight: 44)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(terms.prefix(5)), id: \.id) { term in
                        Button(term.localizedName(language: context.language)) { app.tastePick.open(mode: .edit) }
                            .buttonStyle(DiscoveryButtonStyle())
                    }
                }
            }
            DiscoveryIndexedRail(ids: personal.map(\.exhibition.id), itemWidth: 300) { index in
                recommendationCard(personal[index], index: index).frame(width: 300)
            }
        }
    }

    private func recommendationCard(_ item: ExhibitionRecommendation, index: Int) -> some View {
        DiscoveryRecommendationCard(
            app: app, context: context, recommendation: item, index: index, dismissed: dismissed,
            onDismissed: { dismissed = $0 }
        )
    }

    private var tastePrompt: some View {
        VStack(alignment: .leading, spacing: 8) {
            DiscoverySectionTitle(title: context.text("내 취향", "For you"))
            Text(context.text("좋아하는 전시를 골라보세요.", "Pick the art you like."))
                .font(context.font(.subheadline))
                .foregroundStyle(GallrStyle.secondary)
            Button(context.text("취향 고르기", "Choose your taste")) { app.tastePick.open(mode: .edit) }
                .buttonStyle(DiscoveryButtonStyle())
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder
    private func exhibitionRail(_ title: String, _ exhibitions: [Exhibition], large: Bool = false, kind: DiscoveryKind = .featured) -> some View {
        if !exhibitions.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                DiscoverySectionTitle(title: title)
                DiscoveryIndexedRail(ids: exhibitions.map(\.id), itemWidth: large ? 280 : 220) { index in
                    let exhibition = exhibitions[index]
                    let attribution = DiscoveryCardAttribution(kind, index: index)
                    DiscoveryExhibitionCard(
                        exhibition: exhibition, context: context, app: app,
                        open: {
                            app.recordExhibitionOpened(
                                id: exhibition.id, surface: .featured, kind: kind, position: attribution.position
                            )
                            navigation.push(.exhibition(exhibition.id))
                        },
                        surface: .featured, attribution: attribution, large: large
                    )
                    .frame(width: large ? 280 : 220)
                }
            }
        }
    }

    private func editorEye(_ cards: [CuratorEyeCard]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            DiscoverySectionTitle(title: context.text("에디터의 시선", "Editor’s eye"))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(Array(cards.enumerated()), id: \.element.exhibition.id) { index, card in
                        let attribution = DiscoveryCardAttribution(.curatorNote, index: index)
                        Button {
                            app.recordExhibitionOpened(
                                id: card.exhibition.id, surface: .featured, kind: .curatorNote, position: attribution.position
                            )
                            navigation.push(.exhibitionNote(card.exhibition.id))
                        } label: {
                            VStack(alignment: .leading, spacing: 0) {
                                DiscoveryArtwork(url: card.exhibition.coverImageUrl).frame(height: 170)
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(card.note.hook(lang: context.language)).font(context.font(.headline))
                                    Text(card.note.firstInsight(lang: context.language)).font(context.font(.subheadline)).lineLimit(typeSize.isAccessibilitySize ? nil : 3)
                                    Text(card.exhibition.localizedName(lang: context.language))
                                        .font(context.font(.caption, weight: .semibold))
                                    Text(card.exhibition.localizedDateRange(lang: context.language)).font(context.font(.caption))
                                }
                                .padding(16)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .frame(width: 260)
                            .background(GallrStyle.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(GallrStyle.divider))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("home.editor-note.\(card.exhibition.id)")
                        .background {
                            DiscoveryVisibility {
                                context.recordImpression(card.exhibition.id, surface: .featured, attribution: attribution, app: app)
                            }
                        }
                    }
                }
            }
        }
    }

    private func routesRail(_ routes: [HomeRoute]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                DiscoverySectionTitle(title: context.text("지금 주목받는 동선", "Routes getting attention"))
                Spacer()
                Button(context.text("전체 보기", "See all")) { navigation.push(.publicRoutes) }
                    .font(context.font(.subheadline)).frame(minHeight: 44)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(routes, id: \.summary.id) { route in
                        Button {
                            app.selectPublicRoute(summary: route.summary)
                            navigation.push(.publicRoute(route.summary.id))
                        } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(spacing: 4) {
                                    ForEach(route.shownCovers, id: \.self) { url in
                                        DiscoveryArtwork(url: url).frame(width: 42, height: 48)
                                            .clipShape(RoundedRectangle(cornerRadius: 12))
                                    }
                                }
                                Text(route.summary.name).font(context.font(.subheadline, weight: .semibold))
                                Text(context.text("전시 \(route.summary.stopCount)곳", "\(route.summary.stopCount) stops"))
                                    .font(context.font(.caption))
                                    .foregroundStyle(GallrStyle.secondary)
                            }
                            .padding(12)
                            .frame(width: 210, alignment: .leading)
                            .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func collectionSections(_ collections: [HomeCollection]) -> some View {
        let curations = collections.filter {
            $0.kind == .mostViewed || $0.kind == .freeAdmission ||
                $0.kind == .closingThisWeek || $0.kind == .openingThisWeek
        }
        let themes = collections.filter { collection in !curations.contains { $0.key == collection.key } }
        if !curations.isEmpty {
            DiscoverySectionTitle(title: context.text("큐레이션", "Curations"))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 145), spacing: 8)], spacing: 8) {
                ForEach(curations, id: \.key) { collection in collectionTile(collection) }
            }
        }
        if !themes.isEmpty {
            DiscoverySectionTitle(title: context.text("테마별 전시", "Exhibitions by theme"))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(themes, id: \.key) { collection in collectionTile(collection).frame(width: 184) }
                }
            }
        }
    }

    private func collectionTile(_ collection: HomeCollection) -> some View {
        Button {
            navigation.push(.collection(collection.key))
        } label: {
            DiscoveryBanner(
                title: collection.localizedTitle(lang: context.language),
                subtitle: collection.localizedTags(lang: context.language).joined(separator: " · "),
                image: collection.coverImageUrl
            )
            .frame(minHeight: 142)
        }
        .buttonStyle(.plain)
    }
}

private struct CarouselTask: Equatable {
    let count: Int
    let paused: Bool
}

struct DiscoveryBanner: View {
    let title: String
    let subtitle: String
    let image: String?

    var body: some View {
        DiscoveryArtwork(url: image)
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(discoveryFont(.headline, weight: .bold, korean: title.containsHangul))
                    if !subtitle.isEmpty { Text(subtitle).font(discoveryFont(.caption, korean: subtitle.containsHangul)) }
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .padding(.top, 8)
                .background(.linearGradient(
                    colors: [.clear, .black.opacity(0.76)],
                    startPoint: .top, endPoint: .bottom
                ))
            }
            .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct DiscoveryUndo: View {
    @ObservedObject var context: DiscoveryContext
    let undo: () -> Void

    var body: some View {
        HStack {
            Text(context.text("비슷한 전시를 덜 보여드릴게요.", "You’ll see fewer exhibitions like this."))
                .font(context.font(.subheadline))
            Spacer(minLength: 8)
            Button(context.text("되돌리기", "Undo"), action: undo)
                .font(context.font(.subheadline, weight: .semibold))
                .frame(minWidth: 44, minHeight: 44)
        }
        .padding(12)
        .background(GallrStyle.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(GallrStyle.divider))
    }
}

struct DiscoveryCollection: View {
    let app: NativeGallrApp
    let id: String
    @EnvironmentObject private var navigation: GallrNavigation
    @StateObject private var context = DiscoveryContext()
    @State private var collection: HomeCollection?
    @State private var terminal = false
    @State private var observation: NativeObservation?

    var body: some View {
        DiscoveryPage(title: collection?.localizedTitle(lang: context.language)) {
            if let collection {
                ForEach(collection.exhibitions, id: \.id) { exhibition in
                    DiscoveryExhibitionCard(
                        exhibition: exhibition, context: context, app: app,
                        open: {
                            app.recordExhibitionOpened(id: exhibition.id, surface: .featured, kind: .featured)
                            navigation.push(.exhibition(exhibition.id))
                        },
                        surface: .featured
                    )
                }
            } else if terminal {
                DiscoveryMessage(title: context.text("큐레이션을 찾을 수 없어요.", "This collection is unavailable."))
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 160)
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear {
            context.connect(app)
            observation = app.observeHome { state in
                if let ready = state as? HomeUiStateReady {
                    if collection == nil { collection = ready.feed.collections.first { $0.key == id } }
                    terminal = true
                } else if state is HomeUiStateError {
                    terminal = true
                }
            }
        }
        .onDisappear {
            observation?.cancel()
            observation = nil
            context.disconnect()
        }
    }
}

private struct DiscoveryIndexedRail<Content: View>: View {
    let ids: [String]
    let itemWidth: CGFloat
    @ViewBuilder var content: (Int) -> Content
    @State private var selected = 0

    var body: some View {
        if #available(iOS 18.0, *) {
            VStack(spacing: 8) {
                rail.onScrollGeometryChange(for: Int.self) { geometry in
                    min(max(Int((geometry.contentOffset.x / (itemWidth + 12)).rounded()), 0), max(0, ids.count - 1))
                } action: { _, index in
                    selected = index
                }
                if ids.count > 1 { indicator }
            }
        } else {
            rail
        }
    }

    private var rail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(Array(ids.enumerated()), id: \.element) { index, _ in
                    content(index)
                }
            }
        }
    }

    private var indicator: some View {
        let dotCount = min(7, ids.count)
        let active = dotCount == ids.count ? selected : min(dotCount - 1, selected * dotCount / ids.count)
        return HStack(spacing: 4) {
            ForEach(0..<dotCount, id: \.self) { index in
                Circle()
                    .fill(index == active ? GallrStyle.foreground : GallrStyle.divider)
                    .frame(width: 5, height: 5)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityHidden(true)
    }
}
