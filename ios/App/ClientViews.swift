import SwiftUI
import VisionKit
import RemoteCodexCore

struct ClientRootView: View {
    @ObservedObject var controller: AppController
    @State private var conversation = false
    @State private var settings = false
    var body: some View {
        NavigationStack {
            List {
                if !controller.connected {
                    Section {
                        Text("Connect to Grace to see your chats.")
                        Button("Connection settings") { settings = true }
                    }
                }
                ForEach(controller.chats) { chat in
                    Button {
                        conversation = true
                        Task { await controller.openChat(chat.id, title: chat.title.isEmpty ? "Chat" : chat.title) }
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(chat.title.isEmpty ? "Untitled chat" : chat.title).font(.headline).foregroundStyle(.primary)
                            Text(chat.preview).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                        }
                    }.accessibilityIdentifier("chat-\(chat.id)")
                }
                if controller.listCursor != nil {
                    Button("Load more chats") { Task { await controller.refreshChats(more: true) } }
                }
            }
            .navigationTitle("Chats")
            .refreshable { await controller.refreshChats() }
            .overlay { if controller.loading { ProgressView() } }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Settings", systemImage: "gearshape") { settings = true }.accessibilityIdentifier("settings")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("New chat", systemImage: "square.and.pencil") {
                        Task { await controller.newChat(); conversation = true }
                    }.disabled(!controller.connected).accessibilityIdentifier("new-chat")
                }
            }
            .navigationDestination(isPresented: $conversation) { ConversationView(controller: controller) }
            .sheet(isPresented: $settings) { SettingsView(controller: controller) }
            .safeAreaInset(edge: .bottom) {
                if let error = controller.error {
                    HStack(alignment: .top) {
                        Text(error).font(.footnote).accessibilityIdentifier("client-error")
                        Spacer()
                        Button("Dismiss", systemImage: "xmark.circle.fill") { controller.error = nil }.labelStyle(.iconOnly)
                    }.padding().background(.regularMaterial)
                }
            }
        }
    }
}

struct SettingsView: View {
    @ObservedObject var controller: AppController
    @Environment(\.dismiss) private var dismiss
    @State private var scanning = false
    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    LabeledContent("Host", value: "Grace")
                    Text(AppController.endpoint.absoluteString).font(.caption).textSelection(.enabled)
                    LabeledContent("Status", value: controller.connected ? "Connected" : "Disconnected")
                    if !controller.account.isEmpty { LabeledContent("Account", value: controller.account) }
                }
                if !controller.fixture {
                    Section("Pair this iPhone") {
                        SecureField("Transport token or setup QR text", text: $controller.tokenInput)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("setup-token")
                        Button(controller.connecting ? "Connecting…" : "Save and connect") {
                            Task { await controller.setup(controller.tokenInput) }
                        }.disabled(controller.connecting || controller.tokenInput.isEmpty)
                        if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                            Button("Scan setup QR", systemImage: "qrcode.viewfinder") { scanning = true }
                        }
                        Text("The setup QR contains only the transport token. Sign in to Codex on Grace first.").font(.footnote)
                    }
                    Section {
                        Button("Reconnect") { Task { await controller.reconnect() } }.disabled(controller.connecting)
                        Button("Disconnect") { Task { await controller.disconnect() } }
                        Button("Forget token", role: .destructive) { Task { await controller.forgetToken() } }
                    }
                } else {
                    Section { Text("Fixture mode uses synthetic content and never connects to a server.") }
                }
            }
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $scanning) {
                QRScanner { value in
                    scanning = false
                    Task { await controller.setup(value) }
                }.ignoresSafeArea()
            }
        }
    }
}

