import SwiftUI
import composeApp

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.container)
            .ignoresSafeArea(.keyboard)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    private let exhibitionCatalogSource = Bundle.main.object(
        forInfoDictionaryKey: "GallrExhibitionCatalogSource"
    ) as? String ?? "legacy"
    private let promotionEnabled = (
        Bundle.main.object(forInfoDictionaryKey: "GallrPromotionEnabled") as? String
    )?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "true"
    // Dark launch: changing this literal requires a separately reviewed app release.
    private let mobileAnalyticsReleaseEnabled = false

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewControllerWithCatalogSourceAndPromotion(
            supabaseUrl: Config.supabaseUrl,
            supabaseApiKey: Config.supabaseApiKey,
            exhibitionCatalogSource: exhibitionCatalogSource,
            promotionEnabled: promotionEnabled,
            mobileAnalyticsReleaseEnabled: mobileAnalyticsReleaseEnabled
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
