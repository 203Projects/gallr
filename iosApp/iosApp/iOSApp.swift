import SwiftUI
import composeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    NSLog("GALLR_DEEPLINK: received URL: \(url.absoluteString)")
                    MainViewControllerKt.handleDeeplinkUrl(url: url.absoluteString)
                }
        }
    }
}
