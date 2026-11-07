# 4Link — one interface through which our apps ask each other to do things

Status: DRAFT 5, 2026-10-20. Draft 5 adds Links (§14), approved by the owner
after the 4Zones PM's review. Draft 4 added the optional `suggestion` on a
`bad_arguments` answer (§11a), for "did you mean". Draft 3 added the date line
in the skill prompt (§12) and licensed the library Apache-2.0. Draft 2 applied the owner's rulings
on draft 1 (family list by build type, submodule, "Connected apps",
command-mode trigger). Version 1.0 of the protocol.

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
- P3. Every model-chosen action that creates, changes or deletes anything is
  confirmed on screen (a "Do it / Cancel" dialog naming the app, the function
  and the arguments in plain words) before it is invoked, EXCEPT where the user
  has switched confirmation off for `create` and/or `change` in the calling
  app (owner's decision 2026-10-03; both off by default). `delete` is ALWAYS
  confirmed. A caller that skips a confirmation must tell the user what was
  done (for example in a notification). The library's default is that every
  effect but `read` needs confirmation, `create` included. Family "read"
  functions run without the dialog, because they change nothing.
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
- `effect`: `"read"` (changes nothing), `"create"` (makes something new),
  `"change"` (alters something that exists) or `"delete"` (removes
  something). A reader that does not know a value treats it as `"change"`
  (forward-compatible: it asks before running it); a missing value makes the
  entry no function.

Limits are enforced by the reader, not trusted from the writer: a title or
description over the limit is truncated on reading, and a function whose
schema uses anything outside the subset is dropped from the catalogue with a
log line.

### 3b. Always confirmed (`"confirm": "always"`)

A function may carry `"confirm": "always"`. A caller that lets its user skip
confirmation for adds or changes (§P3) must still confirm this function, every
time. It is for calls a misheard word must never run unseen, such as switching a
plug that powers a rack or moving a desk (4Home, owner 2026-10-06). A DELETE is
always confirmed anyway. Readers that don't know the field ignore it, and lose
nothing they had before.

### 3a. The user's own words (`x-from-speech`)

A string property may carry `"x-from-speech": true`: the caller may fill it
with the user's own words. When the caller has those words (4Dictate: the
request after "4Dictate, …" in dictation mode, tidied with the user's own
tidy-up), the model is told it may send a placeholder in such a field instead
of retyping the text:

- `{{said}}`: everything the user said;
- `{{said from: <first words>}}`: from those words on, to drop a spoken lead-in
  ("add this to my notes"). Matched word by word, ignoring case and
  punctuation; when not found, everything is kept, so no words are lost.

The caller substitutes before validation, so `maxLength` applies to the real
text. A placeholder in an unmarked field, or when the caller has no words, makes
the decision "none". Callers show a long value in a confirmation by its start
and its length. Why (owner, 2026-10-04): a 5½-minute note retyped by the model
lost the user's formatting, could change words, and risked the model's output
limit. Readers that do not know the keyword ignore it, as JSON Schema readers do.

### 3c. A frame beside the reply (`"frame": true`)

A function may carry `"frame": true`: its successful reply carries a FRAME, a
picture, beside its `json` (§4a). The first is 4Zones' `screen.capture`, one
picture of one screen for 4Screenshots (2026-10-09).

- **A caller that cannot consume a frame MUST NOT list a `"frame": true`
  function to a model or to a user.** 4Dictate's model must never pick
  "capture the screen" and receive pixels it throws away. Filtering is the
  caller's job, at the point it builds what the model or the user is shown.
- **Effect: a frame function declares `"read"`.** Taking a picture changes
  nothing, so P3 runs it from family without a dialog, like any read.
- **A provider MAY also mark it `"confirm": "always"` (§3b).** Read that
  carefully: `confirm` is CALLER-side. It makes the CALLING app ask ITS user
  before every invocation; the provider shows nothing (§4 forbids it UI during
  an invoke). The spec permits it for a provider that wants every picture asked
  for.
- **4Zones does not use it, on purpose.** A dialog on every capture is what its
  owner is trying to be rid of. 4Zones' answer instead is a per-app grant its
  user turns on once in 4Zones (off by default), and a notification for EVERY
  picture it hands over, naming the app and the screen. If 4Zones cannot post
  that notification, it refuses the capture. Recorded so a later reader does
  not take the missing `confirm` for an oversight.

Readers that do not know the field ignore it, so an old caller would list the
function. That is why the rule above is a MUST on callers, and why a provider
should keep any frame function to the callers it names.

## 4. Transport

