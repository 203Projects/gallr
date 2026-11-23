import SwiftUI
import composeApp

struct DiscoveryList: View {
    let app: NativeGallrApp
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @StateObject private var context = DiscoveryContext()
    @State private var state: ListScreenUiState?
    @State private var observation: NativeObservation?
    @State private var search = ""
    @State private var eventIndex = 0
    @State private var confirmClear = false
    @State private var visited = Set<String>()
    @State private var following = Set<String>()
    @State private var personalObservation: NativeObservation?

    var body: some View {
        DiscoveryPage {
            if let state {
                if !state.activeEvents.isEmpty {
                    eventBanner(state.activeEvents[min(eventIndex, state.activeEvents.count - 1)])
                }
                listMode(state)
                TextField(context.text("전시, 작가, 갤러리 검색", "Search exhibitions, artists, galleries"), text: Binding(
                    get: { search },
                    set: {
                        search = $0
                        app.tabs.setSearchQuery(query: $0)
                    }
                ))
                    .font(context.font(.body))
                    .padding(12)
                    .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityIdentifier("list.search")
                locationFilters(state)
                exhibitionFilters(state)
                if !state.showMyListOnly, state.selectedCity != nil, let promotion = state.promotedExhibition,
                   let exhibition = app.findExhibition(id: promotion.exhibitionId) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(context.text("광고", "Sponsored")).font(context.font(.caption, weight: .semibold))
                        DiscoveryExhibitionCard(
                            exhibition: exhibition, context: context, app: app,
                            open: { navigation.push(.exhibition(exhibition.id)) },
                            permitsBookmark: false, visited: visited.contains(exhibition.id)
                        )
                    }
                }
                if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !state.showMyListOnly {
                    galleryResults()
                }
                results(state)
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 240)
            }
        }
        .discoveryStyle(language: context.language)
        .refreshable { app.tabs.refresh() }
        .task(id: EventCycle(count: state?.activeEvents.count ?? 0, paused: reduceMotion || voiceOver || !surfaceVisible)) {
            let cycle = EventCycle(count: state?.activeEvents.count ?? 0, paused: reduceMotion || voiceOver || !surfaceVisible)
            guard cycle.count > 1, !cycle.paused else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(4)) } catch { return }
                eventIndex = (eventIndex + 1) % cycle.count
            }
        }
        .onAppear {
            context.connect(app)
            if surfaceVisible { app.recordSurface(surface: .list, entryPoint: .tab) }
            observation = app.observeList {
                if state == nil { search = $0.searchQuery }
                state = $0
            }
            personalObservation = app.observeMyGallr {
                visited = Set($0.visits.map(\.exhibitionId))
                following = Set($0.followedGalleryRecords.flatMap { gallery in
                    [gallery.galleryKey, gallery.galleryId].compactMap { $0 }
                })
            }
        }
        .onChange(of: surfaceVisible) { visible in
            if visible { app.recordSurface(surface: .list, entryPoint: .tab) }
        }
        .onDisappear {
            observation?.cancel()
            personalObservation?.cancel()
            context.disconnect()
        }
        .confirmationDialog(context.text("저장한 전시를 모두 지울까요?", "Remove all saved exhibitions?"), isPresented: $confirmClear) {
            Button(context.text("모두 지우기", "Remove all"), role: .destructive) { app.tabs.clearAllBookmarks() }
        }
    }

    private struct EventCycle: Equatable {
        let count: Int
        let paused: Bool
    }

    private func eventBanner(_ event: Event) -> some View {
        Button {
            navigation.push(.event(event.id))
        } label: {
            HStack(spacing: 8) {
                Text(event.localizedName(lang: context.language)).font(context.font(.subheadline, weight: .semibold))
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.right").font(context.font(.caption))
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private func listMode(_ state: ListScreenUiState) -> some View {
        HStack(spacing: 8) {
            chip(context.text("전체 전시", "All exhibitions"), selected: !state.showMyListOnly) {
                app.tabs.setShowMyListOnly(enabled: false)
            }
            .accessibilityIdentifier("list.mode.all")
            chip(context.text("내 전시", "My list"), selected: state.showMyListOnly) {
                app.tabs.setShowMyListOnly(enabled: true)
            }
            .accessibilityIdentifier("list.mode.saved")
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private func locationFilters(_ state: ListScreenUiState) -> some View {
        if !state.cities.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Menu {
                        Text(context.text("대한민국", "South Korea"))
                    } label: {
                        Label(context.text("대한민국", "South Korea"), systemImage: "chevron.down")
                            .font(context.font(.subheadline)).frame(minHeight: 44)
                    }
                    chip(context.text("모든 도시", "All cities"), selected: state.selectedCity == nil) {
                        app.tabs.setCity(cityKo: nil)
                    }
                    .accessibilityIdentifier("list.filter.all-cities")
                    ForEach(state.cities, id: \.cityKo) { city in
                        chip("\(context.text(city.cityKo, city.cityEn.isEmpty ? city.cityKo : city.cityEn)) (\(city.count))", selected: state.selectedCity == city.cityKo) {
                            app.tabs.setCity(cityKo: city.cityKo)
                        }
                    }
                }
            }
        }
        if state.selectedCity != nil, !state.regions.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(context.text("모든 지역", "All districts"), selected: state.filter.regions.isEmpty) {
                        app.tabs.clearRegions()
                    }
                    ForEach(state.regions, id: \.regionKo) { region in
                        chip("\(context.text(region.regionKo, region.regionEn.isEmpty ? region.regionKo : region.regionEn)) (\(region.count))", selected: state.filter.regions.contains(region.regionKo)) {
                            app.tabs.toggleRegion(regionKo: region.regionKo)
                        }
                    }
                }
            }
        }
    }

    private func exhibitionFilters(_ state: ListScreenUiState) -> some View {
        let filter = state.filter
        return VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(context.text("에디터", "Editors"), selected: false) { navigation.push(.editors) }
                    ForEach(state.activeEvents, id: \.id) { event in
                        chip(event.shortLabel ?? event.localizedName(lang: context.language), selected: filter.selectedEventId == event.id) {
                            app.tabs.toggleEventFilter(eventId: event.id)
                        }
                    }
                    chip(context.text("추천", "Featured"), selected: filter.showFeatured) {
                        app.setListFilters(showFeatured: !filter.showFeatured, openingThisWeek: filter.openingThisWeek, closingThisWeek: filter.closingThisWeek)
                    }
                    chip(context.text("이번 주 시작", "Opening this week"), selected: filter.openingThisWeek) {
                        app.setListFilters(showFeatured: filter.showFeatured, openingThisWeek: !filter.openingThisWeek, closingThisWeek: filter.closingThisWeek)
                    }
                    chip(context.text("이번 주 종료", "Closing this week"), selected: filter.closingThisWeek) {
                        app.setListFilters(showFeatured: filter.showFeatured, openingThisWeek: filter.openingThisWeek, closingThisWeek: !filter.closingThisWeek)
                    }
                    .accessibilityIdentifier("list.filter.closing")
                }
            }
            .accessibilityIdentifier("list.filters")
            HStack {
                Text(context.text("전시 \(state.tabExhibitionCount)개", "\(state.tabExhibitionCount) exhibitions"))
                    .font(context.font(.caption))
                    .foregroundStyle(GallrStyle.secondary)
                Spacer()
                if state.selectedCity != nil || filter.showFeatured || filter.openingThisWeek ||
                    filter.closingThisWeek || filter.selectedEventId != nil || !filter.regions.isEmpty {
                    Button(context.text("필터 초기화", "Clear filters")) { app.tabs.clearAllFilters() }
                        .font(context.font(.caption))
                        .frame(minHeight: 44)
                }
                if state.showMyListOnly && !state.bookmarkedIds.isEmpty {
                    Button(context.text("모두 지우기", "Clear saved")) { confirmClear = true }
                        .font(context.font(.caption))
                        .frame(minHeight: 44)
                }
            }
        }
    }

    @ViewBuilder
    private func galleryResults() -> some View {
        let matches = app.searchGalleries(query: search)
        if !matches.isEmpty {
            DiscoverySectionTitle(title: context.text("갤러리", "Galleries"))
            ForEach(matches, id: \.candidate.galleryKey) { result in
                let candidate = result.candidate
                HStack(spacing: 12) {
                    Button {
                        if let id = candidate.exhibitions.first?.id { navigation.push(.gallery(id)) }
                    } label: {
                        HStack(spacing: 12) {
                            // Square by design: gallery avatars.
                            DiscoveryArtwork(url: candidate.profileImageUrl).frame(width: 44, height: 44)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(candidate.snapshot.localizedName(lang: context.language))
                                    .font(context.font(.headline))
                                Text(context.text("\(result.visitedCount)회 방문", "\(result.visitedCount) visited"))
                                    .font(context.font(.caption))
                                    .foregroundStyle(GallrStyle.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    if !following.contains(candidate.galleryKey), candidate.galleryId.map({ following.contains($0) }) != true {
                        Button(context.text("팔로우", "Follow")) { app.followGallery(candidate: candidate) }
                            .buttonStyle(DiscoveryButtonStyle())
                    }
                }
                Divider()
            }
        }
    }

    @ViewBuilder
    private func results(_ state: ListScreenUiState) -> some View {
        if let available = state.exhibitions as? ExhibitionListState.Success {
            if available.exhibitions.isEmpty {
                let hasNoSaves = state.showMyListOnly && state.bookmarkedIds.isEmpty
                DiscoveryMessage(
                    title: hasNoSaves
                        ? context.text("저장한 전시가 없어요.", "No saved exhibitions yet.")
                        : context.text("조건에 맞는 전시가 없어요.", "No exhibitions match these filters."),
                    actionTitle: hasNoSaves ? nil : context.text("필터 초기화", "Clear filters"),
                    action: {
                        search = ""
                        app.tabs.setSearchQuery(query: "")
                        app.tabs.clearAllFilters()
                    }
                )
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 276), spacing: 12)], spacing: 16) {
                    ForEach(Array(available.exhibitions.enumerated()), id: \.element.id) { index, exhibition in
                        card(exhibition, index: index)
                    }
                }
            }
        } else if state.exhibitions is ExhibitionListState.Error {
            DiscoveryMessage(
                title: context.text("전시를 불러올 수 없어요.", "Couldn’t load exhibitions."),
                actionTitle: context.text("다시 시도", "Retry"),
                action: app.tabs.loadAllExhibitions
            )
        } else {
            ProgressView().frame(maxWidth: .infinity, minHeight: 200)
        }
    }

    private func card(_ exhibition: Exhibition, index: Int) -> some View {
        let kind: DiscoveryKind
        if !search.isEmpty { kind = .search }
        else if state?.showMyListOnly == true { kind = .saved }
        else if state?.filter.selectedEventId != nil { kind = .event }
        else if state?.filter.showFeatured == true { kind = .featured }
        else { kind = .organic }
        let attribution = DiscoveryCardAttribution(kind, index: index)
        return DiscoveryExhibitionCard(
            exhibition: exhibition, context: context, app: app,
            open: {
                app.recordExhibitionOpened(id: exhibition.id, surface: .list, kind: kind, position: attribution.position)
                navigation.push(.exhibition(exhibition.id))
            },
            attribution: attribution, visited: visited.contains(exhibition.id)
        )
    }

    private func chip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(text, action: action)
            .buttonStyle(DiscoveryButtonStyle(prominent: selected))
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
