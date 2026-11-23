import XCTest

final class NativeWindowGeometryTests: XCTestCase {
    private let bounds = CGRect(x: 0, y: 0, width: 800, height: 700)

    func testVerticalDivisionLeavesTheBookHingeBetweenPanes() {
        let layout = GallrWindowLayout(
            isExpanded: true,
            divisionRegions: [CGRect(x: 380, y: 0, width: 40, height: 700)],
            contentSize: bounds.size
        )
        let panes = layout.paneFrames(in: bounds, split: true)
        XCTAssertEqual(panes.master, CGRect(x: 0, y: 0, width: 380, height: 700))
        XCTAssertEqual(panes.detail, CGRect(x: 420, y: 0, width: 380, height: 700))
    }

    func testHorizontalDivisionLeavesTheTabletopHingeBetweenPanes() {
        let layout = GallrWindowLayout(
            isExpanded: true,
            divisionRegions: [CGRect(x: 0, y: 320, width: 800, height: 30)],
            contentSize: bounds.size
        )
        let panes = layout.paneFrames(in: bounds, split: true)
        XCTAssertEqual(panes.master, CGRect(x: 0, y: 0, width: 800, height: 320))
        XCTAssertEqual(panes.detail, CGRect(x: 0, y: 350, width: 800, height: 350))
    }

    func testCameraOcclusionMovesIntersectingControlsAway() {
        let layout = GallrWindowLayout(
            isExpanded: true,
            occlusionRegions: [
                CGRect(x: 12, y: 8, width: 30, height: 38),
                CGRect(x: 740, y: 0, width: 45, height: 37),
                CGRect(x: 480, y: 665, width: 60, height: 20),
            ],
            contentSize: bounds.size
        )
        XCTAssertEqual(layout.leadingControlsInset, 42)
        XCTAssertEqual(layout.trailingControlsInset, 60)
        XCTAssertEqual(layout.bottomControlsInset, 35)
    }

    func testCompactKeepsSinglePaneAndUnobstructedControls() {
        let layout = GallrWindowLayout(contentSize: bounds.size)
        let panes = layout.paneFrames(in: bounds, split: false)
        XCTAssertEqual(panes.master, bounds)
        XCTAssertEqual(panes.detail, bounds)
        XCTAssertEqual(layout.leadingControlsInset, 0)
        XCTAssertEqual(layout.trailingControlsInset, 0)
        XCTAssertEqual(layout.bottomControlsInset, 0)
    }
}