Each member app exports one ContentProvider at authority `<package>.4link`
(for `uk.mr_biz.fourdictate`: `uk.mr_biz.fourdictate.4link`). It is used only
through `ContentProvider.call(method, arg, extras)`; query, insert, update
and delete answer nothing. Three methods:

| Method | arg | extras in | Bundle out | Who may call |
|---|---|---|---|---|
| `hello` | – | – | `app` (name), `4link` ("1.0"), `caller` ("family" / "paired" / "unknown") | anyone; reveals no functions |
| `catalogue` | – | – | `json` (the catalogue; for a paired caller, only its granted functions) | family or paired |
| `invoke` | function id | `json` (arguments), `version` (major the caller read), `origin` (§4c) | `ok` (Boolean) and `json` (result) — and `frame` (§4a) from a `"frame": true` function — or `ok`=false with `error` (code) and `message` (a sentence for the user), for `bad_arguments` optionally `suggestion` (§11a), and for `refused` or `failed` optionally `reason` (§11b) | family, or paired and granted |

A call that reaches a function runs inside the provider app, on a binder
thread; a function that needs the main thread posts to it and waits, with a
timeout, as 4Zones' door does. The provider never shows UI during an invoke.

### 4c. Who chose the call (`origin`)

Why: one family caller can carry both a model and the user's own triggers (4Dictate has a model
and fixed voice commands; 4StreamDeck has keys). Some functions, or some argument values, must
run only when the user triggered them, never on a model's choice: 4Zones' system keys (Back,
Home, Recents, scroll, screenshot through `actions.run`) and `keys.press`. A provider cannot
tell the two apart from the call alone (owner's go for the system keys, 2026-11-07).

- `invoke` takes an optional extra `origin`: `"user"` when the user triggered this exact call (a
  key or gesture they pressed, a fixed voice command mapped to a fixed action, a link they ran),
  or `"model"` when a model chose the function or filled its arguments.
- A family caller MUST set it on every invoke. A missing or unknown value, and every call from a
  paired caller, is read as `"model"`.
- A provider may refuse a function, or a particular argument value, unless `origin` is `"user"`.
  It answers `refused` with a sentence and `reason` `"not_for_model"` (§11b, not fixable). It
  keeps such functions and values visible in its lists, so a surface can still put them on a key.
- `origin` is the caller's HONEST DECLARATION of provenance, not a proof. No provider can verify
  that a person pressed anything. It is trusted only because a family caller is already trusted
  by its certificate (§5a), and the risk it guards against is an honest app's model reaching
  something it should not, not a hostile caller, which could lie about anything. A provider must
  not rely on it against a caller it would not otherwise trust, and must not build a security
  control on it.
- A model-facing description never names a value that is refused to `"model"` (no enum or list
  of such ids), so the model is never offered it.

Discovery: every member declares an activity with the intent filter action
`uk.mr_biz.4link.action.PAIR` (section 6). A caller lists those activities
(`PackageManager.queryIntentActivities`, with a matching `<queries>` entry in
its manifest) and derives each member's authority from its package name.
Family members declare the same filter so one discovery serves both kinds.

### 4a. A reply's frame

A `"frame": true` function's `ok` reply carries ONE frame under the Bundle key
`frame`, and never otherwise. A function that declares a frame always sends
one; a frame from a function that does not declare one is the provider's bug,
and the library answers `failed` instead of passing it on.

- **What it is:** an `android.os.SharedMemory`, set read-only (`PROT_READ`)
  before it leaves the provider. The receiver cannot map it writable.
- **What describes it:** the reply's `json`, never a header inside the frame.
  For pixels: `width`, `height`, `stride` (bytes per row) and `format`, from a
  closed list (`"RGBA_8888"` today). The caller reads the layout; it never
  infers it.
- **Why shared memory, and not `openFile` or a pipe:** it keeps the single
  door of §4, because it arrives in the reply to the call that was already
  identified and gated, with no second door, token or expiry. It is never a
  file on disk. And the provider has finished when it replies; it is not
  blocked on a binder thread while the caller drains megabytes. A 1920×1080
  frame is 8,294,400 bytes, eight times a binder transaction; base64 in JSON
  would be 11 MB of text.
- **Measured** (2026-10-09, API 37 emulator, two apps, so two uids): every
  byte arrived, and the receiver's `mapReadWrite` was refused.
- **The caller owns the frame it receives and closes it.**

**A frame is an image of the user's screen, and two rules bind BOTH sides:**

1. **The provider MUST honour `FLAG_SECURE`.** A window an app has protected
   from screenshots comes out black, as it does in any screenshot. (4Zones'
   route, `screencap` at shell uid, does: measured on an emulator and on a
   Galaxy Tab A9+, with controls. DRM-protected video is a separate hardware
   path, and was not tested.)
2. **Neither side may persist a frame beyond the call without the user's own
   action.** The provider keeps no copy. The caller may hold it while it does
   what the user asked, and saves it only because the user chose to (pressed
   save, accepted the picture), never by default, in a cache, or "for later".
   This rule binds the caller as much as the provider.

### 4b. One retry, and only where running twice is harmless

After a minute or so idle, Android's freezer may freeze a member. A call that
then reaches the frozen process can make Android KILL it ("Sync transaction
while frozen"); the caller sees no answer, and a second call starts a fresh
member and succeeds. Measured by the 4Screenshots implementer on an API 37
emulator, 2026-10-09, twice, and a third time through the library's own retry
below, which recovered it: the immediate retry started a fresh member that
answered 580 ms later, and the call succeeded (1,349 ms end to end). No delay
is needed. Not reproduced on a second API 37 image in two runs, where the
frozen member was thawed and answered (the killed member had been cached at
oom adj 905), so the exact conditions are not known. It is a defect measured in one place and reasoned to be
general. It is NOT offered as the explanation for any other missed call.

The library's client (`FourLinkClient`) therefore retries a failed call
ONCE, immediately, with a fresh acquire, under this rule:

- **The provider could not be acquired:** nothing reached it, so nothing ran.
  Retried, whatever the function's effect.
- **The transaction failed** (`DeadObjectException`, `RemoteException`): the
  frozen kill arrives this way, but so does a provider that ran the call and
  died before replying, and the two are the same exception class. So it is
  retried ONLY for `hello`, `catalogue` and a function whose declared effect is
  `read`, where running twice changes nothing.
- **A timeout, or any answer at all, is never retried.** A `create`, `change`
  or `delete` that may have run must not run twice.

**A hello before a change, so the change meets a live process and never has to
be retried.** Invoked in the declared form (with its `FunctionSpec`), any
function that is not `read` is preceded by a `hello`. The hello is a read, so
it carries the retry: if the member was frozen, the hello absorbs the kill, its
retry restarts the member, and the change goes to a live process. **If the
hello fails even after its retry, the change is not sent** and the hello's
failure is returned: a change is never attempted into a process the caller has
just failed to reach (the 4Dictate PM's condition, 2026-10-09). A read gets no
hello: it already retries itself. The id-only invoke sends no hello, as before.

The effect is the function's DECLARED one, from the catalogue, never a flag
the caller passes. A caller that invokes by id alone, without the function's
declaration, gets no binder-failure retry. The client keeps no provider client
between calls, so there is no idle connection to expire. A retry is logged
with the provider's package and the exception's class, never its message.

## 5. Caller identity

The provider identifies every caller from `Binder.getCallingUid()`, then the
package(s) of that uid, then the SHA-256 digest of the package's current
signing certificate (`PackageManager.GET_SIGNING_CERTIFICATES`). A package
name is never trusted on its own: a different developer can publish an app
with the same package name under a different certificate, and that app must
be a stranger. Every decision below is made on the (package, digest) pair.

### 5a. Family

The caller is family when its certificate digest is in the family LIST the
library carries as a resource (`fourlink_family_digests`). The list holds one
release key PER APP (owner's decision 2026-10-03: one set of credentials per
app), in every build: 4Dictate's release certificate
`7ffc5b0df6b4ffb420d8965db8e041fa4398b534608142d110885b9e67cfd8d9` (the same
digest 4Zones pins) and 4Tasks' own release certificate
`92c51b99e38eb2f498375cd912cea900203ce6a19069d33ce8384203a1c2ad06`; plus the workstation debug
certificate in DEBUG builds only (`src/debug`). Every app carries the whole
list, so each trusts the others; an app joins the family by being added to the
list and rebuilding the members. A test holds the two files to
that, as 4Zones' ControlPinTest does. The list is never "whatever signed me":
a release build must not widen the family by being built elsewhere. Family
callers have full access to the catalogue.

Play App Signing: every 4Link app published on Google Play MUST be enrolled
with OUR OWN release key uploaded through PEPK, never a Google-generated key.
Play re-signs installs with the enrolled key; if it is not ours, Play installs
of our apps carry a different certificate, are not in the family list, and
every family check between them fails. (4Dictate's enrolment is in
play/PLAY_CONSOLE.md.)

## 6. Pairing (user-approved, for non-family callers)

- Who asks: a caller sends an explicit intent, action
  `uk.mr_biz.4link.action.PAIR`, to the provider app's pairing activity, with
  the string-array extra `uk.mr_biz.4link.extra.FUNCTIONS` listing the
  function ids it wants (or the effect classes `read`, `create`, `change`, `delete` to
  mean every function of that class).
- The provider app shows a pairing screen ONLY in response to that intent,
  never on its own: the requesting app's name and icon; a short fingerprint
  of its signing certificate (the first 16 hex digits of the digest, in
  groups of four); each requested function in plain words, grouped Read /
  Create / Change / Delete, each with what data it receives (its input fields); and
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
- Limits: a per-app rate limit across `catalogue` and `invoke` of 30 calls a minute for a paired
  app and 120 for a family app (owner, 2026-11-07; §15f) (`hello` is not counted: it is a constant
  reply), answered `rate_limited` (`state.get` has its own limit, §15c); and an audit log of every
  call (section 9).

## 7. The other direction: a family caller using a non-family provider

4Dictate may read the catalogue of, and call, a third-party provider only
after the user has approved it in 4Dictate's own "Connected apps" page
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
- requires the confirmation in P3 for every non-family `create`, `change` or `delete`,
  whatever the description or the model says, and runs no non-family function
  the user did not approve, whatever the model says.

The same guard applies to family catalogues in code; it costs nothing and
makes the family case the same path as the paired case.

## 9. Audit log

Every provider keeps a bounded log (the last 500 calls): time, caller
package, method or function id, and the result code. Argument values and
results are NEVER logged. A frame (§4a) is a result: it is never logged, by
either side, in any form, not even a thumbnail. The log is visible in the
provider's Settings next to the "Paired apps" list, and is included in a diagnostics report only in
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
| `rate_limited` | Over the caller's limit in the last minute: 30 for a paired app, 120 for family |
| `refused` | The provider declined for its own reason (a switch off, no session) |
| `failed` | The function ran and failed; the sentence says why |

`catalogue` answers `not_paired` or `rate_limited` the same way. `hello`
never fails.

### 11a. Suggestions ("did you mean")

Sometimes a provider can see what the caller probably meant but must not act on
a guess: the user said "mark the sounder task done" and the provider has a task
called "Get back to Sandra Elaine…". It answers `bad_arguments`, as always, with
a `message` that stands on its own (a caller that has never heard of
suggestions shows it and nothing else), and may add ONE optional Bundle key:

| Key | Value |
|---|---|
| `suggestion` | a JSON object as a string: `{"question": "Did you mean “Get back to Sandra Elaine about her reservation”?", "function": "tasks.complete", "arguments": {"id": 4}}` |

- `question`: one sentence for the user, at most 200 characters.
- `function`: the id of a function in THIS provider's catalogue (usually the one
  just called). `arguments`: its arguments, valid against that function's input
  schema.
- Meaning: NOTHING was done. If the user agrees, the caller invokes `function`
  with `arguments`.

The provider never acts on a suggestion by itself, and never offers one that
would do something the caller's own request did not ask for (a "did you mean"
for a completion offers a completion, not a deletion).

The caller:

- treats `question` as text from the provider, like a description (§8): for a
  non-family provider it is truncated, shown as third-party text, and never
  obeyed;
- looks the function up in the catalogue it already read and approved, takes
  the effect from THAT, and ignores any claim in the suggestion; a function it
  has not read or the user has not approved is dropped;
- validates `arguments` against the function's input schema (§8); if invalid,
  the suggestion is dropped and the plain `message` is shown;
- for `create`, `change` and `delete` asks the user (P3), naming the app, the function
  and the arguments in plain words as well as showing `question`; a "yes" to the
  question alone is not a confirmation of arguments the user cannot see;
- invokes at most once for one suggestion, with no automatic retry and no
  chaining: an answer to that call may carry a new suggestion, which needs the
  user's yes again.

Unknown keys are ignored (§10), so this changes no protocol major. A suggestion
reveals information from the provider's data (here, a task title) to the caller,
so a provider offers one only to a caller that could read that data anyway:
family, or a paired app granted a read function that returns it.

### 11b. A reason beside the sentence (`reason`, `fixable`)

A `refused` or `failed` reply MAY carry two things beside its sentence:

- `fixable` (Boolean): **true only when the user can fix this in the
  provider's own settings**, false otherwise (a device without power, a server
  that is switched off: the fix is real but not in the app). A fixable
  refusal is always shown, by every caller, because the user can act on it.
  A caller that HAS another way to do the job may fall back quietly when
  `fixable` is false or absent (4Screenshots falls back to the sharing prompt). A caller that has
  NO other way (a key press, a voice command, a tap) always shows the
  provider's sentence, and adds that it can be fixed in the provider's
  settings only when `fixable` is true. It works on a reason the caller has
  never seen, so a caller is not updated each time a provider's vocabulary
  grows.
  Why (owner, 2026-10-21): a key press refused because a light had no power
  showed nothing, and a voice command was told to look in settings for a
  light switch.
