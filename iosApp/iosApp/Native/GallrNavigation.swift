import SwiftUI

enum GallrTab: String, CaseIterable, Identifiable {
    case home, list, map, my

    var id: String { rawValue }

    func title(korean: Bool) -> String {
        switch self {
        case .home: return korean ? "홈" : "HOME"
        case .list: return korean ? "목록" : "LIST"
        case .map: return korean ? "지도" : "MAP"
        case .my: return "MY"
        }
    }
}

enum GallrRoute: Hashable {
    case exhibition(String), exhibitionNote(String), exhibitionShare(String), exhibitionNotification(String)
    case recommendationExhibition(String), recommendationShare(String)
    case gallery(String), event(String), editors, editor(String), collection(String), recommendations
    case account, promptedSignIn, passwordRecovery, settings
    case diaryEntry(String), diaryNote(String), diary, publishedDiary, following, profile, editProfile
    case addVisits, addEndedVisits, addGalleries
    case routeComposer, seededRouteComposer(String), publicRoute(String), publicRoutes, myRoutes
    case routePlanner(latitude: Double, longitude: Double, mode: String)

    var belongsToCatalogue: Bool {
        switch self {
        case .exhibition, .exhibitionNote, .exhibitionShare, .exhibitionNotification, .recommendationExhibition, .recommendationShare,
             .gallery, .event, .editors,
             .editor, .collection, .recommendations:
            return true
        default:
            return false
        }
    }

    var supportsSplit: Bool {
        switch self {
        case .exhibition, .exhibitionNote, .exhibitionNotification, .recommendationExhibition, .gallery, .event, .editor:
            return true
        default:
            return false
        }
    }
}

struct GallrNavigationEntry: Identifiable, Equatable {
    let id = UUID()
    let route: GallrRoute
}

@MainActor
final class GallrNavigation: ObservableObject {
    @Published private(set) var selected: GallrTab = .home
    @Published private(set) var visited: Set<GallrTab> = [.home]
    private var backHandlers: [UUID: () -> Bool] = [:]
    @Published private(set) var trails: [GallrTab: [GallrNavigationEntry]] = [:]

    func select(_ tab: GallrTab) {
        if tab == selected {
            guard permitBack(on: tab) else { return }
            trails[tab]?.forEach { backHandlers.removeValue(forKey: $0.id) }
            trails[tab] = []
        } else {
            show(tab)
        }
    }

    func push(_ route: GallrRoute) {
        guard trails[selected]?.last?.route != route else { return }
        trails[selected, default: []].append(GallrNavigationEntry(route: route))
    }

    var canGoBack: Bool { !(trails[selected]?.isEmpty ?? true) }

    func back() {
        back(on: selected)
    }

    func back(on tab: GallrTab, bypassHandler: Bool = false) {
        guard let trail = trails[tab], let current = trail.last else { return }
        guard bypassHandler || permitBack(on: tab) else { return }
        backHandlers.removeValue(forKey: current.id)
        trails[tab] = Array(trail.dropLast())
    }

    func setBackHandler(for entry: UUID, handler: (() -> Bool)?) {
        backHandlers[entry] = handler
    }

    private func permitBack(on tab: GallrTab) -> Bool {
        guard let entry = trails[tab]?.last else { return true }
        return backHandlers[entry.id]?() ?? true
    }

    func open(_ route: GallrRoute, on tab: GallrTab) {
        show(tab)
        push(route)
    }

    func show(_ tab: GallrTab) {
        visited.insert(tab)
        selected = tab
    }

    func signInCompleted() {
        for tab in GallrTab.allCases where trails[tab]?.last?.route == .promptedSignIn {
            back(on: tab)
        }
    }
}


private struct GallrSurfaceVisibleKey: EnvironmentKey {
    static let defaultValue = true
}

private struct GallrNavigationEntryKey: EnvironmentKey {
    static let defaultValue: UUID? = nil
}

private struct GallrNavigationTabKey: EnvironmentKey {
    static let defaultValue: GallrTab = .home
}

extension EnvironmentValues {
    var gallrNavigationEntry: UUID? {
        get { self[GallrNavigationEntryKey.self] }
        set { self[GallrNavigationEntryKey.self] = newValue }
    }

    var gallrNavigationTab: GallrTab {
        get { self[GallrNavigationTabKey.self] }
        set { self[GallrNavigationTabKey.self] = newValue }
    }

    var gallrSurfaceVisible: Bool {
        get { self[GallrSurfaceVisibleKey.self] }
        set { self[GallrSurfaceVisibleKey.self] = newValue }
    }
}
