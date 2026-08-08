import SwiftUI
import composeApp

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    private let exhibitionCatalogSource = Bundle.main.object(
        forInfoDictionaryKey: "GallrExhibitionCatalogSource"
    ) as? String ?? "legacy"
    private let promotionEnabled: Bool = {
        let value = Bundle.main.object(
            forInfoDictionaryKey: "GallrPromotionEnabled"
        ) as? String ?? "false"
        return ["1", "true", "yes"].contains(value.lowercased())
    }()

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewControllerWithCatalogSource(
            supabaseUrl: Config.supabaseUrl,
            anonKey: Config.supabaseAnonKey,
            exhibitionCatalogSource: exhibitionCatalogSource,
            promotionEnabled: promotionEnabled
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