- `reason` (token): a short, stable name for the refusal, for a caller that
  needs more than `fixable`.

The sentence is for a person, is shown as it is, and may be reworded at any
time. **A caller that needs to tell one refusal from another branches on
`fixable` or `reason`, never on the sentence.** Matching a provider's copy is
how a caller breaks the day the copy improves. Why (2026-10-09): 4Zones
answered every `screen.capture` refusal as `refused`, and the 4Screenshots
caller could tell "turn this on" from "nothing you can do" only by matching
4Zones' draft sentences.

**The core vocabulary.** Unprefixed tokens are shared, so providers do not
invent synonyms for one thing:

| Token | Meaning |
|---|---|
| `not_set_up` | Something the function needs has never been set up |
| `permission_off` | A permission or switch the function needs is off |
| `not_found` | The thing asked about does not exist |
| `busy` | The provider is doing something else; try again |
| `unavailable` | The provider cannot do it right now |
| `rate_limited` | Asked too often |
| `too_large` | The request or its answer is too large |
| `user_declined` | The user said no |
| `not_for_model` | Runs only when the user triggered it, and this call's `origin` (§4c) was not `"user"` |

- **A provider's own tokens MUST be prefixed with the provider:**
  `4zones.grant_off`. An unprefixed token MUST be a core token. That is what
  keeps a provider's token from colliding with a core token added later. The
  library enforces it on the provider side: a token a provider may not declare
  is dropped, and the sentence and `fixable` still go.