struct ConversationView: View {
    @ObservedObject var controller: AppController
    @State private var abandon = false
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if controller.pendingOperation { recoveryBanner }
                    if controller.historyNeedsRecovery {
                        Button("Recover using smaller history pages") { Task { await controller.loadHistory(smaller: true) } }
                    } else if controller.hasMoreHistory {
                        Button("Load earlier messages") { Task { await controller.loadHistory() } }
                    }
                    if controller.loading { ProgressView("Loading conversation…") }
                    ForEach(controller.timeline.entries) { entry in TimelineRow(entry: entry) }
                    ForEach(controller.approvals.requests) { request in
                        ApprovalCard(controller: controller, request: request)
                    }
                    if !controller.queue.isEmpty { queueSection }
                    Color.clear.frame(height: 1).id("bottom")
                }.padding()
            }
            .onChange(of: controller.timeline.entries.last?.text) { _, _ in
                if controller.timeline.activeTurnID != nil { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            }
        }
        .navigationTitle(controller.selectedTitle)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { composer }
        .toolbar {
            if controller.timeline.activeTurnID != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Stop", systemImage: "stop.circle") { Task { await controller.stop() } }
                        .disabled(controller.busy || controller.pendingOperation)
                }
            }
        }
        .onChange(of: controller.draft) { _, _ in Task { await controller.persistDraft() } }
        .onChange(of: scenePhase) { _, phase in if phase != .active { Task { await controller.persistDraft() } } }
        .confirmationDialog("Keep the draft and dismiss this operation?", isPresented: $abandon, titleVisibility: .visible) {
            Button("Keep draft and dismiss operation", role: .destructive) { Task { await controller.abandonOperation() } }
        } message: { Text("The operation may already exist on Grace. Dismissing does not undo it. Inspect server state before sending the draft again.") }
    }
    private var recoveryBanner: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Operation awaiting confirmation").font(.headline)
            Text("Your draft is saved. Check Grace before another send.").font(.footnote)
            Button("Reconcile with Grace") { Task { await controller.reconcile() } }
            Button("Keep draft and dismiss…", role: .destructive) { abandon = true }
        }.padding().background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }
    private var queueSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Queued messages").font(.headline)
            ForEach(controller.queue) { item in
                VStack(alignment: .leading, spacing: 8) {
                    Text(item.text)
                    HStack {
                        Button("Remove", role: .destructive) { Task { await controller.queueAction(item, start: false) } }
                        if controller.timeline.activeTurnID == nil {
                            Button("Send now") { Task { await controller.queueAction(item, start: true) } }
                        }
                    }.disabled(controller.busy || controller.pendingOperation)
                }
            }
        }.padding().background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
    private var composer: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Message", text: $controller.draft, axis: .vertical)
                .lineLimit(1...7).padding(10).background(.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("message-draft")
            Button {
                Task { await controller.send() }
            } label: {
                Image(systemName: controller.timeline.activeTurnID != nil || !controller.queue.isEmpty ? "clock.arrow.circlepath" : "arrow.up.circle.fill")
                    .font(.title)
            }.accessibilityLabel(controller.timeline.activeTurnID != nil || !controller.queue.isEmpty ? "Queue message" : "Send message")
                .accessibilityIdentifier("send-message")
                .disabled(!controller.connected || controller.busy || controller.pendingOperation || controller.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }.padding().background(.regularMaterial)
    }
}

private struct TimelineRow: View {
    let entry: TimelineEntry
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            if entry.kind == "agentMessage" {
                Text(.init(entry.text)).textSelection(.enabled)
            } else {
                Text(entry.text.isEmpty ? entry.kind : entry.text)
                    .font(entry.kind == "commandExecution" || entry.kind == "fileChange" ? .system(.footnote, design: .monospaced) : .body)
                    .textSelection(.enabled)
            }
            if !entry.progressMessage.isEmpty { Text(entry.progressMessage).font(.caption).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(entry.kind == "userMessage" ? 12 : 0)
        .background(entry.kind == "userMessage" ? Color.secondary.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 12))
    }
    private var label: String {
        switch entry.kind {
        case "userMessage": return "You"
        case "agentMessage": return "Codex"
        case "commandExecution": return "Command"
        case "fileChange": return "File changes"
        case "reasoning": return "Working"
        default: return "Tool activity"
        }
    }
}

