# Follow-up suggestions

The composer displays three compact, wrapping pills above its existing input.
Each pill shows the complete short chat message that tapping it sends. Submission
uses the existing safeguards and journal, preserves the current mode, and leaves
the separate draft and its attachments intact. Suggestions never approve a
decision, invoke a tool, or switch modes.

Generation occurs when opening or reopening an existing settled chat, including
returning from settings or bringing that chat back into the foreground. Completed
turns invalidate old suggestions but do not trigger generation while the user
stays on the screen. An in-memory cache reuses results for unchanged conversation
text on the same connection. Late results are discarded after navigation,
conversation changes, or reconnection. Errors and the two-second deadline leave
the normal composer usable without a provider error banner or automatic retry.

## Request path and credentials

Android uses its existing authenticated WSS connection to stock Codex
`command/exec`. A fixed Bash command receives JSON as a positional data argument
and calls localhost LiteLLM with curl. It runs in a read-only sandbox with network
access, a bounded deadline, and a bounded output size. Conversation text is never
interpolated into shell code. No Codex thread or inference turn is created.

The command decrypts the existing host-side LiteLLM proxy credential and supplies
the Authorization header through a private file descriptor. LiteLLM holds the
Cerebras provider credential. Neither secret is sent to Android, added to the RPC
payload, embedded in an argument, or written to operational logs. LiteLLM stays
on `127.0.0.1:4000`; no additional Tailscale route or host helper is needed.

`FollowUpSuggestions` isolates the generation capability from the UI and ordinary
chat submission. The v1 transport selects `cerebras/qwen-3.8-27b` independently of
the working chat model, disables reasoning and retries, and asks for structured
JSON containing three distinct messages of at most ten words. Context contains
only recent user and assistant text, capped at 6,000 characters. Attachment
metadata, tool output, and reasoning are excluded. Invalid output is discarded.

## Backend preparation and future deployment

Backend source changes live in `/home/agent/workspaces/litellm-deploy`. Its
deployment inventory enables an encrypted Cerebras service credential sourced
from the existing ignored `.env` in this repository. Provisioning runs only when
the backend deployment is explicitly invoked; an existing encrypted credential
is retained. Route activation uses the existing model-refresh transaction and
recovery mechanism. It preserves the working model and coding model picker.

After committing/reviewing source, a separately authorized deployment consists
of the backend's `deploy/deploy.yml`, then `deploy/cerebras.yml`, followed by the
normal Android build/release and installation workflows as separately requested.
Deploy the backend route before distributing the Android feature. Until the route
exists, the composer quietly omits suggestions. Roll back a failed route
activation using `deploy/cerebras.yml -e refresh_action=rollback`.

Implementation and validation on October 5, 2026 did **not** deploy either repo,
restart the live service, publish an app update, or install on the user's phone.

## Measured latency

Synthetic context only; full completion latency, not time to first token:

| Path | Samples | Median | Observed p95 | Maximum |
| --- | ---: | ---: | ---: | ---: |
| Disposable localhost LiteLLM → Cerebras | 20 | 254 ms | 686 ms | 1,819 ms |
| Existing stock control socket → command/exec → disposable LiteLLM → Cerebras | 10 | 319 ms | 829 ms | 829 ms |

The first batch used ten short contexts (239–248 input tokens) and ten longer
contexts (1,219–1,228 input tokens). All 30 completions returned three valid
messages, using 25–48 output tokens and zero reasoning tokens. Some longer
requests benefited from Cerebras prompt caching. These are small samples, not a
production SLA. The stock-path test substituted only the disposable proxy's port
in the implemented fixed command and used the existing encrypted proxy credential.
It does not include the phone's WSS network round trip.

Focused unit and fixture-backed emulator tests cover unchanged-context reuse,
reopening, exact-text submission, draft/attachment and mode preservation, stale
responses, deadlines, malformed output, quiet errors, and existing queue and
plan-mode behavior. Backend tests exercise stock Cerebras request transformation,
key isolation, no retries on provider failure, route preservation, and startup.
