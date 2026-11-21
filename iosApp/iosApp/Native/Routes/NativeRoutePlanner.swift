import SwiftUI
import composeApp

struct NativeRoutePlanner: View {
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    let app: NativeGallrApp
    let origin: GeoPoint
    let mode: String
    @State private var state: (any RouteUiState)?
    @State private var routeComposer: RouteComposerUiState?
    @State private var publicRoutes: PublicRoutesUiState?
    @State private var myRoutes: MyRoutesUiState?
    @State private var observations: [NativeObservation] = []
    @State private var requestID = Int64.random(in: 1...Int64.max)
    @State private var showReplacePrompt = false
    @State private var mapFailed = false

    private var language: AppLanguage { korean ? .ko : .en }

    var body: some View {
        RoutePage(title: korean ? "전시 동선" : "EXHIBITION ROUTE") {
            if let request {
                picker(request)
                RouteAction(title: korean ? "동선 만들기" : "BUILD ROUTE", primary: true) {
                    mapFailed = false
                    app.localDiscovery.buildRoute()
                }
                .disabled(state is RouteUiStatePlanning)
                .accessibilityIdentifier("native-route-build")
                result
                RouteAction(title: korean ? "내 동선" : "MY ROUTES") { navigation.push(.myRoutes) }
                RouteAction(title: korean ? "동선 둘러보기" : "BROWSE ROUTES") { navigation.push(.publicRoutes) }
                if let rows = publicRoutes?.visibleRows, !rows.isEmpty {
                    ForEach(rows, id: \.id) { summary in
                        RouteAction(title: summary.name) {
                            app.selectPublicRoute(summary: summary)
                            navigation.push(.publicRoute(summary.id))
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .accessibilityIdentifier("native-route-planner")
        .onAppear(perform: connect)
        .onDisappear {
            observations.forEach { $0.cancel() }
            observations = []
        }
        .confirmationDialog(
            korean ? "작성 중인 동선을 바꿀까요?" : "Replace your current draft?",
            isPresented: $showReplacePrompt,
            titleVisibility: .visible
        ) {
            Button(korean ? "현재 초안 바꾸기" : "REPLACE DRAFT", role: .destructive, action: copyRoute)
            Button(korean ? "이어서 편집" : "KEEP EDITING") { navigation.push(.routeComposer) }
        }
    }

    @ViewBuilder
    private func picker(_ request: RoutePlanningRequest) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(RouteCurationMode.entries, id: \.name) { entry in
                    RouteChip(title: label(entry), selected: request.mode == entry) {
                        mapFailed = false
                        app.localDiscovery.setRouteMode(mode: entry)
                    }
                }
            }
        }
        HStack(spacing: 8) {
            ForEach(2...5, id: \.self) { index in
                RouteChip(
                    title: korean ? "\(index)곳" : "\(index) STOPS",
                    selected: request.stopCount == index
                ) {
                    mapFailed = false
                    app.localDiscovery.setStopCount(stopCount: Int32(index))
                }
            }
        }
    }

    @ViewBuilder
    private var result: some View {
        if let ready = state as? RouteUiStateReady {
            routeResult(ready)
        } else if let insufficient = state as? RouteUiStateInsufficient {
            RouteInfo(text: korean ? "선택한 조건에 맞는 전시가 \(insufficient.available)곳 있어요." : "\(insufficient.available) exhibitions match these options.")
            if insufficient.request.stopCount > 2 {
                RouteAction(title: korean ? "전시 수 줄이기" : "TRY FEWER STOPS") {
                    app.localDiscovery.setStopCount(stopCount: insufficient.request.stopCount - 1)
                    app.localDiscovery.buildRoute()
                }
            }
        } else if state is RouteUiStateError {
            RouteInfo(text: korean ? "동선을 만들지 못했어요." : "The route could not be built.")
        } else if state is RouteUiStatePlanning {
            ProgressView()
        }
    }

    @ViewBuilder
    private func routeResult(_ ready: RouteUiStateReady) -> some View {
        let estimate = ready.estimate
        RouteInfo(text: "\(routeDistance(estimate.totalDistanceMeters)) · \(estimate.estimatedTotalMinutes) \(korean ? "분" : "min")")
        NativeRouteMapPanel(
            points: estimate.stops.compactMap { exhibition in
                guard let latitude = exhibition.latitude?.doubleValue,
                      let longitude = exhibition.longitude?.doubleValue else { return nil }
                return RouteMapPoint(
                    point: GeoPoint(latitude: latitude, longitude: longitude),
                    title: exhibition.localizedName(lang: language),
                    cover: exhibition.coverImageUrl
                )
            },
            legs: estimate.legs,
            origin: ready.request.origin
        )
        if let first = estimate.stops.first {
            RouteAction(
                title: ready.hasStarted ? (korean ? "지도 열기" : "OPEN MAP") : (korean ? "동선 시작" : "START ROUTE"),
                primary: true
            ) {
                mapFailed = !app.openPlannerStop(exhibition: first, start: true)
            }
        }
        if mapFailed {
            RouteInfo(text: korean ? "지도를 열지 못했어요." : "The map could not be opened.")
        }
        ForEach(Array(estimate.stops.enumerated()), id: \.element.id) { index, exhibition in
            let schedule = estimate.stopSchedules.first { $0.exhibitionId == exhibition.id }
            RouteStopRow(
                position: index + 1,
                title: exhibition.localizedName(lang: language),
                subtitle: exhibition.localizedVenueName(lang: language),
                cover: exhibition.coverImageUrl,
                note: schedule.map { "\($0.visitStart)–\($0.visitEnd)" },
                open: { navigation.push(.exhibition(exhibition.id)) }
            )
            RouteChip(title: korean ? "이 전시 길찾기" : "DIRECTIONS", selected: false) {
                mapFailed = !app.openPlannerStop(exhibition: exhibition, start: false)
            }
        }
        RouteAction(title: korean ? "내 동선으로 복사" : "COPY TO MY ROUTE") {
            if routeComposer?.replacingNeedsConfirmation == true {
                showReplacePrompt = true
            } else {
                copyRoute()
            }
        }
    }

    private var request: RoutePlanningRequest? {
        switch state {
        case let value as RouteUiStateEditing: return value.request
        case let value as RouteUiStatePlanning: return value.request
        case let value as RouteUiStateReady: return value.request
        case let value as RouteUiStateInsufficient: return value.request
        case let value as RouteUiStateError: return value.request
        default: return nil
        }
    }

    private func connect() {
        guard observations.isEmpty else { return }
        let initial = RouteCurationMode.entries.first { $0.name.caseInsensitiveCompare(mode) == .orderedSame } ?? .neighborhood
        app.localDiscovery.beginRouteIfNeeded(origin: origin, initialMode: initial, requestId: requestID)
        app.publicRoutes.sheetShown()
        app.myRoutes.sectionShown()
        observations = [
            app.observePlanner { state = $0 },
            app.observeRouteComposer { routeComposer = $0 },
            app.observeRoutes { publicRoutes = $0 },
            app.observeMyRoutes { myRoutes = $0 },
        ]
    }

    private func copyRoute() {
        guard let ready = state as? RouteUiStateReady else { return }
        app.routeComposer.startFromPlanner(exhibitions: ready.estimate.stops)
        navigation.push(.routeComposer)
    }

    private func label(_ mode: RouteCurationMode) -> String {
        if mode == .forYou { return korean ? "취향에 맞게" : "FOR YOU" }
        if mode == .closingSoon { return korean ? "종료 임박" : "CLOSING SOON" }
        if mode == .saved { return korean ? "저장한 전시" : "SAVED" }
        return korean ? "동네" : "NEARBY"
    }
}
