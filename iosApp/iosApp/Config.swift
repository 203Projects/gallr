import Foundation

enum Config {
    private static let productionSupabaseUrl = "https://yhuhjxswjbrtmbpbrciq.supabase.co"
    private static let productionSupabasePublishableKey = "sb_publishable_3bwhbY7tBpATZ2UV32jy3A_-8votvXD"

    static let supabaseUrl = bundleOverride(
        key: "GallrSupabaseURL",
        fallback: productionSupabaseUrl
    )

    static let supabaseAnonKey = bundleOverride(
        key: "GallrSupabaseAnonKey",
        fallback: productionSupabasePublishableKey
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
