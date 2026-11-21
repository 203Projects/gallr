import SwiftUI

private struct GallrWindowLayoutKey: EnvironmentKey {
    static let defaultValue = GallrWindowLayout()
}

extension EnvironmentValues {
    var gallrWindowLayout: GallrWindowLayout {
        get { self[GallrWindowLayoutKey.self] }
        set { self[GallrWindowLayoutKey.self] = newValue }
    }
}

struct GallrWindowReader<Content: View>: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @ViewBuilder var content: (GallrWindowLayout) -> Content

    var body: some View {
        GeometryReader { geometry in
            content(windowLayout(geometry))
        }
    }

    private func windowLayout(_ geometry: GeometryProxy) -> GallrWindowLayout {
        var layout = GallrWindowLayout(
            isExpanded: horizontalSizeClass == .regular && geometry.size.width >= 600,
            contentSize: geometry.size
        )
        // Xcode 26's SDK doesn't contain the reserved-region symbols. Swift 6.4 first
        // shipped in Xcode 27, and the API below is present in the 27.1 and later SDKs.
        #if compiler(>=6.4) && !GALLR_IOS_27_0_SDK
        if #available(iOS 27.1, *) {
            layout.divisionRegions = geometry.reservedRegions(kind: .division).filter(\.isActive).map(\.frame)
            layout.occlusionRegions = geometry.reservedRegions(kind: .occlusion).filter(\.isActive).map(\.frame)
        }
        #endif
        return layout
    }
}

/// Two permanently mounted children keep their SwiftUI identity when the same
/// selection moves between the full window and a detail pane.
struct GallrBrowseLayout: Layout {
    var split: Bool
    var window: GallrWindowLayout

    func sizeThatFits(
        proposal: ProposedViewSize, subviews: Subviews, cache: inout ()
    ) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func explicitAlignment(
        of guide: HorizontalAlignment, in bounds: CGRect, proposal: ProposedViewSize,
        subviews: Subviews, cache: inout ()
    ) -> CGFloat? {
        nil
    }

    func explicitAlignment(
        of guide: VerticalAlignment, in bounds: CGRect, proposal: ProposedViewSize,
        subviews: Subviews, cache: inout ()
    ) -> CGFloat? {
        nil
    }

    func placeSubviews(
        in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()
    ) {
        guard subviews.count == 2 else { return }

        let frames = window.paneFrames(in: bounds, split: split)
        let rects = [frames.master, frames.detail]
        for (index, rect) in rects.enumerated() {
            subviews[index].place(
                at: rect.origin,
                anchor: .topLeading,
                proposal: ProposedViewSize(rect.size)
            )
        }
    }
}
