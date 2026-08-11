import Foundation

enum Config {
    private static let productionSupabaseUrl = "https://oqrvbstopuppznxqoonp.supabase.co"
    private static let productionSupabasePublishableKey = "sb_publishable_1kUp8Pf3udHgiPNdmkwbsA_2tl3ueHK"

    static let supabaseUrl = bundleOverride(
        keys: ["GallrSupabaseURL"],
        fallback: productionSupabaseUrl
    )

    static let supabaseApiKey = bundleOverride(
        keys: ["GallrSupabasePublishableKey", "GallrSupabaseAnonKey"],
        fallback: productionSupabasePublishableKey
    )

    private static func bundleOverride(
        keys: [String],
        fallback: String
    ) -> String {
        for key in keys {
            guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String else {
                continue
            }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, !trimmed.hasPrefix("$(") {
                return trimmed
            }
        }
        return fallback
    }
}
