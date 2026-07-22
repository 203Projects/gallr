import SwiftUI
import composeApp

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    // Supabase credentials — anon key is a publishable read-only key (safe to store here).
    // Find them in: Supabase dashboard → your project → Settings → API
    private let supabaseUrl = "https://yhuhjxswjbrtmbpbrciq.supabase.co"
    private let supabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlodWhqeHN3amJydG1icGJyY2lxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM5MzY4NzYsImV4cCI6MjA4OTUxMjg3Nn0.UEKRh1t3K79h58OW1RoNwRTXa1LdeCt0f6M2NEJuadU"
    private let exhibitionCatalogSource = Bundle.main.object(
        forInfoDictionaryKey: "GallrExhibitionCatalogSource"
    ) as? String ?? "legacy"

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewControllerWithCatalogSource(
            supabaseUrl: supabaseUrl,
            anonKey: supabaseAnonKey,
            exhibitionCatalogSource: exhibitionCatalogSource
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
