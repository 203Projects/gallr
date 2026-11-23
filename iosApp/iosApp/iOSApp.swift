import SwiftUI
import UIKit
import composeApp

final class GallrAppDelegate: NSObject, UIApplicationDelegate {
    private var registrationObserver: NSObjectProtocol?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        registrationObserver = NotificationCenter.default.addObserver(
            forName: Notification.Name("GallrRegisterForRemoteNotifications"),
            object: nil,
            queue: .main
        ) { _ in
            UIApplication.shared.registerForRemoteNotifications()
        }
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        #if DEBUG
        let environment = "sandbox"
        #else
        let environment = "production"
        #endif
        MainViewControllerKt.handleRemotePushToken(
            token: token,
            environment: environment
        )
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        MainViewControllerKt.handleRemotePushRegistrationFailure()
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(GallrAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    private let sharedApp: NativeGallrApp = {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--native-fixture") {
            return NativeGallrApp(
                fixtureCatalogue: NativeFixturesKt.nativeExhibitionFixtures(),
                fixtureSignedIn: ProcessInfo.processInfo.arguments.contains("--native-signed-in-fixture")
            )
        }
        #endif
        let config = Bundle.main
        let catalogue = config.object(forInfoDictionaryKey: "GallrExhibitionCatalogSource") as? String ?? "legacy"
        let promotion = (config.object(forInfoDictionaryKey: "GallrPromotionEnabled") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "true"
        // Dark launch: changing this literal requires a separately reviewed app release.
        return NativeGallrApp(
            supabaseUrl: Config.supabaseUrl,
            supabaseApiKey: Config.supabaseApiKey,
            exhibitionCatalogSource: catalogue,
            promotionEnabled: promotion,
            mobileAnalyticsReleaseEnabled: false
        )
    }()

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--native-adaptive-fixture") {
                NativeResizeFixture { ContentView(app: sharedApp) }
            } else {
                ContentView(app: sharedApp)
            }
            #else
            ContentView(app: sharedApp)
            #endif
        }
        .onChange(of: scenePhase) { phase in
            // The App-level phase is the aggregate across windows.
            if phase == .active {
                sharedApp.foreground()
            } else if phase == .background {
                sharedApp.background()
            }
        }
    }
}


#if DEBUG
private struct NativeResizeFixture<Content: View>: View {
    @ViewBuilder var content: () -> Content
    @State private var compact = true

    var body: some View {
        VStack(spacing: 0) {
            Button(compact ? "Expand fixture" : "Compact fixture") {
                compact.toggle()
            }
            .accessibilityIdentifier("gallr-fixture-resize")
            .frame(minHeight: 44)

            content()
                .frame(maxWidth: compact ? 380 : .infinity)
        }
    }
}
#endif
