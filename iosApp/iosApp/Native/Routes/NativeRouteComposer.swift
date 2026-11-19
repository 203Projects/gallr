import Combine
import CoreLocation
import SwiftUI
import composeApp

struct NativeRouteComposer: View {
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @Environment(\.colorScheme) private var colorScheme
    let app: NativeGallrApp
    let seed: String?
    @State private var routes: RouteComposerUiState?
    @State private var routeName = ""
    @State private var catalogue: ExhibitionListState?
    @State private var observations: [NativeObservation] = []
    @State private var seedObservation: NativeObservation?
    @State private var seedApplied = false
    @State private var sheet: ComposerSheet?
    @State private var shareItems: [Any] = []
    @StateObject private var location = RouteDeviceLocation()

    private enum ComposerSheet: String, Identifiable {
        case add, visitTime, share
        var id: Self { self }
    }

    var body: some View {
        RoutePage(title: korean ? "내 동선 만들기" : "MAKE A ROUTE") {
            if let state = routes {
                editor(state)
            } else {
                ProgressView()
            }
        }
        .accessibilityIdentifier("native-route-composer")
        .onAppear(perform: connect)
        .onChange(of: surfaceVisible) { visible in
            if visible, let state = routes { receive(state) }
        }
        .onDisappear {
            observations.forEach { $0.cancel() }
            observations.removeAll()
            seedObservation?.cancel()
            seedObservation = nil
        }
        .onReceive(location.$reading) { reading in
            app.routeComposer.updateLocation(status: reading.status, origin: reading.point)
        }
        .sheet(item: $sheet) { destination in
            switch destination {
            case .add:
                NativeRouteAddStops(
                    catalogue: catalogue,
                    today: routes?.today,
                    excludedIDs: Set(routes?.stops.map(\.exhibitionId) ?? []),
                    retry: { app.tabs.loadAllExhibitions() },
                    onAdd: { app.routeComposer.append(exhibitions: $0) }
                )
            case .visitTime:
                if let state = routes {
                    RouteVisitTimeSheet(
                        options: state.visitTimeOptions,
                        selection: state.visitTime,
                        onSelection: { app.routeComposer.chooseVisitTime(visitTime: $0) }
                    )
                }
            case .share:
                NativeRouteActivitySheet(items: shareItems)
            }
        }
    }

    @ViewBuilder
    private func editor(_ state: RouteComposerUiState) -> some View {
        TextField(
            korean ? "동선 이름" : "Route name",
            text: Binding(
                get: { routeName },
                set: {
                    routeName = $0
                    app.routeComposer.rename(name: $0)
                }
            )
        )
        .textFieldStyle(.plain)
        .font(GallrStyle.font(18, weight: .medium, korean: korean))
        .padding(12)
        .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityIdentifier("native-route-name")

        if state.shareReady {
            RouteInfo(text: korean ? "동선을 공유할 수 있어요." : "Your route is ready to share.")
        }
        if state.showNameError {
            RouteInfo(text: korean ? "동선 이름을 확인해 주세요." : "Check the route name.")
        }
        if state.copiedNote {
            RouteInfo(text: korean ? "내 동선으로 복사했어요." : "Copied to your routes.")
        }
        if state.listingWarning {
            RouteInfo(text: korean ? "수정한 동선을 저장하면 다시 검토해요." : "Saving changes sends the route back for review.")
        }
        if let status = state.saveStatus {
            RouteInfo(text: status == .saved ? (korean ? "저장됨" : "Saved") : (korean ? "저장하지 않은 변경 사항" : "Unsaved changes"))
        }

        RouteAction(title: routeVisitTimeLabel(state.visitTime, korean: korean)) {
            sheet = .visitTime
        }
        if state.showStartFromLocation {
            RouteAction(title: korean ? "현재 위치에서 출발" : "START FROM MY LOCATION") {
                location.request()
            }
        }
        if let evaluation = state.evaluation {
            evaluationSummary(evaluation)
            NativeRouteMapPanel(
                points: state.stops.map { routeMapPoint($0, covers: state.stopCovers, korean: korean) },
                legs: evaluation.legs,
                origin: state.usesDeviceOrigin ? location.reading.point : nil
            )
        }
        if state.stops.isEmpty {
            RouteInfo(text: korean ? "동선에 전시를 두 곳 이상 추가해 보세요." : "Add two or more exhibitions to your route.")
        }
        ForEach(Array(state.stops.enumerated()), id: \.element.exhibitionId) { index, stop in
            stopRow(stop, index: index, state: state)
        }

        RouteAction(title: korean ? "전시 추가" : "ADD EXHIBITIONS") { sheet = .add }
            .accessibilityIdentifier("native-route-add-stops")
        if state.message is ComposerMessageRemoved {
            RouteAction(title: korean ? "삭제 취소" : "UNDO REMOVAL", action: app.routeComposer.undo)
        } else if state.message is ComposerMessageUndoFailed {
            RouteInfo(text: korean ? "전시를 복원할 수 없어요." : "The stop could not be restored.")
        } else if state.message is ComposerMessageVisitTimePassed {
            RouteInfo(text: korean ? "방문 시간이 지나 지금부터 다시 확인해요." : "The visit time passed. The route starts now.")
        }
        if state.actionError != nil {
            RouteInfo(text: korean ? "동선을 처리하지 못했어요. 다시 시도해 주세요." : "The route could not be updated. Try again.")
            if !state.blockedStopIds.isEmpty {
                RouteAction(
                    title: korean ? "문제가 있는 전시를 빼고 저장" : "REMOVE UNAVAILABLE STOPS AND SAVE",
                    action: app.routeComposer.removeBlockedStopsAndSave
                )
            }
        }
        HStack(spacing: 8) {
            RouteAction(title: korean ? "저장" : "SAVE", primary: true, action: app.routeComposer.save)
                .disabled(!state.canSave)
                .opacity(state.canSave ? 1 : 0.5)
                .accessibilityIdentifier("native-route-save")
            RouteAction(
                title: state.isShared ? (korean ? "공유됨" : "SHARED") : (korean ? "공유" : "SHARE"),
                action: app.routeComposer.share
            )
            .disabled(!state.canShare)
            .opacity(state.canShare ? 1 : 0.5)
        }
    }

