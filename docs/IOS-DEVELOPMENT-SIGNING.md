# iPhone development signing

Remote Codex is edited on Grace and built on standard GitHub-hosted macOS
runners. USB tooling on Grace installs the development IPA on the owner's
selected iPhone. The signing identity is an Apple Development certificate with
its private key, plus an iOS App Development provisioning profile. The existing
stock Codex server, WSS transport, and Android connectivity stay in place.

The reviewed app contract is:

| Input | Value |
| --- | --- |
| Bundle identifier | `dev.codexops.client.ios` |
| Platform | iPhone, iOS 18.0 or later |
| Profile | iOS App Development, explicit App ID |
| Device coverage | Only the selected owner's phone |
| Development entitlement | `get-task-allow = true` |
| Additional app services | None: no push, app groups, iCloud, or associated domains |
| Camera permission | `NSCameraUsageDescription` in Info.plist; no portal capability |

## Owner's first step

Sign in yourself at [Apple Developer Account](https://developer.apple.com/account/)
using the account that may already have a membership. Select the intended team
and open **Membership details**. Confirm active membership, Team ID, your role,
and the membership expiry/renewal date. Check any agreement banner. An Apple
login saved in 1Password does not establish enrollment or authority to use a
team's signing identity.

Return only the membership status, intended Team ID, role, expiry date, and the
1Password item name/reference for any existing signing material. Keep passwords,
verification codes, private keys, certificate bundles, and payment information
out of chat. Team IDs are available under Membership details; do not substitute
an App Store Connect issuer ID or enrollment ID.

If there is no active membership, the owner completes enrollment or renewal.
[Apple's current enrollment requirements](https://developer.apple.com/help/account/membership/program-enrollment/)
were checked on October 10, 2026: the Apple Developer Program is USD 99 per year
(local pricing may differ). Enrollment requires an Apple Account with two-factor
authentication and legal age of majority. Individual enrollment uses the owner's
legal identity and current contact details; Apple may require identity
verification. An organization needs the separate legal-entity verification
process, including a D-U-N-S number where applicable. Inspect existing membership
before starting another enrollment.

The owner handles login, trusted-device verification, identity checks, license
agreements, payment, and any renewal choice. When paying by credit card for an
individual web enrollment, Apple requires the owner's own card. Enrollment in
the Apple Developer app creates an annually renewing subscription. Confirm
activation in Membership details after Apple processes enrollment; a receipt
alone is not the signing-readiness gate.

[Apple's membership comparison](https://developer.apple.com/support/compare-memberships/)
also describes free Personal Team testing through Xcode, with seven-day profiles.
The approved manual portal/profile and GitHub signing setup uses an enrolled
Apple Developer Program team. A personal Mac is not a build prerequisite.

## Inspect existing state before creating anything

An Account Holder or Admin reviews **Certificates, Identifiers & Profiles** for
the intended team. These roles can register the App ID/device and create the
manual profile. Preserve unrelated identifiers, devices, certificates, and
profiles. An organization login is not permission to use that organization's
team for this personal app.

1. Locate the exact explicit bundle ID. Reuse it when it belongs to the approved
   team and has suitable capabilities. If absent, register that exact identifier
   using [Apple's App ID procedure](https://developer.apple.com/help/account/identifiers/register-an-app-id/).
   Do not add optional app services or an App Store Connect app record.
2. Obtain the selected phone's actual UDID from the USB tooling chat's private
   device record after confirming model, OS, and ownership. Check whether that
   exact device is already registered. If absent, use
   [Register a single device](https://developer.apple.com/help/account/devices/register-a-single-device/)
   with its real UDID and an owner-approved device name. Register only that phone;
   never use a guessed UDID or select another connected device.
3. Inspect existing Apple Development certificates for validity, intended team,
   ownership, and availability of the corresponding private key. A downloaded
   `.cer` alone cannot sign an app. Reuse an authorized, exportable P12 identity
   when suitable. A cloud-managed/nonexportable identity cannot supply this
   workflow's P12 input. Do not revoke another identity to free a certificate slot.
4. Reuse a valid profile only if it covers the exact app, authorized certificate,
   and only the selected phone. Otherwise create a new narrowly scoped profile.
   Do not edit a shared profile used by other apps or devices.

Device registration and profile generation wait for active team membership and
the real selected UDID. New or long-expired memberships can require device
processing; [Apple's device registration guidance](https://developer.apple.com/help/account/reference/device-registration-updates/)
describes the delays. Verify the downloaded profile actually contains the phone.

## New identity through a CSR on Grace

Use this path only after the approved team is known and inspection establishes
that a new identity is needed. Apple issues the certificate from a CSR; the
private key remains with its owner. Apple's
[CSR instructions](https://developer.apple.com/help/account/certificates/create-a-certificate-signing-request/)
describe Keychain Access. The commands below implement the standard RSA CSR
using Grace's existing OpenSSL, without requiring a personal Mac.

Run in an interactive Bash session with tracing disabled. Choose a private,
owner-controlled directory outside every repository (mode 0700). Files should
be mode 0600. A pending CSR's encrypted key is necessary signing state: retain it
until the issued certificate/P12 is validated and securely stored. Do not create
a fresh key while an issuance outcome is uncertain.

```bash
set -euo pipefail
set +x
umask 077
# SIGNING_DIR is an explicitly chosen private staging directory.
: "${SIGNING_DIR:?Set a private directory outside the checkout}"
mkdir -m 700 "$SIGNING_DIR"
openssl rand -base64 32 | tr -d '\n' > "$SIGNING_DIR/key-password"
openssl rand -base64 32 | tr -d '\n' > "$SIGNING_DIR/password"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -aes-256-cbc -pass "file:$SIGNING_DIR/key-password" \
  -out "$SIGNING_DIR/development.key.pem"
openssl req -new -sha256 -key "$SIGNING_DIR/development.key.pem" \
  -passin "file:$SIGNING_DIR/key-password" \
  -subj '/CN=Remote Codex Grace Development' \
  -out "$SIGNING_DIR/development.csr"
openssl req -in "$SIGNING_DIR/development.csr" -verify -noout
```

Using the intended team's portal, the owner selects **Certificates → + → Apple
Development**, uploads only `development.csr`, and downloads the issued `.cer`
into that private directory. If creation times out, inspect the certificate
listing before retrying. A certificate identity is usable for multiple apps;
the profile supplies the app/device restriction. The descriptive CSR name does
not make the certificate itself app-scoped.

Convert the DER certificate, compare public keys, and export the identity:

```bash
openssl x509 -inform DER -in "$SIGNING_DIR/development.cer" \
  -out "$SIGNING_DIR/development.cert.pem"
openssl pkey -in "$SIGNING_DIR/development.key.pem" \
  -passin "file:$SIGNING_DIR/key-password" -pubout -outform DER \
  -out "$SIGNING_DIR/key.public.der"
openssl x509 -in "$SIGNING_DIR/development.cert.pem" -pubkey -noout |
  openssl pkey -pubin -outform DER -out "$SIGNING_DIR/cert.public.der"
cmp "$SIGNING_DIR/key.public.der" "$SIGNING_DIR/cert.public.der"
openssl x509 -in "$SIGNING_DIR/development.cert.pem" -checkend 0 -noout
openssl x509 -in "$SIGNING_DIR/development.cert.pem" -noout -dates
openssl pkcs12 -export -inkey "$SIGNING_DIR/development.key.pem" \
  -in "$SIGNING_DIR/development.cert.pem" \
  -passin "file:$SIGNING_DIR/key-password" -passout "file:$SIGNING_DIR/password" \
  -name 'Remote Codex development' -out "$SIGNING_DIR/development.p12"
```

OpenSSL success proves local formatting/key correspondence, not acceptance by
Apple's portal, Xcode keychain, or the phone. Validate the downloaded certificate
chain using the appropriate intermediates/roots from
[Apple PKI](https://www.apple.com/certificateauthority/), its Apple Development
type, intended team, valid-from and expiry dates, and current portal status.
[Apple's WWDR guidance](https://developer.apple.com/help/account/certificates/wwdr-intermediate-certificates/)
identifies the software-signing intermediate. Confirm P12 import and usable
code-signing identity on the GitHub runner before claiming signing readiness.
Investigate any import failure; do not replace toolchains or rotate certificates
as a workaround. OpenSSL command details:
[genpkey](https://docs.openssl.org/3.6/man1/openssl-genpkey/),
[req](https://docs.openssl.org/3.6/man1/openssl-req/),
[pkcs12](https://docs.openssl.org/3.6/man1/openssl-pkcs12/).

## Development provisioning profile and validation

In the approved team's portal choose **Profiles → + → iOS App Development**.
Select the exact explicit App ID, the validated development certificate, and
only the selected phone. Use a clear name such as `Remote Codex selected iPhone
development`, generate once, and download privately. These are Apple's
[manual development profile steps](https://developer.apple.com/help/account/provisioning-profiles/create-a-development-provisioning-profile/).

Inspect locally, without printing the plist, device list, account names, or P12.
[TN3125](https://developer.apple.com/documentation/technotes/tn3125-inside-code-signing-provisioning-profiles)
describes profile inspection and its limitations. Decode CMS into a restricted
file; verify its signature/Apple trust chain separately from merely decoding it.
Do not treat OpenSSL `-noverify` as trust validation. The checks must establish:

- `TeamIdentifier` and `com.apple.developer.team-identifier` match the intended
  Team ID, as does the signing certificate's team affiliation.
- `application-identifier` is exactly the profile's App ID prefix followed by
  `.dev.codexops.client.ios`, with no wildcard. The App ID prefix can differ from
  Team ID on older accounts; preserve the registered prefix.
- `DeveloperCertificates` contains the exact selected leaf certificate, with
  a matching private key in the P12. Confirm correspondence again after export.
- `ProvisionedDevices` is exactly the one selected UDID; `ProvisionsAllDevices`
  is absent/false; platform includes iOS; `get-task-allow` is true.
- Creation/valid-from dates are not in the future and profile/certificate are
  unexpired. Record their actual UTC expiry dates rather than assuming a year.
- The app's signed entitlements fit the profile's allowlist. The runner validates
  the actual signed app using Apple's signing tools; plist inspection alone is
  not final acceptance of the modern DER profile or code signature.

Keep a private readiness record with membership expiry, certificate fingerprint
and expiry, profile UUID and expiry, team, bundle ID, and selected-device match
result. Report only dates and pass/fail metadata to the other chats. A standard
online development profile may require the phone to contact Apple's provisioning
service on first launch; see
[profile updates](https://developer.apple.com/help/account/provisioning-profiles/provisioning-profile-updates/).

## Store material and configure GitHub

Use the op-bridge skill for targeted 1Password access. Inspect its configured
route without opening a session, then request only the needed fields. Never
fetch an Apple login password merely to document readiness. Supply item
create/edit JSON on stdin, preserve existing fields, and do not log native stderr
that might contain secrets. Store the validated encrypted P12, its password,
profile, and team in an owner-approved item. Concealed base64 fields are suitable
when the whole bridge request fits its 64 KiB encoded-input limit. The bridge
cannot transfer attachments; use the owner's native 1Password app if larger
material needs attachment storage. Do not put signing material into repository
docs or an unencrypted durable `.env` file.

Inspect `ios-development` before modifying it. Create it if absent only when
validated authorized inputs are ready. Restrict access to reviewed development
refs/owner approvals with the build chat; unsigned PR jobs receive no signing
material. Populate this agreed interface in `denta-codex/remote-codex`:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `IOS_DEVELOPMENT_CERTIFICATE_P12_BASE64` | Base64 of encrypted P12 |
| Secret | `IOS_DEVELOPMENT_CERTIFICATE_PASSWORD` | Exact P12 password |
| Secret | `IOS_DEVELOPMENT_PROFILE_BASE64` | Base64 of validated profile |
| Variable | `IOS_DEVELOPMENT_TEAM_ID` | Intended Apple Team ID |

For already validated private files, use stdin with the existing authenticated
GitHub CLI. Run each mutation once and reconcile an uncertain result before
continuing. Do not overwrite existing secrets without confirming ownership and
that replacement is intended. With tracing disabled and `pipefail` set:

```bash
base64 -w 0 "$SIGNING_DIR/development.p12" |
  gh secret set IOS_DEVELOPMENT_CERTIFICATE_P12_BASE64 \
    --repo denta-codex/remote-codex --env ios-development
gh secret set IOS_DEVELOPMENT_CERTIFICATE_PASSWORD \
  --repo denta-codex/remote-codex --env ios-development < "$SIGNING_DIR/password"
base64 -w 0 "$SIGNING_DIR/development.mobileprovision" |
  gh secret set IOS_DEVELOPMENT_PROFILE_BASE64 \
    --repo denta-codex/remote-codex --env ios-development
gh variable set IOS_DEVELOPMENT_TEAM_ID \
  --repo denta-codex/remote-codex --env ios-development < "$SIGNING_DIR/team-id"
```

The password file must contain the exact value, without an extra newline. The
base64 examples target Grace's GNU tools. Never use secret values in `--body`
arguments, echo them, or enable shell tracing. Read-only secret listing confirms
names/update times, not their hidden contents. Verify the actual material with
the signed workflow; do not automatically replay a timed-out write. See
[GitHub's secret guidance](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
and the installed `gh secret set --help`.

After secure storage and completed verification, remove the private staging
directory, extracted key/certificate files, password files, and decoded profile.
If issuance or storage remains unfinished, preserve only the required private
files and report their path/removal condition privately. Do not create redundant
recovery archives. An uncertain credential write requires a deliberate recovery
decision; never rotate automatically.

## Acceptance handoff

The build chat owns workflow files, temporary runner keychain cleanup, and IPA
export. Actual signed acceptance waits for a successful unsigned simulator/core/
fixture UI CI gate at an identified revision and run, reviewed default-branch
registration of the manual development workflow, validated GitHub inputs, and
selected-phone readiness. No App Store Connect API key is required for this
manual signing-material setup.

The artifact is `ios-development-<run_id>-<run_attempt>` with `RemoteCodex.ipa`,
`metadata.json`, and `SHA256SUMS`. Manifest keys are `schema_version=1`,
`repository=denta-codex/remote-codex`, `revision` (full SHA), `run_id`,
`run_attempt`, `artifact=RemoteCodex.ipa`, `sha256`,
`bundle_id=dev.codexops.client.ios`, `version`, `build_number`,
`signing=development`, `minimum_ios=18.0`, and `fixture_launch_argument=--fixture`.
Grace retrieves an exact run/revision with
`scripts/ios download RUN_ID EXPECTED_FULL_SHA [DEST]`. The USB chat owns device
operations and verifies the selected phone against the embedded profile before
installation.

The fixture launch uses `--fixture`, `RemoteCodexFixture/client.sqlite`, in-memory
credentials, and chat accessibility ID `chat-fixture-chat`. It avoids production
transport and Keychain. Share revision/run/checksum, certificate/profile expiry
dates, and readiness outcomes with the build/acceptance chats. Never publish the
UDID, profile contents, identity bundle, credentials, RPC payloads, or transcripts
in public CI diagnostics. This setup authorizes development signing and the
selected fixture installation; enrollment/payment, distribution, and unrelated
credential changes remain separate owner actions.
