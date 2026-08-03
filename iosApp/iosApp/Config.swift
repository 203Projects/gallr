import Foundation

enum Config {
    private static let productionSupabaseUrl = "https://oqrvbstopuppznxqoonp.supabase.co"
    private static let productionSupabasePublishableKey = "sb_publishable_1kUp8Pf3udHgiPNdmkwbsA_2tl3ueHK"

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