- **Shape:** lower case; an optional `<provider>.` prefix (letters, digits,
  `_`); then a letter, then letters, digits or `_`; at most 64 characters in
  all. A reader accepts any well-formed token, core or not, known or not, so a
  core token added later still reads.
- **Frozen once shipped,** like a function id (§3). A changed meaning is a new
  token.
- **The catalogue MAY list a function's reasons** (`"reasons": [...]`). It is
  optional and never a promise of completeness: a required list would have to
  be exhaustive to be useful, and a stale one is worse than none.
- Only on `refused` and `failed`: the other codes are the library's own and
  already say exactly what happened. The audit log (§9) records the code, as
  before, not the token.

## 12. How 4Dictate uses it

- 4Dictate discovers 4Link apps whenever it needs them and caches their
  catalogues, refetching when an app was installed or updated since (its
  last-update time) and dropping an app when it is removed. Family
  catalogues are used at once; other apps appear in "Connected apps" for the
  user to approve.
- Where it happens (owner, 2026-10-02): in COMMAND mode only, as the last
  resort after the fixed command table, the 4Zones names and "open <app>"
  have all matched nothing. Dictation mode is unchanged: what you say is
  typed.
- The "skill" path: the words and the usable catalogues go to the tidy-up
  model (the same provider cascade tidy-up uses) with an instruction to reply
  with exactly one function call as JSON (`{"function": id, "arguments":
  {...}}`) or `{"none": reason}`. The reply is validated against the schema
  before anything happens. The instruction carries one line giving the
  current moment — weekday, date, 24-hour time, time zone id and UTC offset —
  so "tomorrow at 3" is resolved against it, and tells the model to answer
  dates and times as ISO-8601 local time with no zone or offset; the caller
  must pass the moment (`SkillPrompt.instructions(sources, now)` has no default).
