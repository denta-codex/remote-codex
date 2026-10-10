import Foundation
import RemoteCodexCore

/// Runs production reconciliation against synthetic scenarios. Output contains
/// counts only: the harness cannot connect to Grace or accept credentials.
@main
struct RemoteCodexFixture {
    static func main() async {
        do {
            let arguments = Array(CommandLine.arguments.dropFirst())
            guard arguments.count == 2, arguments[0] == "validate" else {
                throw FixtureFailure.invalidArguments
            }
            let data = try Data(contentsOf: URL(fileURLWithPath: arguments[1]))
            let scenarios = try JSONDecoder().decode([Scenario].self, from: data)
            for scenario in scenarios {
                var timeline = TimelineState()
                timeline.hydrate(turns: scenario.turns, bufferedEvents: scenario.events)
                guard timeline.entries.count == scenario.expected.entries,
                      timeline.activeTurnID == scenario.expected.activeTurnID,
                      timeline.entries.last?.text == scenario.expected.lastText else {
                    throw FixtureFailure.expectationMismatch
                }
            }
            let summary = ["scenarios": scenarios.count, "passed": scenarios.count]
            let output = try JSONSerialization.data(withJSONObject: summary, options: [.sortedKeys])
            print(String(decoding: output, as: UTF8.self))
        } catch {
            // Never emit errors from arbitrary fixture content or raw payloads.
            fputs("Fixture validation failed. Usage: RemoteCodexFixture validate <scenario-file>\n", stderr)
            exit(1)
        }
    }

    private struct Scenario: Decodable {
        let turns: [JSONValue]
        let events: [JSONValue]
        let expected: Expectation
    }

    private struct Expectation: Decodable {
        let entries: Int
        let activeTurnID: String?
        let lastText: String
    }

    private enum FixtureFailure: Error { case invalidArguments, expectationMismatch }
}