private struct ApprovalCard: View {
    @ObservedObject var controller: AppController
    let request: ApprovalRequest
    @State private var confirmation: ApprovalChoice?
    @State private var confirmChoice = false
    @State private var selectedPermissions = Set<String>()
    @State private var questionSelections: [String: String] = [:]
    @State private var questionNotes: [String: String] = [:]
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            if let reason = request.params["reason"]?.stringValue { Text(reason) }
            if let command = request.params["command"]?.stringValue { Text(command).font(.system(.footnote, design: .monospaced)).textSelection(.enabled) }
            if request.method == "item/fileChange/requestApproval" {
                if let context = controller.approvals.fileContext(for: request) {
                    Text(context).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                } else { Text("File details are unavailable. Approval is disabled until Grace provides the changes.").font(.footnote) }
            }
            if request.method == "item/permissions/requestApproval" { permissionControls }
            else if request.method == "item/tool/requestUserInput" { questionControls }
            else {
                ForEach(Array(request.choices.enumerated()), id: \.offset) { _, choice in
                    Button(choice.label) {
                        if choice.consequence.isEmpty { Task { await controller.answer(request, result: choice.result) } }
                        else { confirmation = choice; confirmChoice = true }
                    }.disabled(request.method == "item/fileChange/requestApproval" && choice.grantsAccess && controller.approvals.fileContext(for: request) == nil)
                }
            }
        }.padding().frame(maxWidth: .infinity, alignment: .leading)
            .background(.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
            .confirmationDialog(confirmation?.label ?? "Confirm approval", isPresented: $confirmChoice, titleVisibility: .visible) {
                if let confirmation {
                    Button(confirmation.label) { Task { await controller.answer(request, result: confirmation.result, broaderScope: true) } }
                }
            } message: { Text(confirmation?.consequence ?? "") }
    }
    private var title: String {
        switch request.method {
        case "item/fileChange/requestApproval": return "Review file changes"
        case "item/permissions/requestApproval": return "Requested permissions"
        case "item/tool/requestUserInput": return "Codex needs your input"
        default: return "Review command"
        }
    }
    private var permissionControls: some View {
        let selection = PermissionSelection(request: request)
        return VStack(alignment: .leading) {
            ForEach(selection.options) { option in
                Toggle(option.label, isOn: Binding(get: { selectedPermissions.contains(option.id) }, set: { enabled in
                    if enabled { selectedPermissions.insert(option.id) } else { selectedPermissions.remove(option.id) }
                }))
            }
            Button("Grant selected for this turn") {
                if let result = try? selection.result(selected: selectedPermissions) { Task { await controller.answer(request, result: result) } }
            }.disabled(selectedPermissions.isEmpty)
            Button("Deny requested permissions", role: .destructive) {
                if let result = try? selection.result(selected: []) { Task { await controller.answer(request, result: result) } }
            }
        }
    }
    private var questionControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(request.questions.enumerated()), id: \.offset) { _, question in
                let id = question["id"]?.stringValue ?? ""
                VStack(alignment: .leading, spacing: 8) {
                    Text(question["question"]?.stringValue ?? "Question")
                    ForEach(Array((question["options"]?.arrayValue ?? []).enumerated()), id: \.offset) { _, option in
                        let label = option["label"]?.stringValue ?? ""
                        Button {
                            questionSelections[id] = label
                        } label: {
                            HStack(alignment: .top) {
                                Image(systemName: questionSelections[id] == label ? "checkmark.circle.fill" : "circle")
                                VStack(alignment: .leading) {
                                    Text(label)
                                    if let description = option["description"]?.stringValue { Text(description).font(.footnote).foregroundStyle(.secondary) }
                                }
                            }
                        }
                    }
                    if question["isSecret"]?.boolValue == true {
                        SecureField("Answer", text: Binding(get: { questionNotes[id] ?? "" }, set: { questionNotes[id] = $0 }))
                    } else {
                        TextField("Additional answer", text: Binding(get: { questionNotes[id] ?? "" }, set: { questionNotes[id] = $0 }), axis: .vertical)
                    }
                }
            }
            Button("Submit answers") {
                Task { await controller.answer(request, result: request.questionAnswers(selections: questionSelections, notes: questionNotes)) }
            }
        }
    }
}