    @ViewBuilder
    private func stopRow(_ stop: PersonalRouteStop, index: Int, state: RouteComposerUiState) -> some View {
        let evaluation = state.evaluation?.stops.first { $0.stop.exhibitionId == stop.exhibitionId }
        VStack(alignment: .leading, spacing: 8) {
            RouteStopRow(
                position: index + 1,
                title: routeStopTitle(stop, korean: korean),
                subtitle: routeStopVenue(stop, korean: korean),
                cover: state.stopCovers[stop.exhibitionId],
                note: evaluation.map { routeVerdictLabel($0.verdict, korean: korean) },
                open: { navigation.push(.exhibition(stop.exhibitionId)) }
            )
            HStack(spacing: 8) {
                RouteChip(title: korean ? "위로" : "UP", selected: false) {
                    app.routeComposer.move(from: Int32(index), to: Int32(index - 1))
                }
                .disabled(index == 0)
                RouteChip(title: korean ? "아래로" : "DOWN", selected: false) {
                    app.routeComposer.move(from: Int32(index), to: Int32(index + 1))
                }
                .disabled(index + 1 == state.stops.count)
                RouteChip(title: korean ? "삭제" : "REMOVE", selected: false) {
                    app.routeComposer.remove(position: Int32(index), exhibitionId: stop.exhibitionId)
                }
            }
        }
    }

    private func connect() {
        guard observations.isEmpty else { return }
        location.readIfAuthorized()
        observations = [
            app.observeCatalogue { catalogue = $0 },
            app.observeRouteComposer(onChange: receive),
        ]
        if let seed, !seedApplied {
            seedObservation = app.resolveExhibition(id: seed) { exhibition in
                if let exhibition {
                    app.routeComposer.append(exhibitions: [exhibition])
                    seedApplied = true
                }
            }
        }
    }

    private func receive(_ state: RouteComposerUiState) {
        if routes?.draftId != state.draftId { routeName = state.name }
        routes = state
        let destination = seed.map(GallrRoute.seededRouteComposer) ?? .routeComposer
        guard navigation.trails[navigation.selected]?.last?.route == destination else { return }
        if state.signInRequested {
            app.routeComposer.onSignInRequestHandled()
            navigation.push(.promptedSignIn)
        }
        if let payload = state.sharePayload, sheet != .share {
            shareItems = routeShareItems(
                payload: payload,
                language: korean ? .ko : .en,
                walkingLegs: state.walkingLegs,
                dark: colorScheme == .dark
            )
            sheet = .share
            app.routeComposer.onShareSheetShown()
        }
    }

    private func evaluationSummary(_ evaluation: PersonalRouteEvaluation) -> some View {
        let verdict: String
        if evaluation.conflictCount > 0 {
            verdict = korean ? "\(evaluation.conflictCount)곳 관람 어려움" : "\(evaluation.conflictCount) stops may not fit"
        } else {
            let unknownHours = evaluation.stops.count { $0.verdict is RouteStopVerdictHoursUnknown }
            if unknownHours == 0 {
                verdict = korean ? "모두 관람 가능" : "All stops fit"
            } else {
                verdict = korean ? "\(unknownHours)곳 운영 시간 미확인" : "\(unknownHours) stops with unconfirmed hours"
            }
        }
        return RouteInfo(text: "\(routeDistance(evaluation.totalDistanceMeters)) · \(evaluation.travelMinutes) \(korean ? "분 도보" : "min walk") · \(verdict)")
    }
}

