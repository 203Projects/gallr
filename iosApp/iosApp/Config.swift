import Foundation

enum Config {
    private static let productionSupabaseUrl = "https://yhuhjxswjbrtmbpbrciq.supabase.co"
    private static let productionSupabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlodWhqeHN3amJydG1icGJyY2lxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM5MzY4NzYsImV4cCI6MjA4OTUxMjg3Nn0.UEKRh1t3K79h58OW1RoNwRTXa1LdeCt0f6M2NEJuadU"

    static let supabaseUrl = bundleOverride(
        key: "GallrSupabaseURL",
        fallback: productionSupabaseUrl
    )

    static let supabaseAnonKey = bundleOverride(
        key: "GallrSupabaseAnonKey",
        fallback: productionSupabaseAnonKey
    )

    private static func bundleOverride(key: String, fallback: String) -> String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String else {
            return fallback
        }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("$(") else {
            return fallback
        }
        return trimmed
    }
}
