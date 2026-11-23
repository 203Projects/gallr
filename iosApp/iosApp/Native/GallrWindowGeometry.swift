import CoreGraphics

struct GallrWindowLayout: Equatable {
    var isExpanded = false
    var divisionRegions: [CGRect] = []
    var occlusionRegions: [CGRect] = []
    var contentSize: CGSize = .zero

    var leadingControlsInset: CGFloat {
        occlusionRegions
            .filter { $0.minX < 72 && $0.minY < 56 && $0.maxY > 0 }
            .map(\.maxX)
            .max() ?? 0
    }

    var trailingControlsInset: CGFloat {
        let edge = contentSize.width
        return occlusionRegions
            .filter { $0.maxX > edge - 112 && $0.minY < 56 && $0.maxY > 0 }
            .map { edge - $0.minX }
            .max() ?? 0
    }

    var bottomControlsInset: CGFloat {
        let edge = contentSize.height
        let horizontalDivisions = divisionRegions.filter { $0.width > $0.height }
        return (occlusionRegions + horizontalDivisions)
            .filter { $0.maxY > edge - 56 && $0.minY < edge }
            .map { edge - $0.minY }
            .max() ?? 0
    }

    var reservedRegions: [CGRect] { divisionRegions + occlusionRegions }

    func paneFrames(in bounds: CGRect, split: Bool) -> (master: CGRect, detail: CGRect) {
        var master = bounds
        var detail = bounds
        if split {
            if let hinge = divisionRegions.first(where: {
                $0.height > $0.width && $0.minX >= 240 && bounds.width - $0.maxX >= 240
            }) {
                master.size.width = hinge.minX
                detail.origin.x += hinge.maxX
                detail.size.width -= hinge.maxX
            } else if let hinge = divisionRegions.first(where: {
                $0.width > $0.height && $0.minY >= 160 && bounds.height - $0.maxY >= 160
            }) {
                master.size.height = hinge.minY
                detail.origin.y += hinge.maxY
                detail.size.height -= hinge.maxY
            } else {
                let masterWidth = min(max(bounds.width * 0.43, 280), 420)
                master.size.width = masterWidth
                detail.origin.x += masterWidth
                detail.size.width -= masterWidth
            }
        }
        return (master, detail)
    }
}
