import XCTest
@testable import RemoteCodexCore

final class PairingTests: XCTestCase {
    func testAcceptsExistingTokenOnlyQR() {
        let token = String(repeating: "0123456789abcdef", count: 4)
        XCTAssertEqual(Pairing.parseSetupQR("remote-codex-setup-v1:" + token), token)
        XCTAssertEqual(parseSetupQR("remote-codex-setup-v1:" + token), token)
    }
    func testRejectsEndpointsWhitespaceUppercaseAndOtherVersions() {
        let token = String(repeating: "a", count: 64)
        for invalid in [nil, "", token, "remote-codex-setup-v2:" + token,
                        "remote-codex-setup-v1:" + token + "\n", "remote-codex-setup-v1:" + token.uppercased(),
                        "remote-codex-setup-v1:wss://other.example/" + token,
                        "remote-codex-setup-v1:" + String(token.dropLast())] {
            XCTAssertNil(Pairing.parseSetupQR(invalid))
        }
    }
}
