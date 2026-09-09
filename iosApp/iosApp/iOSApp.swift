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

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    MainViewControllerKt.handleDeeplinkUrl(url: url.absoluteString)
                }
        }
    }
}
