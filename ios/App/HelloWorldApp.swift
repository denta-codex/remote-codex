import HelloWorldFixtureCore
import SwiftUI

@main
struct HelloWorldApp: App {
    var body: some Scene {
        WindowGroup { HelloWorldView() }
    }
}

struct HelloWorldView: View {
    private let store: FixtureStore
    @State private var state: FixtureState
    @State private var note: String
    @State private var storageError: String?
    @FocusState private var editingNote: Bool

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let store = FixtureStore(fileURL: directory.appendingPathComponent("HelloWorldFixture/state.json"))
        self.store = store
        do {
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("--fixture") && arguments.contains("--ui-testing")
                && arguments.contains("--reset-fixture") {
                try store.reset()
            }
            let restored = try store.load()
            _state = State(initialValue: restored)
            _note = State(initialValue: restored.savedNote)
            _storageError = State(initialValue: nil)
        } catch {
            _state = State(initialValue: FixtureState())
            _note = State(initialValue: "")
            _storageError = State(initialValue: "Could not load fixture state. Saving is disabled.")
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Hello, world!")
                        .font(.largeTitle.bold())
                        .accessibilityIdentifier("hello-world")
                    Text("Offline testing fixture")
                        .accessibilityIdentifier("fixture-status")
                }
                Section("Tap test") {
                    Text("Tap count: \(state.tapCount)")
                        .accessibilityIdentifier("tap-count")
                    Button("Tap me") {
                        var updated = state
                        updated.tapCount += 1
                        save(updated)
                    }
                    .accessibilityIdentifier("increment-button")
                    .disabled(storageError != nil)
                }
                Section("Persistence test") {
                    TextField("Type a synthetic note", text: $note, axis: .vertical)
                        .lineLimit(1...4)
                        .focused($editingNote)
                        .accessibilityIdentifier("note-input")
                    Button("Save note") {
                        var updated = state
                        updated.savedNote = note
                        save(updated)
                        editingNote = false
                    }
                    .accessibilityIdentifier("save-note")
                    .disabled(storageError != nil)
                    Text("Saved note: \(state.savedNote.isEmpty ? "(empty)" : state.savedNote)")
                        .accessibilityIdentifier("saved-note")
                }
                Section {
                    Text(buildDescription)
                        .accessibilityIdentifier("build-info")
                    if let storageError {
                        Text(storageError).foregroundStyle(.red)
                            .accessibilityIdentifier("storage-error")
                    }
                }
            }
            .navigationTitle("Hello World")
        }
    }

    private var buildDescription: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
        return "Version \(version) · Build \(build)"
    }

    private func save(_ updated: FixtureState) {
        do {
            try store.save(updated)
            state = updated
        } catch {
            storageError = "Could not save fixture state. Restart to inspect the saved state."
        }
    }
}