- Effect `create`, `change` or `delete`: a confirmation in 4Dictate's ConfirmButton
  style (app, function title, arguments in plain words, Do it / Cancel).
  Family `read` runs directly, and its result is shown.
- Execution is ONLY an `invoke` on the target app's provider. The result is
  reported on screen and on the dictation's card in Transcriptions, with the
  timings (the model call, the confirmation wait, the invoke) in the ⏱
  panel's own "4Link" section.
- If no function fits, 4Dictate answers exactly as today, "Heard … — not a
  command. Nothing was typed.", and keeps the words on the card. It never
  guesses, and never falls back to driving the screen.

## 13. The library

A small Android library module, `uk.mr_biz.fourlink`, no UI and no
accessibility code, used by every member app: catalogue data classes and
JSON; argument validation; caller identification by certificate digest; the
pairing store; the provider-side gate (family / paired+granted / refused)
with rate limit and audit log; the provider base class; and a client
(discover, hello, catalogue, invoke). Everything that decides is pure Kotlin
behind interfaces, unit-tested on the workstation, including a spoofed
package name with a different certificate. It lives in this repository
(`library/`, github.com/mr-bizzy/4link, private) and member apps include it
as a git SUBMODULE at `4link/` in their own repository (owner, 2026-10-02),
pointing `settings.gradle` at `4link/library`.

