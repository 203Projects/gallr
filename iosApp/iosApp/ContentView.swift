import SwiftUI
import composeApp

struct ContentView: View {
    let app: NativeGallrApp

    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var navigation = GallrNavigation()
    @State private var korean = true
    @State private var signedIn = false
    @State private var colorScheme: ColorScheme?
    @State private var keyboardVisible = false
    @State private var startupPreferencesReady = false
    @State private var startupCatalogueReady = false
    @State private var startupMinimumElapsed = false
    @State private var startupDeadlineElapsed = false
    @Environment(\.openURL) private var openURL
    @State private var updateRequired = false
    @State private var showNotificationsPrompt = false
    @State private var showSignUpNudge = false
    @State private var storeFailed = false
    @GestureState private var backSwipeOffset: CGFloat = 0
    @State private var navigationObservation: NativeObservation?
    @State private var notificationObservation: NativeObservation?
    @State private var observations: [NativeObservation] = []

    private var showingStartup: Bool {
        !startupDeadlineElapsed && !(startupPreferencesReady && startupCatalogueReady && startupMinimumElapsed)
    }

    var body: some View {
        Group {
            if showingStartup {
                startupView
            } else if updateRequired {
                requiredUpdateView
            } else {
                windowContent
            }
        }
        .background(GallrStyle.background.ignoresSafeArea())
        .foregroundStyle(GallrStyle.foreground)
        .tint(GallrStyle.foreground)
        .environmentObject(navigation)
        .environment(\.gallrKorean, korean)
        .preferredColorScheme(colorScheme)
        .onAppear {
            subscribe()
            watchNavigation(when: scenePhase)
        }
        .onDisappear(perform: unsubscribe)
        .task {
            do {
                try await Task.sleep(for: .milliseconds(1500))
                startupMinimumElapsed = true
                try await Task.sleep(for: .milliseconds(1500))
                startupDeadlineElapsed = true
            } catch is CancellationError {
                return
            } catch {
                startupDeadlineElapsed = true
            }
        }
        .onChange(of: scenePhase) { watchNavigation(when: $0) }
        .onOpenURL { app.openUrl(url: $0.absoluteString) }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            if scenePhase == .active { keyboardVisible = true }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardVisible = false
        }
        .sheet(isPresented: availableDuringNormalUse($showSignUpNudge), onDismiss: app.tabs.dismissSignUpNudge) {
            VStack(alignment: .leading, spacing: 16) {
                Text(korean ? "전시 5개를 저장했어요." : "You’ve saved 5 exhibitions.")
                    .font(GallrStyle.font(18, weight: .medium, korean: korean, relativeTo: .headline))
                Text(korean
                    ? "재설치하면 목록이 사라질 수 있어요. 로그인하면 안전하게 보관됩니다."
                    : "Sign in so your list doesn’t disappear if you reinstall.")
                    .foregroundStyle(GallrStyle.secondary)
                NativePrimaryButton(korean ? "gallr 로그인" : "Sign in to gallr") {
                    showSignUpNudge = false
                    app.tabs.dismissSignUpNudge()
                    navigation.push(.promptedSignIn)
                }
                Button(korean ? "나중에" : "Not now") { showSignUpNudge = false }
                    .frame(maxWidth: .infinity)
            }
            .padding(32)
            .presentationDetents([.medium])
        }
        .alert(
            korean ? "알림을 받을까요?" : "Allow reminders?",
            isPresented: availableDuringNormalUse($showNotificationsPrompt)
        ) {
            Button(korean ? "허용" : "Allow") { app.respondToNotificationPrompt(allow: true) }
            Button(korean ? "나중에" : "Not now", role: .cancel) { app.respondToNotificationPrompt(allow: false) }
        } message: {
            Text(korean ? "저장한 전시가 끝나기 전에 알려드려요." : "Get a reminder before your saved exhibitions close.")
        }
    }

    private func availableDuringNormalUse(_ presented: Binding<Bool>) -> Binding<Bool> {
        Binding(
            get: { presented.wrappedValue && !showingStartup && !updateRequired },
            set: { presented.wrappedValue = $0 }
        )
    }

    private var startupView: some View {
        ZStack {
            Color("SplashBackground").ignoresSafeArea()
            Image("SplashLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 72, height: 72)
                .foregroundStyle(Color("SplashLogoColor"))
                .accessibilityHidden(true)
        }
    }

    private var windowContent: some View {
        GallrWindowReader { window in
            GeometryReader { viewport in
                ZStack {
                    ForEach(GallrTab.allCases.filter(navigation.visited.contains)) { tab in
                        tabContent(tab, window: window)
                            .frame(width: viewport.size.width, height: viewport.size.height)
                            .opacity(navigation.selected == tab ? 1 : 0)
                            .allowsHitTesting(navigation.selected == tab)
                            .gallrExposedToAccessibility(navigation.selected == tab)
                    }
                }
                .frame(width: viewport.size.width, height: viewport.size.height)
            }
            .simultaneousGesture(backSwipe)
            .accessibilityElement(children: .contain)
            .accessibilityAction(.escape) {
                if navigation.canGoBack { back() }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !keyboardVisible {
                    tabBar(window: window)
                }
            }
            .environment(\.gallrWindowLayout, window)
        }
    }

    private var requiredUpdateView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(korean ? "업데이트가 필요해요" : "UPDATE REQUIRED")
                    .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
                Text(korean
                    ? "지금 쓰는 버전은 더 이상 지원되지 않아요. 업데이트하면 계속 쓸 수 있어요."
                    : "This version is no longer supported. Update to keep using gallr.")
                    .foregroundStyle(GallrStyle.secondary)
                Button(action: openStore) {
                    Text(korean ? "업데이트" : "UPDATE")
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(GallrStyle.background)
                        .background(GallrStyle.foreground)
                }
                .buttonStyle(.plain)
                if storeFailed {
                    Text(korean ? "스토어에서 gallr를 업데이트해 주세요" : "Please update gallr from the store")
                        .foregroundStyle(GallrStyle.secondary)
                }
            }
            .frame(maxWidth: 460)
            .padding(32)
            .frame(maxWidth: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func openStore() {
        guard let url = URL(string: app.updateStoreUrl()) else {
            storeFailed = true
            return
        }
        openURL(url) { accepted in storeFailed = !accepted }
    }

    private func subscribe() {
        guard observations.isEmpty else { return }
        observations = [
            app.observeLanguage { language in korean = language == .ko },
            app.observeTheme { applyTheme($0) },
            app.observeInitialPresentation { language, theme in
                korean = language == .ko
                applyTheme(theme)
                startupPreferencesReady = true
            },
            app.observe(state: app.tabs.featuredState) { state in
                if state is ExhibitionListState.Success || state is ExhibitionListState.Error {
                    startupCatalogueReady = true
                }
            },
            app.observeUpdateRequirement { updateRequired = $0 == .required },
            app.observe(state: app.tabs.showSignUpNudge) { value in
                showSignUpNudge = (value as? KotlinBoolean)?.boolValue == true && !updateRequired
            },
            app.observeAuth { state in
                signedIn = state is AuthState.Authenticated
                if signedIn { navigation.signInCompleted() }
            }
        ]
    }

    private func applyTheme(_ theme: ThemeMode) {
        switch theme {
        case .light: colorScheme = .light
        case .dark: colorScheme = .dark
        default: colorScheme = nil
        }
    }

    private func unsubscribe() {
        observations.forEach { $0.cancel() }
        observations = []
        navigationObservation?.cancel()
        navigationObservation = nil
        notificationObservation?.cancel()
        notificationObservation = nil
    }

    private func watchNavigation(when phase: ScenePhase) {
        navigationObservation?.cancel()
        navigationObservation = nil
        notificationObservation?.cancel()
        notificationObservation = nil
        guard phase == .active else {
            showNotificationsPrompt = false
            return
        }
        notificationObservation = app.observeNotificationPrompt { showNotificationsPrompt = $0.boolValue }
        navigationObservation = app.observeNavigation { request in
            guard app.claimNavigation(request: request) else { return }
            if let exhibition = request.exhibition {
                navigation.push(.exhibitionNotification(exhibition.id))
            } else {
                navigation.select(.list)
            }
        }
    }

    private func tabContent(_ tab: GallrTab, window: GallrWindowLayout) -> some View {
        let trail = navigation.trails[tab, default: []]
        let tabActive = navigation.selected == tab
        let split = window.isExpanded && (tab == .list || tab == .map)
            && trail.last?.route.supportsSplit == true
        let masterVisible = split || trail.isEmpty
        let revealPrevious = tabActive && !split && backSwipeOffset > 0
        let previousID = trail.dropLast().last?.id

        return GallrBrowseLayout(split: split, window: window) {
            VStack(spacing: 0) {
                rootHeader(tab: tab, window: window)
                if tab == .my {
                    MyRootView(app: app)
                } else {
                    CatalogueRootView(tab: tab, app: app)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .environment(\.gallrSurfaceVisible, tabActive && masterVisible && scenePhase == .active)
            .opacity(masterVisible || (revealPrevious && trail.count == 1) ? 1 : 0)
            .allowsHitTesting(tabActive && masterVisible)
            .gallrExposedToAccessibility(tabActive && masterVisible)

            VStack(spacing: 0) {
                if tabActive, let top = trail.last {
                    destinationHeader(top.route, window: window)
                        .offset(x: revealPrevious ? backSwipeOffset : 0)
                } else if !trail.isEmpty {
                    Color.clear.frame(height: 44)
                }
                ZStack {
                    ForEach(trail) { entry in
                        let top = trail.last?.id == entry.id
                        destination(entry.route)
                            .environment(\.gallrNavigationEntry, entry.id)
                            .environment(\.gallrSurfaceVisible, tabActive && top && scenePhase == .active)
                            .opacity(top || (revealPrevious && previousID == entry.id) ? 1 : 0)
                            .offset(x: top && revealPrevious ? backSwipeOffset : 0)
                            .allowsHitTesting(tabActive && top)
                            .gallrExposedToAccessibility(tabActive && top)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .leading) {
                if split {
                    Rectangle().fill(GallrStyle.divider).frame(width: 1)
                }
            }
            .allowsHitTesting(tabActive && !trail.isEmpty)
            .gallrExposedToAccessibility(tabActive && !trail.isEmpty)
        }
        .environment(\.gallrNavigationTab, tab)
        .clipped()
    }

    private var backSwipe: some Gesture {
        DragGesture(minimumDistance: 14)
            .updating($backSwipeOffset) { value, offset, _ in
                if navigation.canGoBack, isBackSwipe(value) {
                    offset = max(0, value.translation.width)
                }
            }
            .onEnded { value in
                if navigation.canGoBack, isBackSwipe(value),
                   value.translation.width > 72 || value.predictedEndTranslation.width > 130 {
                    back()
                }
            }
    }

    private func back() {
        if !signedIn, app.routesEnabled, navigation.trails[navigation.selected]?.last?.route == .promptedSignIn {
            app.routeComposer.cancelSignIn()
        }
        navigation.back()
    }

    private func isBackSwipe(_ value: DragGesture.Value) -> Bool {
        value.startLocation.x < 24 && value.translation.width > abs(value.translation.height)
    }

    private func rootHeader(tab: GallrTab, window: GallrWindowLayout) -> some View {
        HStack {
            Text("gallr")
                .font(GallrStyle.font(18, weight: .medium, relativeTo: .headline))
                .accessibilityAddTraits(.isHeader)
            Spacer()
            connectMenu
            if tab == .my {
                Button {
                    navigation.push(.settings)
                } label: {
                    Image(systemName: "gearshape")
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(korean ? "설정" : "Settings")
                .accessibilityIdentifier("gallr-settings")
            }
        }
        .frame(minHeight: 48)
        .padding(.leading, 16 + window.leadingControlsInset)
        .padding(.trailing, 16 + window.trailingControlsInset)
        .background(GallrStyle.background)
    }

    private var connectMenu: some View {
        Menu {
            if let instagram = URL(string: "https://instagram.com/gallrmap") {
                Link(korean ? "인스타그램 팔로우" : "Follow on Instagram", destination: instagram)
            }
            if let email = URL(string: "mailto:hello@gallrmap.com") {
                Link(korean ? "이메일 문의" : "Email us", destination: email)
            }
            if let store = URL(string: app.updateStoreUrl()) {
                ShareLink(item: store) {
                    Text(korean ? "앱 공유하기" : "Tell friends")
                }
            }
        } label: {
            Image(systemName: "info.circle")
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(korean ? "연결" : "Connect")
    }

    private func destinationHeader(_ route: GallrRoute, window: GallrWindowLayout) -> some View {
        HStack {
            Button(action: back) {
                Text(route == .promptedSignIn ? (korean ? "← 뒤로" : "← BACK") : "←")
                    .font(GallrStyle.font(16, weight: .medium, korean: korean))
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(korean ? "뒤로" : "Back")
            .accessibilityIdentifier("gallr-back")
            Spacer()
        }
        .padding(.leading, 16 + window.leadingControlsInset)
        .padding(.trailing, 16 + window.trailingControlsInset)
        .background(GallrStyle.background)
    }

    @ViewBuilder
    private func destination(_ route: GallrRoute) -> some View {
        Group {
            if route.belongsToCatalogue {
                CatalogueDestinationView(route: route, app: app)
            } else {
                MyDestinationView(route: route, app: app)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GallrStyle.background)
    }

    private func tabBar(window: GallrWindowLayout) -> some View {
        VStack(spacing: 0) {
            Rectangle().fill(GallrStyle.divider).frame(height: 1)
            if let hinge = window.divisionRegions.first(where: { $0.height > $0.width }) {
                HStack(spacing: 0) {
                    tabs(GallrTab.allCases.prefix(2))
                        .frame(width: hinge.minX)
                    Spacer().frame(width: hinge.width)
                    tabs(GallrTab.allCases.suffix(2))
                }
            } else {
                tabs(GallrTab.allCases[...])
            }
        }
        .padding(.bottom, window.bottomControlsInset)
        .background(GallrStyle.background)
    }

    private func tabs(_ tabs: ArraySlice<GallrTab>) -> some View {
        HStack(spacing: 0) {
            ForEach(tabs) { tab in
                Button {
                    navigation.select(tab)
                } label: {
                    VStack(spacing: 0) {
                        Rectangle()
                            .fill(navigation.selected == tab ? GallrStyle.accent : Color.clear)
                            .frame(height: 4)
                        Text(tab.title(korean: korean))
                            .font(GallrStyle.font(13, weight: .medium, korean: korean && tab != .my, relativeTo: .caption))
                            .tracking(0.52)
                            .foregroundStyle(navigation.selected == tab ? GallrStyle.foreground : GallrStyle.secondary)
                            .padding(.vertical, 16)
                            .frame(minHeight: 48)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(navigation.selected == tab ? [.isSelected] : [])
                .accessibilityIdentifier("gallr-tab-\(tab.rawValue)")
            }
        }
    }
}


private extension View {
    func gallrExposedToAccessibility(_ exposed: Bool) -> some View {
        accessibilityElement(children: exposed ? .contain : .ignore)
            .accessibilityHidden(!exposed)
    }
}
