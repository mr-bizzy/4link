# 4Link — one interface through which our apps ask each other to do things

Status: DRAFT 1 for the owner's agreement, 2026-10-02. Nothing in it ships
until agreed. Version 1.0 of the protocol.

## 1. What it is

4Link is the shared interface through which Android apps ask each other to do
things on the user's behalf. It follows the MCP pattern: each member app
publishes a CATALOGUE of functions (a name, a plain-language description
written for an AI model, typed inputs as JSON Schema, an output, and whether
the function reads, changes or deletes anything). A caller discovers
catalogues and invokes functions by name with JSON arguments.

It is our own equivalent of Android's AppFunctions. AppFunctions' calling
permissions (`EXECUTE_APP_FUNCTIONS`, `EXECUTE_APP_FUNCTIONS_SYSTEM`) are
reserved for privileged system agents, so they are unavailable to us.

Two kinds of member:

- FAMILY apps: ours (4Dictate, 4Zones, a future task app), signed with our
  key. Trusted automatically.
- PAIRED apps: anyone else's. Trusted only after the user approves a pairing,
  limited to what the user approved, and revocable at any time.

4Zones' existing B75 door (`listActions`/`runAction` over a bound service,
`signature|knownSigner`) is version 0 of this idea, and its action ids
(snap1–snap4, desk1–desk4, nextDesk, previousDesk, addDesk, open:*, layout:*,
workspace:*, page:*) are the model for a catalogue. 4Zones joins 4Link later,
through the 4Zones project manager; this document changes nothing in 4Zones.

## 2. Google Play policy — the line 4Link never crosses

4Dictate and every 4Link app are published on Google Play. Play's
AccessibilityService API policy (enforced since 28 January 2026) says: "Any
use of the Accessibility API that enables an app to autonomously initiate,
plan, and execute actions or decisions is strictly prohibited", including an
"LLM agent that books, buys, messages, or changes settings". Deterministic,
rule-based automation ("if X, do Y") is allowed. 4Link is designed so that a
reviewer can check each of the following against the code:

- P1. 4Link calls travel ONLY over Android IPC (`ContentProvider.call`). A
  model's choice of function is executed by the TARGET app, inside its own
  process, through 4Link. 4Dictate never taps, types into or otherwise drives
  another app's screen through its accessibility service to carry out a
  model's decision. The 4Link library contains no accessibility code at all.
- P2. 4Dictate's accessibility service keeps its existing, disclosed jobs
  only: typing the user's dictation into the focused field, and running fixed
  voice commands (a spoken phrase mapped to a fixed action). 4Link adds no
  accessibility-driven behaviour. The accessibility disclosure and
  `play/ACCESSIBILITY_SPEC.md` do not change.
- P3. Every model-chosen action that changes or deletes anything needs the
  user's explicit confirmation on screen (a "Do it / Cancel" dialog naming the
  app, the function and the arguments in plain words) before it is invoked.
  The app therefore never acts on its own. Only family "read" functions run
  without the dialog, because they change nothing.
- P4. Data passed to non-family (paired) apps is declared: the Play Data
  safety answers and the 4Dictate privacy policy are updated before 4Link
  ships, and the pairing/approval screens show what each function receives.
  `play/DATA_SAFETY.md` and `play/PRIVACY_POLICY.md` are kept in step; the
  website copy is never published without the owner's explicit "publish".
- P5. This section states the reasoning plainly so that it can be given to a
  Play reviewer as it stands. Summary for the reviewer: the model only picks
  a function from a fixed list and fills its arguments; the user confirms
  anything that changes state; the receiving app does the work in its own
  process over ordinary Android IPC; the accessibility service is not
  involved in any of it.

## 3. Catalogue format

A catalogue is a JSON object:

```json
{
  "4link": "1.0",
  "app": "4Link Echo",
  "functions": [
    {
      "id": "echo.note",
      "version": "1.0",
      "title": "Save a note",
      "description": "Keeps a short note the user dictated, for later.",
      "effect": "change",
      "input": {
        "type": "object",
        "properties": { "text": { "type": "string", "maxLength": 500 } },
        "required": ["text"]
      },
      "output": {
        "type": "object",
        "properties": { "count": { "type": "number" } }
      }
    }
  ]
}
```

Per function:

- `id`: `<area>.<verb>`, lower case, letters, digits and dots ("tasks.add").
  Frozen once shipped; a changed meaning is a new id or a new major version.
- `version`: `major.minor`. A minor bump adds optional fields or output keys;
  a major bump changes meaning, required inputs or effect.