## 14. Links

Why: an app that binds keys and gestures (4Zones' Links page) should be able to offer the things
another family app can do for the user's own setup, with nothing to type (4Home: "Desk up",
"John's room Relax"). Today 4Zones' family links are fixed inside 4Zones, which suits fixed links
but not links that come from the user's Home Assistant.

A link is a 4Link call: one of the providing app's own functions with its arguments filled in.
It changes nothing about how 4Link works. The link list is read with an ordinary call, and a link
is run with an ordinary `invoke`, so caller identity (§5), family (§5a), pairing and grants (§6),
and `confirm` (§3b) apply exactly as they do today.

### 14a. The function

`links.list`, version 1.0, effect `read`, no input. Output `{ "links": "<JSON array as text>" }`
(text, because the schema subset has no arrays, §3). Each element:

    { "id": "light.john_s_room/scene.john_s_room_relax",
      "name": "John's room Relax",
      "group": "John's room",
      "function": "home.scene.start",
      "version": "2.0",
      "arguments": { "entity": "scene.john_s_room_relax" } }

- `id`: unique within the app, at most 200 characters, never changes: made from what the link
  does, never from a name the user can change. Renaming a thing renames the link and keeps any
  key bound to it.
- `name`: for people, at most 60 characters.
- `group`: optional, at most 60 characters, a heading to list links under.
- `function`, `version`, `arguments`: the call. The function must be in the provider's own
  catalogue; `arguments` is a JSON object.
- No icon. The caller shows the provider's app icon. (Never a bitmap in a reply: a bitmap in a
  shortcut reply crashed the caller, 4Zones L12.)
- Order: the array's order is the order. Groups appear in the order of their first link.

The reader enforces the limits (§3): an element over a limit, ill-formed, or with a duplicate
`id` is dropped with a log line; the rest are kept.

### 14b. Using links

- The caller keeps only the provider's package and the link's `id`, plus what is the user's own:
  the key or gesture, and any name the user gave it. Everything else (name, group, the call) is
  taken from the latest list each time, never from a stored copy.
- A stored `(package, id)` that is not in the current list is ABSENT: not shown as the provider's,
  not run, and its key stays bound so it works again if the link comes back. "Delete" keeps the
  caller's own meaning (the user's act).
- When a key is pressed, the caller invokes the call from the latest list for that `id`. If the
  call needs confirming (§3b), the caller asks first, as for any call.
- The caller re-reads the list when it shows its links, and when the provider is installed or
  updated. Links are not pushed (§15 pushes state, not links).
- The caller tells the user which of three things happened when a link does nothing, using the
  §11b reason: the link is absent from the list; the provider refused it (with its sentence, and
  whether the user can fix it); the provider did not answer.
- The caller never edits the provider's list.

### 14c. Who does what

- Library: the element format (parse with the limits, build), unit-tested.
- 4Home: the user chooses, with a toggle per object and state, which ones are links (owner,
  2026-10-20). `links.list` lists only those, each the same call `home.object.list` gives for that
  state. The toggles start off and are kept in 4Home's backup file. Why: the user offers the few
  links they will bind, not every state of every device, so a caller's Links page is not flooded.
- 4Zones: reading `links.list` needs a new kind of link row and a firing path through the 4Link
  client; the 4Zones PM scopes it, later. When 4Zones reads it, 4Zones becomes a 4Link caller and
  its release certificate joins the family list (§5a), on the owner's word, with the digest taken
  only from the Play Console's App signing key certificate on the page whose Digital Asset Links
  JSON names `uk.mr_biz.fourzones`.
- 4Screenshots: unchanged. Its two links stay fixed in 4Zones (it has no 4Link provider, and its
  links are addresses, not calls), and 4StreamDeck reaches them through 4Zones' links. It may
  become a provider later (`links.list`, `icons.get`, and the state in §15e), in one release,
  when the 4Zones PM schedules it. 4Dictate's own screenshot action stays on offer beside
  4Screenshots' two (owner, 2026-11-07: "do not drop screenshot").

## 15. Pictures and state (for control surfaces)

Why: 4StreamDeck shows family actions as keys on a phone, a tablet or an Elgato deck. Every key
needs a picture, and the owner ruled (2026-11-07) that each app supplies its own, because a
picture is a claim about what the action does and an app owns its claims (§5a). Some keys must
also show what an app is doing now: 4Dictate's microphone key lights while it listens. Until
now 4Link pushed nothing (§14b). This section adds one way for an app to say "this changed",
shared by every member, so no app needs a private wire. Owner's go: 2026-11-07.

Both parts are ordinary functions in the provider's catalogue. A provider that lacks them answers
`unknown_function`, and the caller manages without (its own picture, no light).

