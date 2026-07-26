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

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewControllerWithCatalogSource(
            supabaseUrl: Config.supabaseUrl,
            anonKey: Config.supabaseAnonKey,
            exhibitionCatalogSource: exhibitionCatalogSource
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