private struct NativeRouteAddStops: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.gallrKorean) private var korean
    let catalogue: ExhibitionListState?
    let today: Kotlinx_datetimeLocalDate?
    let excludedIDs: Set<String>
    let retry: () -> Void
    let onAdd: ([Exhibition]) -> Void
    @State private var query = ""
    @State private var selected: [Exhibition] = []

    var body: some View {
        RoutePage(title: korean ? "전시 추가" : "ADD EXHIBITIONS") {
            TextField(korean ? "전시 검색" : "Search exhibitions", text: $query)
                .textFieldStyle(.roundedBorder)
            if let exhibitions = (catalogue as? ExhibitionListState.Success)?.exhibitions {
                let lang: AppLanguage = korean ? .ko : .en
                let matching = exhibitions.filter {
                    isAvailable($0) && (query.isEmpty || $0.localizedName(lang: lang).localizedCaseInsensitiveContains(query)
                            || $0.localizedVenueName(lang: lang).localizedCaseInsensitiveContains(query))
                }
                RouteAction(title: korean ? "\(selected.count)곳 추가" : "ADD \(selected.count)", primary: true) {
                    onAdd(selected)
                    dismiss()
                }
                .disabled(selected.isEmpty)
                .accessibilityIdentifier("native-route-confirm-stops")
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(matching, id: \.id) { exhibition in
                        RouteChip(
                            title: exhibition.localizedName(lang: lang),
                            selected: selected.contains { $0.id == exhibition.id }
                        ) {
                            if let existing = selected.firstIndex(where: { $0.id == exhibition.id }) {
                                selected.remove(at: existing)
                            } else {
                                selected.append(exhibition)
                            }
                        }
                        .disabled(isAtCapacity && !selected.contains { $0.id == exhibition.id })
                        .accessibilityIdentifier("native-route-stop.\(exhibition.id)")
                    }
                }
            } else if catalogue is ExhibitionListState.Error {
                RouteAction(title: korean ? "다시 시도" : "RETRY", action: retry)
            } else {
                ProgressView()
            }
        }
    }

    private var isAtCapacity: Bool {
        excludedIDs.count + selected.count >= Int(PersonalRouteKt.MAX_ROUTE_STOPS)
    }

    private func isAvailable(_ exhibition: Exhibition) -> Bool {
        guard !excludedIDs.contains(exhibition.id), exhibition.latitude != nil, exhibition.longitude != nil else {
            return false
        }
        guard let today else { return true }
        return ExhibitionStatusKt.exhibitionStatus(
            openingDate: exhibition.openingDate,
            closingDate: exhibition.closingDate,
            today: today
        ) != .ended
    }
}

private final class RouteDeviceLocation: NSObject, ObservableObject, CLLocationManagerDelegate {
    struct Reading {
        let status: LocationPermissionStatus
        let point: GeoPoint?
    }

    @Published private(set) var reading = Reading(status: .canAsk, point: nil)
    private lazy var manager: CLLocationManager = {
        let manager = CLLocationManager()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        return manager
    }()
    private var wantsLocation = false

    func readIfAuthorized() {
        updateAuthorization()
    }

    func request() {
        wantsLocation = true
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else {
            updateAuthorization()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        updateAuthorization()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let coordinate = locations.last?.coordinate else { return }
        reading = Reading(status: .granted, point: GeoPoint(latitude: coordinate.latitude, longitude: coordinate.longitude))
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        reading = Reading(status: reading.status, point: nil)
    }

    private func updateAuthorization() {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            reading = Reading(status: .granted, point: reading.point)
            if wantsLocation || reading.point == nil { manager.requestLocation() }
        case .denied, .restricted:
            reading = Reading(status: .deniedPermanently, point: nil)
        default:
            reading = Reading(status: .canAsk, point: nil)
        }
        wantsLocation = false
    }
}

func routeStopTitle(_ stop: PersonalRouteStop, korean: Bool) -> String {
    korean || stop.nameEn.isEmpty ? stop.nameKo : stop.nameEn
}

func routeStopVenue(_ stop: PersonalRouteStop, korean: Bool) -> String {
    korean || stop.venueNameEn.isEmpty ? stop.venueNameKo : stop.venueNameEn
}

func routeMapPoint(_ stop: PersonalRouteStop, covers: [String: String], korean: Bool) -> RouteMapPoint {
    RouteMapPoint(point: stop.point, title: routeStopTitle(stop, korean: korean), cover: covers[stop.exhibitionId])
}

struct RouteStopRow: View {
    let position: Int
    let title: String
    let subtitle: String
    let cover: String?
    let note: String?
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(alignment: .top, spacing: 12) {
                Text(String(format: "%02d", position))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(GallrStyle.secondary)
                if let cover, let url = URL(string: cover) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        GallrStyle.mutedSurface
                    }
                    .frame(width: 48, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.subheadline.weight(.semibold))
                    Text(subtitle).font(.caption).foregroundStyle(GallrStyle.secondary)
                    if let note {
                        Text(note).font(.caption).foregroundStyle(GallrStyle.secondary)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption)
            }
            .frame(minHeight: 44)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}
