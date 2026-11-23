import SwiftUI
import UIKit

enum GallrStyle {
    static let accent = Color(red: 1, green: 84.0 / 255, blue: 0)
    static let background = adaptive(light: 0xFFFFFF, dark: 0x121212)
    static let foreground = adaptive(light: 0x000000, dark: 0xE0E0E0)
    static let surface = adaptive(light: 0xFFFFFF, dark: 0x1E1E1E)
    static let mutedSurface = adaptive(light: 0xF5F5F5, dark: 0x2C2C2C)
    static let secondary = adaptive(light: 0x525252, dark: 0xA0A0A0)
    static let outline = adaptive(light: 0x000000, dark: 0x404040)
    static let divider = adaptive(light: 0xE5E5E5, dark: 0x333333)

    static func font(
        _ size: CGFloat,
        weight: Font.Weight = .regular,
        korean: Bool = false,
        relativeTo textStyle: Font.TextStyle = .body
    ) -> Font {
        let name: String
        if korean {
            name = "GothicA1-Medium"
        } else if weight == .bold {
            name = "Inter18pt-Bold"
        } else if weight == .medium || weight == .semibold {
            name = "Inter18pt-Medium"
        } else {
            name = "Inter18pt-Regular"
        }
        return .custom(name, size: size, relativeTo: textStyle)
    }

    private static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((value >> 16) & 255) / 255,
                green: CGFloat((value >> 8) & 255) / 255,
                blue: CGFloat(value & 255) / 255,
                alpha: 1
            )
        })
    }
}


private struct GallrKoreanKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    var gallrKorean: Bool {
        get { self[GallrKoreanKey.self] }
        set { self[GallrKoreanKey.self] = newValue }
    }
}
