import SwiftUI
import NMapsMap
import composeApp

@main
struct iOSApp: App {
    init() {
        NMFAuthManager.shared().ncpKeyId = "dkd2c8bh63"
    }

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