- `title`: for people, up to 60 characters ("Save a note").
- `description`: for the model, up to 300 characters, plain language, saying
  what the function does and what the inputs mean.
- `input`: a JSON Schema SUBSET. Allowed: `object` (with `properties`,
  `required`), `string` (with `maxLength`, `enum`), `number`, `boolean`.
  Nothing else: no arrays, no nesting below one object level, no `$ref`.
- `output`: the same subset, describing the `json` the function returns.
- `effect`: `"read"` (changes nothing), `"change"` (creates or alters
  something) or `"delete"` (removes something).

Limits are enforced by the reader, not trusted from the writer: a title or
description over the limit is truncated on reading, and a function whose
schema uses anything outside the subset is dropped from the catalogue with a
log line.

## 4. Transport

Each member app exports one ContentProvider at authority `<package>.4link`
(for `uk.mr_biz.fourdictate`: `uk.mr_biz.fourdictate.4link`). It is used only
through `ContentProvider.call(method, arg, extras)`; query, insert, update
and delete answer nothing. Three methods:

| Method | arg | extras in | Bundle out | Who may call |
|---|---|---|---|---|
| `hello` | – | – | `app` (name), `4link` ("1.0"), `caller` ("family" / "paired" / "unknown") | anyone; reveals no functions |
| `catalogue` | – | – | `json` (the catalogue; for a paired caller, only its granted functions) | family or paired |
| `invoke` | function id | `json` (arguments), `version` (major the caller read) | `ok` (Boolean) and `json` (result), or `ok`=false with `error` (code) and `message` (a sentence for the user) | family, or paired and granted |

A call that reaches a function runs inside the provider app, on a binder
thread; a function that needs the main thread posts to it and waits, with a
timeout, as 4Zones' door does. The provider never shows UI during an invoke.

Discovery: every member declares an activity with the intent filter action
`uk.mr_biz.4link.action.PAIR` (section 6). A caller lists those activities
(`PackageManager.queryIntentActivities`, with a matching `<queries>` entry in
its manifest) and derives each member's authority from its package name.
Family members declare the same filter so one discovery serves both kinds.

## 5. Caller identity

The provider identifies every caller from `Binder.getCallingUid()`, then the
package(s) of that uid, then the SHA-256 digest of the package's current
signing certificate (`PackageManager.GET_SIGNING_CERTIFICATES`). A package
name is never trusted on its own: a different developer can publish an app
with the same package name under a different certificate, and that app must
be a stranger. Every decision below is made on the (package, digest) pair.

### 5a. Family

The caller is family when its certificate digest equals one of ours: the
stored family digest (4Dictate's release certificate,
`7ffc5b0df6b4ffb420d8965db8e041fa4398b534608142d110885b9e67cfd8d9`, the same
digest 4Zones pins) or the certificate the provider app itself is signed
with. The second rule makes two debug builds family to each other on a
workstation without ever shipping a debug digest: a release build is signed
with the release key, so its own digest IS the family digest. Family callers
have full access to the catalogue.

## 6. Pairing (user-approved, for non-family callers)

- Who asks: a caller sends an explicit intent, action
  `uk.mr_biz.4link.action.PAIR`, to the provider app's pairing activity, with
  the string-array extra `uk.mr_biz.4link.extra.FUNCTIONS` listing the
  function ids it wants (or the effect classes `read`, `change`, `delete` to
  mean every function of that class).
- The provider app shows a pairing screen ONLY in response to that intent,
  never on its own: the requesting app's name and icon; a short fingerprint
  of its signing certificate (the first 16 hex digits of the digest, in
  groups of four); each requested function in plain words, grouped Read /
  Change / Delete, each with what data it receives (its input fields); and
  the choice to approve all, approve some, or refuse. Delete-class functions
  are unticked by default. The requesting app is identified from the
  activity's calling package AND its certificate, so the screen names the app
  that actually sent the intent.
- What is stored, per paired app: package, certificate digest, the granted
  function ids, the date, the 4Link version, and the time each granted
  function was last called. If the caller's certificate later differs from
  the stored one, the pairing is void: it is removed, the call is answered
  `not_paired`, and the caller must ask again.
- A call outside the grant answers `not_granted`; a call from an unpaired app
  answers `not_paired`. Both are logged (section 9).
- Revoking: every provider app has a "Paired apps" list in its Settings
  showing what each app may do and when it last did it, with a Remove button
  that asks first. Uninstalling the caller removes its pairing (the provider
  listens for the package's removal and also drops a pairing whose package is
  no longer installed when it next checks).
- Limits: a per-app rate limit of 30 calls a minute across `catalogue` and
  `invoke` (`hello` is not counted: it is a constant reply), answered
  `rate_limited`; and an audit log of every call (section 9).

