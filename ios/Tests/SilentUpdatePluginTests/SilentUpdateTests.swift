import XCTest
@testable import SilentUpdatePlugin

/// iOS implementation is a stub until platform support lands. This test
/// just asserts the Capacitor bridge identifiers stay stable, so renaming
/// them later is caught by CI.
class SilentUpdateTests: XCTestCase {
    func testBridgeIdentifiers() {
        let plugin = SilentUpdatePlugin()
        XCTAssertEqual(plugin.identifier, "SilentUpdatePlugin")
        XCTAssertEqual(plugin.jsName, "SilentUpdate")
    }
}
