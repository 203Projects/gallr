import SwiftUI
import UserNotifications
import composeApp

struct NativeSettingsView: View {
    let app: NativeGallrApp

    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @Environment(\.gallrNavigationTab) private var ownerTab
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var theme: ThemeMode = .system
    @State private var signedIn = false
    @State private var analytics: Bool?
    @State private var hidden: HiddenAuthorsUiState?
    @State private var notifications: UNAuthorizationStatus?
    @State private var observations: [NativeObservation] = []
    @State private var action: NativeObservation?
    @State private var confirmation: AccountAction?
    @State private var failure = false

    private enum AccountAction: String, Identifiable {
        case signOut, deleteAccount
        var id: String { rawValue }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(korean ? "설정" : "SETTINGS")
                    .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
                preferences
                support
                if signedIn { account }
                if failure {
                    Text(korean ? "실행하지 못했어요. 다시 시도해 주세요." : "Couldn’t complete this action. Try again.")
                        .foregroundStyle(GallrStyle.secondary)
                }
            }
            .frame(maxWidth: 580, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
            .frame(maxWidth: .infinity)
        }
        .onAppear {
            observations = [
                app.observeTheme { theme = $0 },
                app.observeAuth { signedIn = $0 is AuthState.Authenticated },
                app.observeAnalyticsEnabled { analytics = $0?.boolValue },
                app.observeHiddenAuthors { hidden = $0 },
            ]
            refreshNotifications()
        }
        .onDisappear {
            observations.forEach { $0.cancel() }
            action?.cancel()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { refreshNotifications() }
        }
        .confirmationDialog(
            confirmation == .deleteAccount ? (korean ? "계정을 삭제할까요?" : "Delete your account?")
                : (korean ? "로그아웃할까요?" : "Sign out?"),
            isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }),
            titleVisibility: .visible,
            presenting: confirmation
        ) { selected in
            Button(selected == .deleteAccount ? (korean ? "계정 삭제" : "Delete account")
                : (korean ? "로그아웃" : "Sign out"), role: .destructive) {
                perform(selected)
            }
            Button(korean ? "취소" : "Cancel", role: .cancel) {}
        }
    }

    private var preferences: some View {
        VStack(alignment: .leading, spacing: 0) {
            section(korean ? "환경 설정" : "PREFERENCES")
            Picker(korean ? "언어" : "Language", selection: Binding(
                get: { korean },
                set: { app.tabs.setLanguage(lang: $0 ? .ko : .en) }
            )) {
                Text("한국어").tag(true)
                Text("English").tag(false)
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("gallr-settings-language")
            .frame(minHeight: 48)

            Picker(korean ? "화면 모드" : "Appearance", selection: Binding(
                get: { theme },
                set: { app.tabs.setThemeMode(mode: $0) }
            )) {
                Text(korean ? "시스템" : "System").tag(ThemeMode.system)
                Text(korean ? "밝게" : "Light").tag(ThemeMode.light)
                Text(korean ? "어둡게" : "Dark").tag(ThemeMode.dark)
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("gallr-settings-appearance")
            .frame(minHeight: 48)

            row(korean ? "알림" : "Notifications", detail: notificationStatus) {
                if notifications == .notDetermined {
                    app.respondToNotificationPrompt(allow: true)
                    refreshNotifications()
                } else if let url = URL(string: UIApplication.openSettingsURLString) {
                    openURL(url) { if !$0 { failure = true } }
                }
            }

            if let analytics {
                Toggle(korean ? "앱 사용 분석 공유" : "Share usage analytics", isOn: Binding(
                    get: { analytics },
                    set: { app.setAnalyticsEnabled(enabled: $0) }
                ))
                .frame(minHeight: 48)
                Text(korean
                    ? "전시를 둘러보는 방식에 관한 사용 정보를 공유해 gallr 개선을 돕습니다."
                    : "Share usage information about how you browse exhibitions to help improve gallr.")
                    .font(.footnote)
                    .foregroundStyle(GallrStyle.secondary)
                link(korean ? "개인정보 처리방침" : "Privacy choices", "https://gallrmap.com/privacy/#choices")
            }

            DisclosureGroup(korean ? "숨긴 사용자" : "Hidden users") {
                if let hidden {
                    if hidden.authors.isEmpty {
                        Text(korean ? "숨긴 사용자가 없어요." : "No hidden users.")
                            .foregroundStyle(GallrStyle.secondary)
                            .padding(.vertical, 12)
                    }
                    ForEach(hidden.authors, id: \.userId) { author in
                        row(author.displayName, detail: korean ? "숨기기 해제" : "Unhide") {
                            app.hiddenAuthors.unhide(userId: author.userId)
                        }
                    }
                    if hidden.unhideFailed { Text(korean ? "다시 시도해 주세요." : "Try again.") }
                } else {
                    ProgressView()
                }
            }
            .padding(.vertical, 12)
        }
    }

    private var support: some View {
        VStack(alignment: .leading, spacing: 0) {
            section(korean ? "지원" : "SUPPORT")
            link(korean ? "의견 보내기" : "Send feedback", "mailto:hello@gallrmap.com?subject=gallr%20feedback")
            link(korean ? "전시 정보 수정 요청" : "Report incorrect exhibition information",
                 "mailto:hello@gallrmap.com?subject=Exhibition%20information%20correction")
            if let url = URL(string: app.updateStoreUrl()) {
                ShareLink(item: url) {
                    HStack {
                        Text(korean ? "gallr 공유" : "Share gallr")
                        Spacer()
                        Image(systemName: "square.and.arrow.up")
                    }
                    .frame(minHeight: 48)
                }
                Divider()
            }
            link("Instagram", "https://instagram.com/gallrmap")
            link(korean ? "gallr 소개" : "About gallr", "https://gallrmap.com/about/")
            link(korean ? "개인정보 처리방침" : "Privacy policy", "https://gallrmap.com/privacy/")
            HStack {
                Text(korean ? "버전" : "Version")
                Spacer()
                Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    .foregroundStyle(GallrStyle.secondary)
            }
            .frame(minHeight: 48)
        }
    }

    private var account: some View {
        VStack(alignment: .leading, spacing: 0) {
            section(korean ? "계정" : "ACCOUNT")
            row(korean ? "로그아웃" : "Sign out") { confirmation = .signOut }
            row(korean ? "계정 삭제" : "Delete account") { confirmation = .deleteAccount }
        }
        .disabled(action != nil)
    }

    private func section(_ name: String) -> some View {
        Text(name)
            .font(GallrStyle.font(13, weight: .medium, korean: korean, relativeTo: .headline))
            .foregroundStyle(GallrStyle.secondary)
            .accessibilityAddTraits(.isHeader)
            .padding(.bottom, 8)
    }

    private func row(_ title: String, detail: String? = nil, action: @escaping () -> Void) -> some View {
        VStack(spacing: 0) {
            Button(action: action) {
                HStack {
                    Text(title)
                    Spacer()
                    if let detail {
                        Text(detail).foregroundStyle(GallrStyle.secondary)
                    }
                    Image(systemName: "chevron.right").font(.caption)
                }
                .contentShape(Rectangle())
                .frame(minHeight: 48)
            }
            .buttonStyle(.plain)
            Divider()
        }
    }

    private func link(_ title: String, _ address: String) -> some View {
        row(title) {
            if let url = URL(string: address) {
                openURL(url) { if !$0 { failure = true } }
            }
        }
    }

    private var notificationStatus: String {
        guard let notifications else { return "—" }
        switch notifications {
        case .authorized, .provisional, .ephemeral:
            return korean ? "켜짐" : "On"
        default:
            return korean ? "꺼짐" : "Off"
        }
    }

    private func refreshNotifications() {
        Task { notifications = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }
    }

    private func perform(_ selected: AccountAction) {
        failure = false
        switch selected {
        case .signOut:
            app.logout()
            navigation.back(on: ownerTab)
        case .deleteAccount:
            action = app.deleteAccount { succeeded in
                action = nil
                if succeeded.boolValue {
                    navigation.back(on: ownerTab)
                } else {
                    failure = true
                }
            }
        }
    }
}
