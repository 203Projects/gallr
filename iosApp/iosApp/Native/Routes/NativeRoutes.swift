import SwiftUI
import composeApp

struct NativeRoutes: View {
    let route: GallrRoute
    let app: NativeGallrApp

    var body: some View {
        if app.routesEnabled {
            switch route {
            case .routeComposer:
                NativeRouteComposer(app: app, seed: nil)
            case let .seededRouteComposer(id):
                NativeRouteComposer(app: app, seed: id)
            case .publicRoutes:
                NativePublicRouteBrowse(app: app)
            case let .publicRoute(id):
                NativePublicRoutePreview(app: app, routeId: id)
            case .myRoutes:
                NativeMyRoutes(app: app)
            case let .routePlanner(latitude, longitude, mode):
                NativeRoutePlanner(app: app, origin: GeoPoint(latitude: latitude, longitude: longitude), mode: mode)
            default:
                EmptyView()
            }
        }
    }
}

struct RoutePage<Content: View>: View {
    @Environment(\.gallrKorean) private var korean
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(title)
                    .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
                content
            }
            .frame(maxWidth: 680, alignment: .leading)
            .padding(16)
            .frame(maxWidth: .infinity)
        }
        .foregroundStyle(GallrStyle.foreground)
        .background(GallrStyle.background)
    }
}

struct RouteAction: View {
    @Environment(\.gallrKorean) private var korean
    let title: String
    var primary = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(GallrStyle.font(14, weight: .medium, korean: korean))
                .padding(.horizontal, 16)
                .frame(minHeight: 44)
                .frame(maxWidth: .infinity)
                .foregroundStyle(primary ? GallrStyle.background : GallrStyle.foreground)
                .background(primary ? GallrStyle.foreground : GallrStyle.mutedSurface)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}

struct RouteChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .background(GallrStyle.mutedSurface)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay {
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(selected ? GallrStyle.accent : GallrStyle.divider)
                }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

struct RouteInfo: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(GallrStyle.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct RouteVisitTimeSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.gallrKorean) private var korean
    let options: VisitTimeOptions
    let selection: RouteVisitTime?
    let onSelection: (RouteVisitTime?) -> Void
    @State private var day: Kotlinx_datetimeLocalDate?

    var body: some View {
        RoutePage(title: korean ? "방문 시간" : "VISIT TIME") {
            RouteAction(title: korean ? "지금부터" : "START NOW", primary: selection == nil) {
                onSelection(nil)
                dismiss()
            }
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(options.days, id: \.self) { option in
                        RouteChip(title: option.description, selected: (day ?? selection?.day ?? options.days.first) == option) {
                            day = option
                        }
                    }
                }
            }
            if let selectedDay = day ?? selection?.day ?? options.days.first {
                let times = options.timesFor(day: selectedDay)
                if times.isEmpty {
                    RouteInfo(text: korean ? "이 날에는 남은 시간이 없어요." : "No start times remain on this day.")
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 80), spacing: 8)], spacing: 8) {
                    ForEach(times, id: \.self) { start in
                        RouteChip(title: start.description, selected: selection?.day == selectedDay && selection?.start == start) {
                            onSelection(RouteVisitTime(day: selectedDay, start: start))
                            dismiss()
                        }
                    }
                }
            }
        }
    }
}

func routeVisitTimeLabel(_ time: RouteVisitTime?, korean: Bool) -> String {
    if let time {
        return "\(time.day) · \(time.start)"
    }
    return korean ? "방문 시간 선택" : "CHOOSE VISIT TIME"
}

func routeVerdictLabel(_ verdict: any RouteStopVerdict, korean: Bool) -> String {
    switch verdict {
    case is RouteStopVerdictOpen: return korean ? "관람 가능" : "Open"
    case is RouteStopVerdictHoursUnknown: return korean ? "운영 시간 확인 필요" : "Check opening hours"
    case is RouteStopVerdictClosedOnPlannedDay: return korean ? "이 날 휴관" : "Closed on this day"
    case is RouteStopVerdictArrivesAfterClose: return korean ? "도착 전에 문을 닫아요" : "Closes before arrival"
    case is RouteStopVerdictVisitCutShort: return korean ? "관람 시간이 짧아요" : "Short visit"
    case is RouteStopVerdictEnded: return korean ? "종료된 전시" : "Exhibition ended"
    case is RouteStopVerdictNotYetOpen: return korean ? "아직 시작하지 않았어요" : "Not yet open"
    default: return korean ? "전시 정보를 볼 수 없어요" : "Exhibition unavailable"
    }
}

func routeDistance(_ meters: Int32) -> String {
    if meters < 1000 { return "\(meters) m" }
    return "\((Double(meters) / 1000).formatted(.number.precision(.fractionLength(1)))) km"
}
