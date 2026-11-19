import MapKit
import SwiftUI
import composeApp

struct NativePublicRouteBrowse: View {
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    let app: NativeGallrApp
    @State private var state: PublicRouteBrowseUiState?
    @State private var observation: NativeObservation?

    var body: some View {
        RoutePage(title: korean ? "동선 둘러보기" : "BROWSE ROUTES") {
            if let state {
                filters(state)
                list(state.list)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            guard observation == nil else { return }
            observation = app.observePublicRouteBrowse { state = $0 }
            app.publicRouteBrowse.open()
        }
        .onDisappear {
            observation?.cancel()
            observation = nil
        }
        .accessibilityIdentifier("native-route-browse")
    }

    @ViewBuilder
    private func filters(_ state: PublicRouteBrowseUiState) -> some View {
        HStack(spacing: 8) {
            RouteChip(title: korean ? "인기" : "POPULAR", selected: state.query.sort == .popular) {
                app.publicRouteBrowse.selectSort(sort: .popular)
            }
            RouteChip(title: korean ? "최신" : "NEWEST", selected: state.query.sort == .newest) {
                app.publicRouteBrowse.selectSort(sort: .newest)
            }
        }
        if !state.districts.isEmpty {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    RouteChip(title: korean ? "전체" : "ALL", selected: state.query.districtKo == nil) {
                        app.publicRouteBrowse.clearDistrict()
                    }
                    ForEach(state.districts, id: \.nameKo) { district in
                        RouteChip(
                            title: district.localizedName(lang: korean ? .ko : .en),
                            selected: state.query.districtKo == district.nameKo
                        ) {
                            app.publicRouteBrowse.selectDistrict(districtKo: district.nameKo)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func list(_ list: any BrowseListState) -> some View {
        switch list {
        case let loaded as BrowseListStateLoaded:
            LazyVStack(spacing: 8) {
                ForEach(loaded.routes, id: \.summary.id) { row in
                    PublicRouteRow(row: row) {
                        app.selectPublicRoute(summary: row.summary)
                        navigation.push(.publicRoute(row.summary.id))
                    }
                }
                if loaded.appending {
                    ProgressView()
                } else if loaded.appendFailed {
                    RouteAction(title: korean ? "다시 시도" : "TRY AGAIN", action: app.publicRouteBrowse.retryMore)
                } else if loaded.hasMore {
                    RouteAction(title: korean ? "더 보기" : "LOAD MORE", action: app.publicRouteBrowse.loadMore)
                        .onAppear(perform: app.publicRouteBrowse.loadMore)
                }
            }
        case let empty as BrowseListStateEmpty:
            RouteInfo(text: korean ? "표시할 동선이 없어요." : "No routes to show.")
            if empty.filtered {
                RouteAction(title: korean ? "필터 지우기" : "CLEAR FILTER", action: app.publicRouteBrowse.clearDistrict)
            } else {
                RouteAction(title: korean ? "내 동선 만들기" : "MAKE A ROUTE") { navigation.push(.routeComposer) }
            }
        case is BrowseListStateFirstPageError:
            RouteInfo(text: korean ? "동선을 불러오지 못했어요." : "Could not load routes.")
            RouteAction(title: korean ? "다시 시도" : "TRY AGAIN", action: app.publicRouteBrowse.retry)
        default:
            ProgressView()
        }
    }
}

private struct PublicRouteRow: View {
    @Environment(\.gallrKorean) private var korean
    let row: BrowsedPublicRoute
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                if !row.coverUrls.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(Array(row.coverUrls.prefix(3).enumerated()), id: \.offset) { _, cover in
                            AsyncImage(url: URL(string: cover)) { image in
                                image.resizable().scaledToFill()
                            } placeholder: {
                                GallrStyle.surface
                            }
                            .frame(width: 42, height: 54)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                    }
                    .accessibilityHidden(true)
                }
                Text(row.summary.name).font(.headline)
                Text(metadata).font(.caption).foregroundStyle(GallrStyle.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    private var metadata: String {
        let first = korean || row.summary.firstDistrictEn.isEmpty ? row.summary.firstDistrictKo : row.summary.firstDistrictEn
        let last = korean || row.summary.lastDistrictEn.isEmpty ? row.summary.lastDistrictKo : row.summary.lastDistrictEn
        let count = korean ? "\(row.summary.stopCount)곳" : "\(row.summary.stopCount) stops"
        return [count, first == last ? first : "\(first) → \(last)", row.summary.authorDisplayName]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

struct NativePublicRoutePreview: View {
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    let app: NativeGallrApp
    let routeId: String
    @State private var preview: PublicRoutePreview?
    @State private var myRoutes: MyRoutesUiState?
    @State private var observations: [NativeObservation] = []
    @State private var showVisitTime = false
    @State private var model: PublicRoutesViewModel?
    @State private var unavailable = false

    var body: some View {
        RoutePage(title: preview?.summary.name ?? (korean ? "전시 동선" : "EXHIBITION ROUTE")) {
            if let preview {
                if let route = preview.route {
                    ready(preview, route: route)
                } else if preview.loading {
                    ProgressView()
                } else {
                    RouteInfo(text: preview.notice == .noLongerListed
                        ? (korean ? "더 이상 볼 수 없는 동선이에요." : "This route is no longer listed.")
                        : (korean ? "동선을 불러오지 못했어요." : "Could not load this route."))
                    if preview.notice == .loadFailed {
                        RouteAction(title: korean ? "다시 시도" : "TRY AGAIN") {
                            model?.openPreview(summary: preview.summary)
                        }
                    }
                }
            } else if unavailable {
                RouteInfo(text: korean ? "동선을 불러오지 못했어요." : "Could not load this route.")
            } else {
                ProgressView()
            }
        }
        .accessibilityIdentifier("native-route-preview")
        .onAppear(perform: connect)
        .onChange(of: surfaceVisible) { visible in
            if visible, let preview, let model { handle(preview, model: model) }
        }
        .onDisappear {
            observations.forEach { $0.cancel() }
            observations.removeAll()
            if let model { app.releaseViewModel(viewModel: model) }
            model = nil
            app.myRoutes.previewLeft()
        }
        .sheet(isPresented: $showVisitTime) {
            if let preview, let options = preview.visitTimeOptions {
                RouteVisitTimeSheet(
                    options: options,
                    selection: preview.visitTime,
                    onSelection: { model?.chooseVisitTime(visitTime: $0) }
                )
            }
        }
        .confirmationDialog(
            korean ? "작성 중인 동선을 바꿀까요?" : "Replace your current draft?",
            isPresented: Binding(
                get: { preview?.confirmReplace == true || myRoutes?.confirmOpen != nil },
                set: { if !$0 { model?.cancelReplace(); app.myRoutes.dismissDialogs() } }
            ),
            titleVisibility: .visible
        ) {
            Button(korean ? "현재 초안 바꾸기" : "REPLACE DRAFT", role: .destructive) {
                if preview?.confirmReplace == true {
                    model?.confirmReplace()
                } else {
                    app.myRoutes.confirmOpen()
                }
            }
            Button(korean ? "이어서 편집" : "KEEP EDITING") { navigation.push(.routeComposer) }
        }
        .confirmationDialog(
            korean ? "동선 신고" : "REPORT ROUTE",
            isPresented: Binding(
                get: { preview?.reportSheet == true },
                set: { if !$0 { model?.closeReport() } }
            ),
            titleVisibility: .visible
        ) {
            Button(korean ? "부적절한 콘텐츠" : "INAPPROPRIATE") { model?.report(reason: .inappropriate) }
            Button(korean ? "홍보 콘텐츠" : "PROMOTIONAL") { model?.report(reason: .promotional) }
            Button(korean ? "잘못된 정보" : "WRONG INFORMATION") { model?.report(reason: .wronginformation) }
            Button(korean ? "기타" : "OTHER") { model?.report(reason: .other) }
        }
    }

    @ViewBuilder
    private func ready(_ preview: PublicRoutePreview, route: PersonalRoute) -> some View {
        if let author = preview.summary.authorDisplayName {
            RouteInfo(text: author)
        }
        if let day = preview.laterDay {
            RouteInfo(text: korean ? "\(day)부터 관람할 수 있어요." : "All stops open from \(day).")
        }
        if preview.visitTimeOptions != nil {
            RouteAction(title: routeVisitTimeLabel(preview.visitTime, korean: korean)) { showVisitTime = true }
        }
        if let evaluation = preview.evaluation {
            RouteInfo(
                text: "\(routeDistance(evaluation.totalDistanceMeters)) · \(evaluation.travelMinutes) \(korean ? "분 도보" : "min walk")"
            )
            NativeRouteMapPanel(
                points: route.stops.map { routeMapPoint($0, covers: preview.stopCovers, korean: korean) },
                legs: evaluation.legs
            )
        }
        ForEach(Array(route.stops.enumerated()), id: \.element.exhibitionId) { index, stop in
            let verdict = preview.evaluation?.stops.first { $0.stop.exhibitionId == stop.exhibitionId }?.verdict
            RouteStopRow(
                position: index + 1,
                title: routeStopTitle(stop, korean: korean),
                subtitle: routeStopVenue(stop, korean: korean),
                cover: preview.stopCovers[stop.exhibitionId],
                note: verdict.map { routeVerdictLabel($0, korean: korean) },
                open: { navigation.push(.exhibition(stop.exhibitionId)) }
            )
        }
        if preview.isOwn {
            RouteAction(title: korean ? "내 동선에서 열기" : "OPEN IN MY ROUTES", primary: true) {
                app.myRoutes.openOwn(routeId: preview.summary.id)
            }
        } else {
            RouteAction(title: korean ? "내 동선으로 복사" : "COPY TO MY ROUTES", primary: true, action: { model?.doCopy() })
                .disabled(preview.copyBusy)
            RouteAction(title: korean ? "신고하기" : "REPORT", action: { model?.startReport() })
                .disabled(preview.reportBusy || preview.reported)
        }
        if let message = preview.message {
            if message == .reported {
                RouteInfo(text: korean ? "신고했어요." : "Route reported.")
            } else if message == .draftChanged {
                RouteInfo(text: korean ? "초안이 바뀌었어요. 다시 복사해 주세요." : "The draft changed. Try copying again.")
            } else {
                RouteInfo(text: korean ? "요청을 완료하지 못했어요." : "The request could not be completed.")
                RouteAction(title: korean ? "다시 시도" : "TRY AGAIN", action: { model?.retryMessage() })
            }
        }
        if myRoutes?.previewOpenFailed == routeId {
            RouteInfo(text: korean ? "내 동선을 열지 못했어요." : "Your route could not be opened.")
        }
    }

    private func connect() {
        guard observations.isEmpty else { return }
        guard let newModel = app.publicRoutePreview(id: routeId) else {
            unavailable = true
            return
        }
        model = newModel
        observations = [
            app.observePublicRoutePreview(viewModel: newModel) { state in
                guard let current = state.preview, current.summary.id == routeId else { return }
                preview = current
                handle(current, model: newModel)
            },
            app.observeMyRoutes { state in
                myRoutes = state
                if navigation.trails[navigation.selected]?.last?.route == .publicRoute(routeId),
                   state.openComposer, state.previewOpenFailed == nil {
                    app.myRoutes.onComposerOpened()
                    navigation.push(.routeComposer)
                }
            },
        ]
    }
    private func handle(_ current: PublicRoutePreview, model: PublicRoutesViewModel) {
        guard navigation.trails[navigation.selected]?.last?.route == .publicRoute(routeId) else { return }
        if current.signInRequested {
            model.onSignInRequestHandled()
            navigation.push(.promptedSignIn)
        }
        if let draft = current.copiedDraftId {
            model.onCopyOpened()
            app.routeComposer.noteCopied(draftId: draft)
            navigation.push(.routeComposer)
        }
    }

}