## 7. The other direction: a family caller using a non-family provider

4Dictate may read the catalogue of, and call, a third-party provider only
after the user has approved it in 4Dictate's own "Apps 4Dictate may use" list
(Settings). The approval screen shows the app, its certificate fingerprint,
its functions grouped by effect with the data each receives, as in section 6,
with Delete unticked by default. What is stored mirrors a pairing: package,
digest, approved function ids, date. A provider whose certificate changes is
dropped from the list and must be approved again. Family providers are used
automatically and do not appear in the list.

Approving a provider in 4Dictate does not pair 4Dictate with it: the provider
still shows its own pairing screen when 4Dictate asks (section 6). Both sides
must agree, each with the user, before a third-party call can happen.

## 8. Untrusted descriptions (the prompt-injection guard)

A non-family catalogue's titles and descriptions are DATA, never
instructions. The caller:

- truncates them to the limits in section 3 before the model sees them;
- passes them to the model inside a clearly delimited block marked as
  third-party text that may be wrong or hostile, separate from the caller's
  own instructions;
- allows the model only to choose one function from the list and fill its
  arguments, or to answer "none" — the reply is parsed as JSON, validated
  against the chosen function's input schema, and anything else (prose, two
  functions, a function not in the approved list) is treated as "none";
- requires the confirmation in P3 for every non-family `change` or `delete`,
  whatever the description or the model says, and runs no non-family function
  the user did not approve, whatever the model says.

The same guard applies to family catalogues in code; it costs nothing and
makes the family case the same path as the paired case.

## 9. Audit log

Every provider keeps a bounded log (the last 500 calls): time, caller
package, method or function id, and the result code. Argument values and
results are NEVER logged. The log is visible in the provider's Settings next
to the "Paired apps" list, and is included in a diagnostics report only in
that form.

## 10. Versioning

- The catalogue carries the 4Link protocol version (`"4link": "1.0"`). A
  caller that reads a major it does not know ignores the whole catalogue.
- Each function carries its own `major.minor`. The caller sends the major it
  read with every `invoke`; a provider serving a different major answers
  `unknown_function`. A caller must not invoke a function whose major it did
  not read from a catalogue.
- Unknown fields anywhere (catalogue, arguments, results, Bundles) are
  ignored, so a minor version can add without breaking.

## 11. Errors

`invoke` answers `ok=false` with one of these codes and a sentence:

| Code | Meaning |
|---|---|
| `unknown_function` | No such id, or a different major version |
| `bad_arguments` | Arguments fail the input schema (the sentence names the field) |
| `not_paired` | Caller is neither family nor paired (or its pairing was voided) |
| `not_granted` | Paired, but this function is outside the grant |
| `rate_limited` | Over 30 calls in the last minute |
| `refused` | The provider declined for its own reason (a switch off, no session) |
| `failed` | The function ran and failed; the sentence says why |

`catalogue` answers `not_paired` or `rate_limited` the same way. `hello`
never fails.

## 12. How 4Dictate uses it

- 4Dictate discovers 4Link apps on start and on every package install or
  removal, and caches their catalogues. Family catalogues are used at once;
  other apps appear in "Apps 4Dictate may use" for the user to approve.
- The "skill" path: the transcript and the usable catalogues go to the
  tidy-up model (the same provider cascade tidy-up uses) with an instruction
  to reply with exactly one function call as JSON
  (`{"function": id, "arguments": {...}}`) or `{"none": reason}`. The reply is
  validated against the schema before anything happens.
- Effect `change` or `delete`: a confirmation in 4Dictate's ConfirmButton
  style (app, function title, arguments in plain words, Do it / Cancel).
  Family `read` runs directly.
- Execution is ONLY an `invoke` on the target app's provider. The result is
  reported on screen and on the dictation's card in Transcriptions, with the
  timings (model, confirmation wait, invoke) in the ⏱ panel.
- If no function fits, 4Dictate says so and keeps the words on the card. It
  never guesses, and never falls back to driving the screen.

## 13. The library

A small Android library module, `uk.mr_biz.fourlink`, no UI and no
accessibility code, used by every member app: catalogue data classes and
JSON; argument validation; caller identification by certificate digest; the
pairing store; the provider-side gate (family / paired+granted / refused)
with rate limit and audit log; the provider base class; and a client
(discover, hello, catalogue, invoke). Everything that decides is pure Kotlin
behind interfaces, unit-tested on the workstation, including a spoofed
package name with a different certificate. It lives in this repository
(`library/`) and member apps include it by path.
