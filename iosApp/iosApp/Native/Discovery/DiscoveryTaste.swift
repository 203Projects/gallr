import SwiftUI
import composeApp

struct DiscoveryRecommendations: View {
    let app: NativeGallrApp
    @EnvironmentObject private var navigation: GallrNavigation
    @StateObject private var context = DiscoveryContext()
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @State private var state: RecommendationUiState?
    @State private var taste: TastePickSheetState?
    @State private var observations: [NativeObservation] = []
    @State private var dismissed: String?
    @State private var lastRun: Int64?

    var body: some View {
        DiscoveryPage(title: context.text("내 취향", "For you")) {
            HStack {
                Button(context.text("취향 편집", "Edit taste")) { app.tastePick.open(mode: .edit) }
                    .buttonStyle(DiscoveryButtonStyle())
                Spacer()
            }
            if let ready = state as? RecommendationUiStateReady, !ready.items.isEmpty {
                if !ready.tasteTerms.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(ready.tasteTerms, id: \.id) { term in
                                Button(term.localizedName(language: context.language)) { app.tastePick.open(mode: .edit) }
                                    .buttonStyle(DiscoveryButtonStyle())
                            }
                        }
                    }
                }
                let ranked = Array(ready.items.enumerated())
                let personal = ranked.filter { $0.element.hasPersonalEvidence }
                let editorial = ranked.filter { !$0.element.hasPersonalEvidence }
                if !personal.isEmpty {
                    DiscoverySectionTitle(title: context.text("취향에 맞는 전시", "Picks for your taste"))
                    recommendationGrid(personal)
                }
                if !editorial.isEmpty {
                    DiscoverySectionTitle(title: context.text("에디터 추천 전시", "Featured picks"))
                    recommendationGrid(editorial)
                }
            } else if state is RecommendationUiStateLoading || state == nil {
                ProgressView().frame(maxWidth: .infinity, minHeight: 160)
            } else if state is RecommendationUiStateError {
                DiscoveryMessage(
                    title: context.text("추천을 불러올 수 없어요.", "Couldn’t load recommendations."),
                    actionTitle: context.text("다시 시도", "Retry"),
                    action: app.localDiscovery.retryRecommendations
                )
            } else {
                DiscoveryMessage(
                    title: context.text("좋아하는 전시를 골라보세요.", "Pick the art you like."),
                    actionTitle: context.text("취향 고르기", "Choose your taste"),
                    action: { app.tastePick.open(mode: .edit) }
                )
            }
        }
        .discoveryStyle(language: context.language)
        .environment(\.gallrSurfaceVisible, surfaceVisible && taste == nil)
        .refreshable { app.localDiscovery.refreshRecommendations() }
        .overlay(alignment: .bottom) {
            if let id = dismissed {
                DiscoveryUndo(context: context) {
                    app.localDiscovery.undoDismissal(exhibitionId: id)
                    dismissed = nil
                }
                .padding(16)
            }
        }
        .sheet(isPresented: Binding(get: { surfaceVisible && taste != nil }, set: { if !$0 { app.tastePick.close() } })) {
            if let taste { DiscoveryTasteSheet(app: app, context: context, state: taste) }
        }
        .onAppear {
            context.connect(app)
            observations = [
                app.observeRecommendations {
                    state = $0
                    if surfaceVisible { recordRecommendationRun($0) }
                },
                app.observeTastePick { taste = $0 },
            ]
            app.localDiscovery.refreshRecommendations()
        }
        .onChange(of: surfaceVisible) { visible in if visible, let state { recordRecommendationRun(state) } }
        .onDisappear {
            observations.forEach { $0.cancel() }
            observations.removeAll()
            context.disconnect()
        }
    }

    private func recommendationGrid(_ ranked: [(offset: Int, element: ExhibitionRecommendation)]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 270), spacing: 12)], spacing: 16) {
            ForEach(ranked, id: \.element.exhibition.id) { index, item in
                recommendationCard(item, index: index)
            }
        }
    }

    private func recordRecommendationRun(_ state: RecommendationUiState) {
        guard let ready = state as? RecommendationUiStateReady, lastRun != ready.runId else { return }
        lastRun = ready.runId
        app.recordRecommendationFeedShown(count: Int32(ready.items.count))
    }

    private func recommendationCard(_ recommendation: ExhibitionRecommendation, index: Int) -> some View {
        DiscoveryRecommendationCard(
            app: app, context: context, recommendation: recommendation, index: index, dismissed: dismissed,
            onDismissed: { dismissed = $0 }
        )
    }
}

struct DiscoveryRecommendationCard: View {
    let app: NativeGallrApp
    @ObservedObject var context: DiscoveryContext
    let recommendation: ExhibitionRecommendation
    let index: Int
    let dismissed: String?
    let onDismissed: (String) -> Void
    @EnvironmentObject private var navigation: GallrNavigation

