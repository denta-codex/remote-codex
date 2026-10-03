Phone credential approval preview

Open Settings → Credential requests to answer a waiting op-bridge single-secret request. Select the requested item through 1Password Autofill, then tap Release once or Deny. There are no notifications or background phone service.

Values are sent only to the waiting caller. The screen blocks screenshots, clears submitted values, and never resends an uncertain approval. Refresh checks request status only.

The execution host starts a temporary op-bridge session only when a caller requests a secret. The Rust forwarder authenticates and relays this separate connection; stock Codex never receives credential traffic.

This preview keeps your desktop destination as the default. Use --desktop phone for a harmless end-to-end test before changing the default. The existing Autofill test screen remains available.