### 15a. Plumbing, not for a model

`icons.get`, `state.get` and `state.subscribe` serve a surface, not a person's request. A caller
that offers functions to a model (§12) leaves out every function whose id starts with `icons.`
or `state.`, and refuses to invoke one the model names anyway.

### 15b. Pictures: `icons.get`

Version 1.0, effect `read`. Input `{ "ids": "<JSON array as text>" }` (text, because the schema
subset has no arrays, §3), at most 32 ids a call. Output
`{ "icons": "<JSON object as text>" }`:

    { "snap1": { "d": "M3 3h8v8H3z", "evenodd": false },
      "tasks.add": { "d": "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z" } }

- `ids`: what the provider itself lists for a key: a function id from its catalogue, or an id
  from its own list (4Zones' `actions.list`).
- A picture is one SVG path on a fixed 24 x 24 view box: `d` (at most 4096 characters) and
  optional `evenodd` (fill rule, default false). Nothing else travels: no colour, no stroke, no
  bitmap, no XML.
- The reader checks `d` against the SVG path grammar: the commands `MmLlHhVvCcSsQqTtAaZz`,
  numbers, spaces and commas, and nothing else. A picture over the limit or outside the grammar
  is dropped with a log line, and the rest are kept.
- An id may have no picture. A provider answers only the ids it has. A missing id is not an
  error, and the caller draws its own (4Zones' generated keys, such as a layout's real shape or
  a numbered desk, are the caller's to draw from `layouts.list`).
- The caller colours the picture to its own scheme, so a surface keeps one palette, and the
  user may replace any picture with their own. That choice is the caller's setting.
- The caller keeps pictures by (package, versionCode) and asks again only when the provider
  is installed or updated, so a first sight costs one call per 32 keys.

### 15c. State: `state.get`, `state.subscribe` and the ping

A provider names a few STATE KEYS (`4dictate.mic`, `4zones.access`). Each has a token from a
fixed set, which says what the user should notice, never how it looks:

| Token | Meaning |
|---|---|
| `idle` | Nothing to show |
| `listening` | Taking the user's input now (a microphone open) |
| `working` | Busy with something the user asked for (transcribing) |
| `attention` | Needs the user (an error, a permission gone) |

A state may also carry `extra`: one word in the provider's namespace (`4dictate.command`),
at most 40 characters, which a caller may use or ignore. The caller decides what each token
looks like, and the user's own colours win. An unknown token is read as `attention`.

`state.get`: version 1.0, effect `read`. Input `{ "keys": "<JSON array as text>" }`, optional;
with none, every key the provider has. Output `{ "states": "<JSON object as text>" }`:

    { "4dictate.mic": { "token": "listening", "extra": "4dictate.command", "seq": 41 } }

`seq` starts, in each provider process, at that process's start time in milliseconds and rises
by 1 with every change of that key (and never falls within a process, even if the clock steps
back), so it rises across restarts too. A caller ignores a reply older than one it already has.

Keys and extras are `<namespace>.<word>`, where the namespace is the provider's own (`4dictate`)
and the whole is lower case letters, digits, `_` and one dot, at most 40 characters. A provider
refuses to set a key or extra outside its namespace, or a token outside the four. A reader drops
a malformed extra and keeps the token.

`state.subscribe`: version 1.0, effect `read` (it changes nothing of the user's). Input
`{ "keys": "<JSON array as text>" }`. Output `{ "keys": "<JSON array as text>", "seconds": n }`:
the keys the provider has, and how long the subscription lasts (the provider chooses, at most
86400; a renewal restarts the clock). Keys the provider does not have are left out; a subscribe
naming only unknown keys stores nothing and leaves an existing subscription alone. The provider
keeps subscriptions on disk, so they survive its own restart. A subscription ends when it runs
out, when the subscriber's package is removed, or when the subscriber subscribes again with an
empty list. A subscriber renews when it starts a session, and well before `seconds` runs out.

The ping: when a subscribed key changes, the provider sends an EXPLICIT broadcast to each
subscribed package (`Intent.setPackage`), action `uk.mr_biz.4link.action.STATE`, extras
`package` (the provider), `keys` (the changed keys, a string array) and `seq` (the highest).
It sends at most one ping per key every 100 ms: the first change goes at once, and changes inside
the window send one ping at its end with every key still pending and the highest `seq`. Each
subscriber gets one broadcast naming only its own keys. The provider does not wait for delivery.
`state.subscribe` counts in the caller's limit (§6); only `state.get` is outside it.

- The ping carries no token. A broadcast's sender cannot be proven, so a ping only says "ask
  me". The subscriber then calls `state.get`, which is gated like any call (§5, §5a). A forged
  ping costs one gated read and shows nothing false.
- Never `ContentResolver.notifyChange`. Any app may observe a URI, and "4Dictate's state
  changed" would tell a stranger app when the user starts speaking.
- Family callers only, in this version. A paired caller is answered `not_granted` for
  `state.get` and `state.subscribe`, whatever its grant. A user's activity going to another
  developer's app is data under P4 and needs its privacy line first.
- A subscriber keeps at most one `state.get` in flight per provider. A ping that arrives during a
  read is answered by one more read when it ends, so a flickering key costs at most two reads per
  burst and the last read is the latest. A read that fails (`rate_limited` or anything else) is
  tried again after 1 s, doubling to at most 30 s, until one succeeds or a new ping supersedes it,
  so a light is never left wrong without a read pending. Its receiver is registered exported
  (`RECEIVER_EXPORTED`), since the ping comes from another app.
- `state.get` is not counted in the caller's limit (§6). It has its own limit of 120 a minute per
  caller, so a busy mic light never uses up a surface's key presses.
- A subscriber that needs live state keeps itself running (a foreground service) while its
  surface is shown. Since Android 14 a frozen app's context-registered receivers get broadcasts
  only when it thaws (Samsung freezes a background app about 6 s after the last key, measured on
  the owner's S25), so a frozen surface shows the light late. This is the subscriber's job. The
  provider does not change for it.

### 15f. Levels: sliders and dials

Why: the owner plans sliders and dials on 4StreamDeck for things with a level, such as a light's
brightness, a desk's height or a blind's position (2026-11-07). A token says what to notice,
not how much.

- A state may carry `level`: a number from 0 to 100, beside its token (a light at 40 %:
  `{ "token": "idle", "level": 40, "seq": 7 }`; on and off stay in the token, see below). A reader drops
  a `level` outside 0 to 100 and keeps the token.
- On and off: a state may carry `on`, a boolean, so a toggle key shows its state. A light that
  is on and needs nothing is `{ "token": "idle", "on": true, "level": 40 }`.
- A link (§14a) may name `level`: the one number argument of its call that a slider or dial
  fills, 0 to 100 (`"level": "brightness_percent"`). The caller puts the user's value into that
  argument and runs the call as any link. A link without `level` is a key, not a slider.
- A link may name `state`: the provider's state key that shows it (`"state":
  "4home.light.desk_lamp"`), so the caller knows which state lights or positions that control.
- A slider sends at most one call per 250 ms while it moves. It MUST send the final value when
  the gesture ends, even if that falls inside the 250 ms; the final value is the one that has to
  land.
- What the state reports is authoritative. While the user's gesture is live, the caller shows the
  user's value and ignores the state's `level`; when the gesture ends, it shows the state's again,
  so a slow bulb never fights the finger and the control ends showing what the thing really is.
- A link may carry `short`: a key-cap label, at most 16 characters ("Kitchen"), for a surface
  with no room for `name`. Without it, the caller shortens `name` itself.
- Rate: these calls count like any invoke (§6). Family callers get 120 a minute; paired callers
  stay at 30 (owner, 2026-11-07), because a 2 s sweep is about 9 calls and a dial that stutters
  is worse than none.

### 15d. Measured before sign-off

The time from a provider's state change to the subscriber holding the new token, on the owner's
S25 in DeX: with the subscriber shown in the foreground, in the background with a foreground
service, and in the background without one. Median and worst of at least 20 changes each. The
numbers go in this section before §15c reaches open testing. Internal testing may carry it, since
Play is the only way onto the owner's devices and so the way it gets measured.

### 15e. Who does what

- Library: `icons.get` (reading with the limits and the path grammar, building), `state.get`,
  `state.subscribe` (the store on disk, expiry, removal with the package), the ping (sending,
  coalescing, a receiver helper for subscribers), the 120 a minute limit, and the §15a filter,
  all unit-tested. The 4Dictate PM's.
- 4Dictate: provides `4dictate.mic` (`idle`; `listening`, with `extra` `4dictate.command` for a
  command; `working` while transcribing, which needs adding; `attention` on a capture error).
  As a caller, it applies §15a. The 4Dictate PM's.
- 4Zones: provides `icons.get` for its fixed actions and `4zones.access` (`attention` when its
  window access is down). The 4Zones PM's.
- 4Screenshots: may provide `4screenshots.capture` (`working` while a capture is in flight,
  `attention` when refused, `idle` otherwise) in the release that makes it a provider. The 4Zones
  PM's.
- 4StreamDeck: the caller, with pictures, user overrides, subscriptions and the foreground
  service. The 4Zones PM's.
