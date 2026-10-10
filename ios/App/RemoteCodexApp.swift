import SwiftUI

@main
struct RemoteCodexApp: App {
    @StateObject private var controller = AppController()
    var body: some Scene {
        WindowGroup {
            ClientRootView(controller: controller)
                .task { await controller.start() }
        }
    }
}