    var body: some View {
        let exhibition = recommendation.exhibition
        let attribution = DiscoveryCardAttribution(.recommendation, index: index)
        let reasonEvidence = recommendation.hasPersonalEvidence ? recommendation.evidence : recommendation.evidence.filter {
            !($0 is RecommendationEvidenceFeatured)
        }
        let visibleReason = recommendation.doCopy(
            exhibition: exhibition, scoreBasisPoints: recommendation.scoreBasisPoints, evidence: reasonEvidence
        )
        DiscoveryExhibitionCard(
            exhibition: exhibition, context: context, app: app,
            open: {
                app.recordExhibitionOpened(id: exhibition.id, surface: .featured, kind: .recommendation, position: attribution.position)
                navigation.push(.recommendationExhibition(exhibition.id))
            },
            reason: app.recommendationReason(recommendation: visibleReason, language: context.language),
            surface: .featured, attribution: attribution
        )
        .opacity(dismissed == exhibition.id ? 0.42 : 1)
        .overlay(alignment: .topLeading) {
            Menu {
                Button(context.text("이런 전시 덜 보기", "See fewer like this")) {
                    app.localDiscovery.dismiss(exhibition: exhibition)
                    onDismissed(exhibition.id)
                }
                Button(context.text("공유", "Share")) { navigation.push(.recommendationShare(exhibition.id)) }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 12))
            }
            .accessibilityLabel(context.text("전시 옵션", "Exhibition options"))
            .padding(8)
        }
    }
}

struct DiscoveryTasteSheet: View {
    let app: NativeGallrApp
    @ObservedObject var context: DiscoveryContext
    let state: TastePickSheetState
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        DiscoveryPage(title: context.text("내 취향 고르기", "Choose your taste")) {
            Text(context.text("좋아하는 키워드와 전시를 골라주세요.", "Choose the terms and exhibition covers that speak to you."))
                .foregroundStyle(GallrStyle.secondary)
            if !state.terms.isEmpty {
                DiscoverySectionTitle(title: context.text("키워드", "Terms"))
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8)], alignment: .leading, spacing: 8) {
                    ForEach(state.terms, id: \.id) { term in
                        let selected = state.selectedTermIds.contains(term.id)
                        Button(term.localizedName(language: context.language)) { app.tastePick.toggleTerm(termId: term.id) }
                            .buttonStyle(DiscoveryButtonStyle(prominent: selected))
                            .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
            }
            if !state.covers.isEmpty {
                DiscoverySectionTitle(title: context.text("전시", "Exhibitions"))
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8)], spacing: 8) {
                    ForEach(state.covers, id: \.id) { exhibition in
                        let selected = state.selectedExhibitionIds.contains(exhibition.id)
                        Button {
                            app.tastePick.toggleCover(exhibitionId: exhibition.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                DiscoveryArtwork(url: exhibition.coverImageUrl).aspectRatio(0.8, contentMode: .fit)
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                Text(exhibition.localizedName(lang: context.language)).font(context.font(.caption)).lineLimit(typeSize.isAccessibilitySize ? nil : 2)
                            }
                            .padding(4)
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(
                                selected ? GallrStyle.foreground : .clear, lineWidth: 2
                            ))
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
            }
            if !state.seeingLess.isEmpty {
                DiscoverySectionTitle(title: context.text("덜 보는 키워드", "Seeing less"))
                ForEach(state.seeingLess, id: \.id) { term in
                    Button {
                        app.tastePick.releaseTerm(termId: term.id)
                    } label: {
                        Label(term.localizedName(language: context.language), systemImage: "minus")
                    }
                    .buttonStyle(DiscoveryButtonStyle())
                }
            }
        }
        .discoveryStyle(language: context.language)
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 8) {
                if state.mode != .edit {
                    Button(context.text("건너뛰기", "Skip")) { app.tastePick.skip() }
                        .buttonStyle(DiscoveryButtonStyle())
                } else {
                    Button(context.text("닫기", "Close")) { app.tastePick.close() }
                        .buttonStyle(DiscoveryButtonStyle())
                }
                Button(state.remaining > 0
                    ? context.text("\(state.remaining)개 더 고르기", "Choose \(state.remaining) more")
                    : context.text("저장", "Save")) {
                    app.tastePick.confirm()
                }
                .buttonStyle(DiscoveryButtonStyle(prominent: true))
                .disabled(!state.canConfirm)
                .opacity(state.canConfirm ? 1 : 0.55)
            }
            .padding(12)
            .frame(maxWidth: .infinity)
            .background(GallrStyle.background)
        }
    }
}
