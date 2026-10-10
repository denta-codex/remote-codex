import Foundation
import XCTest
@testable import RemoteCodexCore

final class JSONValueTests: XCTestCase {
    func testIntegerIDsAboveDoublePrecisionRemainExact() throws {
        let value = try JSONDecoder().decode(JSONValue.self, from: Data("{\"id\":9007199254740993,\"stringId\":\"9007199254740993\"}".utf8))
        XCTAssertEqual(value["id"], .integer(9_007_199_254_740_993))
        XCTAssertEqual(value["stringId"], .string("9007199254740993"))
        XCTAssertNotEqual(value["id"], value["stringId"])
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value)), value)
    }
    func testNestedValuesAndTypedHelpers() throws {
        let value = try JSONDecoder().decode(JSONValue.self, from: Data("{\"bool\":true,\"int\":1,\"float\":1.25,\"list\":[null,\"x\"]}".utf8))
        XCTAssertEqual(value["bool"]?.boolValue, true)
        XCTAssertNil(value["bool"]?.integerValue)
        XCTAssertEqual(value["int"]?.integerValue, 1)
        XCTAssertNil(value["int"]?.boolValue)
        XCTAssertEqual(value["float"], .number(1.25))
        XCTAssertEqual(value["list"]?.arrayValue, [.null, .string("x")])
        XCTAssertNil(value["missing"])
    }
    func testInt64ExtremesAndEscapedUnicodeRoundTrip() throws {
        let value: JSONValue = .object(["max": .integer(.max), "min": .integer(.min), "text": .string("👩🏽‍💻\n\"\\")])
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value)), value)
    }
}
