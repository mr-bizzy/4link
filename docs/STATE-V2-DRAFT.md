# 4Link state, version 2 (draft)

Status: DRAFT for the owner. Semantics: the 4Dictate PM. Budget, visibility and migration: the
4Zones PM (sections 8 to 10, theirs to write). Owner, 2026-11-21: "MQTT protocol needs adopting in
4link to solve this - research and discuss with 4Zones PM", then "go" to the joint recommendation:
MQTT's behaviour, not MQTT's wire.

This changes §15c, §15f and §15g. Where this draft says nothing, they stand.

## 1. Why: measured on the owner's S25, 2026-11-21

| Time | What happened |
|---|---|
| 21:49:30 to 21:50:39 | The desk moved. 4Home published its height about every 0.2 s (4Home 0.1.27 log) |
| Same minute | 4Zones answered each ping with catalogue + links.list + 2 to 3 state.get + state.subscribe: **221 calls in one minute** into 4Home |
| 21:49:34 to 21:50:26 | 4Home's gate refused 4Zones **56 times** (`rate_limited`): 51 catalogue and **5 of the owner's own desk presses** |
| 21:50:26 | 4Zones stopped reading. The dot froze high while the desk went down |
| 20:38 to 21:30 | After an earlier refusal, 4Zones dropped its subscriptions to every provider and was deaf for 52 minutes (the dictation key's colour stuck) |

Four faults in v1's shape caused this, and each is something MQTT does differently:

| v1 | Consequence | MQTT | v2 (this draft) |
|---|---|---|---|
| A ping carries no value; the subscriber reads back | Every change costs at least one call, at the change rate | PUBLISH carries the payload | §3 value pings |
| The first value comes from a separate read after subscribing | A gap, and a call | Retained messages | §4 snapshot in the subscribe reply |
| A subscription can end without the subscriber knowing | Deaf until something re-arms it | Sessions, keepalive, will | §6 birth and ended pings, §7 |
| Coalescing is 100 ms for every key | A 5 Hz desk is 5 pings a second | QoS 0, latest wins | §5 per-kind gaps |

## 2. Why not MQTT itself

MQTT needs a broker. On one phone it would live in a foreground service inside one app, and
every other app would then depend on that app. That breaks the owner's rule that each paid app
works alone (2026-11-07), and makes the family's state only as alive as one app the user may have
stopped. A broker in every app is worse: nobody knows which one to use. Identity is NOT the
reason: a unix-domain socket gives the peer's uid (`LocalSocket.getPeerCredentials`), as binder
does (the 4Zones PM's correction). Supporting reasons: an always-open socket per app costs
battery under Android's background limits, and the Android MQTT clients are either stale (Paho)
or heavy across five apps (HiveMQ).

So v2 keeps 4Link's transport (a gated ContentProvider call, an explicit broadcast) and takes
MQTT's behaviour.

## 3. The value object, defined once

Every state value travels as one object, the SAME shape everywhere it appears (state.get's
output, the subscribe reply, a ping):

    { "key": "4home.light_hall", "token": "idle", "level": 40, "on": true, "seq": 1795301234567 }

`key`, `token` and `seq` always; `extra`, `level`, `on` and `read` when §15c, §15f and §15g give
them. `seq` keeps its v1 meaning, made strict: a provider never emits a `seq` less than or equal to the
last it emitted for that key, across restarts AND reboots, whatever the clock says. Gaps are
allowed. The library persists enough to guarantee this; it need not record every value: one
floor per provider on disk, written at process start as (current + a margin) and again only as
the counter nears it, and each start begins above the stored floor (the 4Zones PM's design: a
write per start, not per change). A reader keeps, per key, the value with
the highest `seq` and drops any older one, whichever way it came, except after a birth (§6). Readers parse
it with v1's limits and drop malformed fields as v1 does.

## 4. Value pings (the PUBLISH)

A ping carrying `values` goes to ONE subscriber package by `Intent.setPackage`, never as an
implicit broadcast. v1 already sends it this way (measured on the S25: `pkg=uk.mr_biz.fourkeys`),
but in v2 the payload is in the broadcast, so this is now what keeps the owner's lamp levels from
any app that registers the action; it is a rule, not an inheritance (the 4Zones PM's point).

A v2 ping is a v1 ping with one more extra, so a v1 subscriber still works (it ignores the extra
and reads back, as today):

- `values`: text, a JSON array of value objects (§3), one per changed key named in the ping, at
  most 8 KB. Over 8 KB, the provider leaves `values` out and the ping is a plain v1 ping.

Sender proof. A broadcast's sender cannot be proven in v1, which is why v1 pings carry nothing.
From Android 14 (API 34) it can: the provider sends with
`BroadcastOptions.setShareIdentityEnabled(true)`, and the subscriber's receiver checks
`getSentFromUid()` against the provider's package and the family list, as the gate does (§5a).

- A subscriber uses `values` ONLY when the sender is proven to be that provider and family.
  Otherwise, or below Android 14, it treats the ping as v1 (one state.get, as §15c already says).
  A forged ping still costs one gated read and shows nothing false.
- Why not a family permission (`knownSigner`, discussed with the 4Zones PM): a permission is
  defined by one app, and our apps are signed with different keys (one key per app, owner
  2026-10-03). Two apps defining the same permission fails to install, and a permission defined
  per provider is not granted to a subscriber installed before it. The sender check has neither
  problem and every family phone that matters (the owner's S25, Max's S23) is on Android 14+.
  The 4Zones PM to confirm.

A subscriber that has a value from a proven v2 ping does NOT call state.get for it. That is the
whole saving: one change, one broadcast, no call. state.get stays for the snapshot (§4a) and for
v1 providers, never as a routine second path for the same value (the 4Zones PM's rule: one
path per value, so the two cannot drift).

### 4a. The snapshot (the retained message)

`state.subscribe`'s output gains `states`: the current value object (§3) of every key it
accepted, the same as state.get would return. The subscriber applies it through the SAME handler
as a ping, by `seq`. A ping that overtakes the reply is not lost: the higher `seq` wins whichever
arrives first.

## 5. Coalescing: latest wins, in the library

The library holds back changes per key and sends the latest, never a queue:

| Change | Gap per key | Why |
|---|---|---|
| The token, `extra`, `on` or a link-affecting key changed | 100 ms (v1) | A mic light must be quick |
| Only `level` changed | 1000 ms | A dot needs one update a second, not five (owner, item 199) |

The first change goes at once. Changes inside the gap send one ping at its end with the latest
value. The final value always goes. This is the library's code in every app, not a setting a
provider chooses, so a subscriber does not have to trust a provider to behave. A subscriber
still caps its own reads (section 8), because a provider may carry an older library.

## 6. Birth and ended (the will)

- Birth. When a provider's process starts and its stored subscriptions (§15c, on disk) are
  loaded, the library sends each live subscriber one ping with `birth` = true and the values of
  all its keys. A restart no longer goes unnoticed. A birth RESETS the subscriber's `seq`
watermark for that provider: its values are applied as they are, not filtered by `seq`, so a
provider whose `seq` came back lower (a lost store, a bad clock) is never ignored for good. The
subscriber also subscribes again on a birth, since a provider that lost its store has nothing to
renew against, and re-reads that provider's catalogue (§7).
- Ended. When a provider ends a subscription for any reason other than the subscriber's own
  empty subscribe (it ran out, the provider cleared its store, a key was withdrawn), it sends
  that subscriber one ping with `ended` = true and the keys that ended. The subscriber subscribes
  again at once if it still wants them. Silence never means "unsubscribed".

## 7. A refusal is never an unsubscription

- A subscriber keeps its list of wanted keys through any failure. A refused or failed
  state.subscribe or state.get (`rate_limited` or anything else) is retried after 1 s, doubling
  to at most 30 s, until it succeeds (v1 already says this for state.get; v2 says it for
  subscribe too). The retry can never become its own storm: per provider, at most ONE retry is
  pending at a time, whatever failed and however many keys are waiting; a `rate_limited` answer
  restarts that one retry at no less than 10 s; and all retries to one provider together send at
  most 6 calls a minute. This floor is the library's (the 4Zones PM's point), not each app's. It sends an empty subscribe only when the user's own action means nothing is
  watched any more (no key on any page names that provider's state), never because a call
  failed and never because a surface went to the background.
- A subscriber renews well before `seconds` runs out, on a timer, not only when something
  happens to re-arm it.
- A subscriber caches each provider's catalogue and reads it again only when the provider's
  version changes or on a birth (§6), never before a routine subscribe, renewal or read. Of the
  56 refusals on 21 Nov, 51 were `catalogue` calls made before re-subscribing (the 4Zones PM's
  count); this makes their removal a rule rather than a side effect.
- A ping that changes only `level` for a key this subscriber process has already seen never
  makes it read `links.list` or `catalogue`. A key the process has never seen, or a change to
  anything else about it (`token`, `on`, `extra`), may cost one links.list, so a row that has
  vanished or changed is noticed. That is bounded by the number of rows, once per key per process.
  The same key buying links.list twice for level-only pings in one process is a defect (the 4Zones
  PM's rule; measured on the S25 2026-11-22: one per key after 4Zones 122 restarted).

## 8. The budget: the user's press always gets through

**This is the only section here that protects the owner rather than the plumbing, and it is the
one that should ship first.**

### 8.1 What happened, measured

On the owner's S25 on 21 November 2026, while his desk travelled, 4Zones made **221 calls into
4Home in one minute**. `FourLink.RATE_LIMIT_FAMILY_PER_MINUTE` is **120**, and it counts
`catalogue` and `invoke` **together**. 4Home's gate refused 4Zones **56 times** with
`rate_limited` — 51 of them `catalogue`, and **five of them `home.cover.act`: the owner's own desk
presses**, at 21:49:46, :49, :58 and 21:50:01, :03.

He pressed his key and nothing happened, because housekeeping had spent his allowance.

**No amount of v2's thrift fixes that by itself.** Sections 4 to 7 make the flood much less likely;
they cannot make it impossible. A provider that publishes faster than anyone expected, a subscriber
with a bug, a burst after a long disconnection — any of them can fill a shared budget, and today
the thing that loses is the press.

### 8.2 The rule

**A call the user themself caused must never be refused because background traffic has used the
allowance.**

4Link already carries what is needed and nothing new is invented: §4c puts `origin` on every call,
`user` or `model`, and the gate's limiter is already keyed per caller.

* The per-caller budget stays **120 a minute for a family caller**, unchanged.
* **Background calls may draw on at most `120 − RESERVED`.** Proposed `RESERVED = 20`.
* **A call with `origin: user` may draw on the whole 120.**

So background can take 100 and no more; at least 20 remain, and only a user's own call can reach
them. A press cannot be starved by a dot.

Everything that is not the user's own action is background, with no exceptions worth carving out:
`catalogue`, `state.subscribe`, `links.list`, `icons.get`, and any `invoke` the calling app decided
to make on its own.

**`state.get` is NOT in this bucket and is deliberately left out of it.** `Gate.gateInvoke` answers
it from its own limiter — `State.GET_PER_MINUTE`, 120 a minute — and returns before the shared
limiter is consulted at all. So **a read cannot starve a press by construction**, which is a
stronger guarantee than a reserve and needs nothing added. Moving it into the shared bucket would
make a dot able to compete with a press, which is the fault this section exists to remove.

What that does NOT mean is that reads are free: his desk at 5 Hz asked for about 300 a minute
against that 120, so reads were refused too — the cost of which is a stale dot, not a dead press.
Sections 5 to 7 are what hold that down.

**`catalogue` carries no `origin` at all** — `Gate.gateCatalogue(caller)` takes none — so it is
always background. Stated so that nobody later adds one to it.

### 8.3 Why this cannot be gamed into starving somebody else

`origin` is **a declaration, not a proof** (§4c says so in those words), so a caller could mark
everything `user`. That is bounded, and the bound is what makes the rule safe:

**the limiter is keyed on the caller's package**, so a caller that lies about `origin` spends only
its own 120. It cannot reach another app's reserve, and the provider's own work is not drawn from
a caller's budget at all. The worst a lying family app achieves is to starve itself — and family
callers are apps we signed, which is the same trust the family list already rests on.

Two properties this rests on, both checked in the library rather than assumed:

* **`RateLimiter` is a per-key sliding window keyed on `caller.packageName`**, and family apps do
  not share a uid (one signing key per app), so no caller's package can alias another's bucket.
* **A refused call is not counted** — `allow` returns before it records the attempt — so a caller
  being refused cannot drive itself, or anybody, further into refusal.

**A paired (non-family) caller keeps its 30 a minute, unsplit.** There is no reserve to give it:
`Origin.read` returns `USER` only when the caller's standing is `FAMILY`, so a paired app's calls
are always `model` and a reserve it could never reach would be a rule that reads as protection and
is not.

### 8.4 A refused user call is SAID, never retried

**The library must not retry a call with `origin: user`.** §7's back-off is for background only.

This is the 4Dictate PM's correction and it is right: under §7's floor a refused press could land
ten to thirty seconds later, so **his desk would move after he had stopped expecting it**. A
physical thing acting late is worse than a thing that did not act — a dot that is stale is a
picture, a desk that rises unbidden is a surprise in the room.

So a refused user call ends there, and **the surface says so**. That is what §9's counters are
for, and it is why they are not optional: without them, "refused and not retried" looks exactly
like "did nothing", which is precisely what the owner suffered on 21 November — he pressed, nothing
happened, and nothing anywhere said why.

With §8.2's reserve in place a `rate_limited` refusal of a user call should be close to impossible.
The other reasons a user call can be refused — `not_granted`, `unknown_function`, `bad_arguments` —
are not fixed by waiting, so there was never anything for a retry to buy.

### 8.5 Open for the owner

`RESERVED = 20` is a proposal, not a measurement. It is about six presses a second for ten seconds,
which is far more than a hand produces; the cost is that background drops from 120 to 100, and
after sections 4 to 7 the measured background is well under 20. **If he wants a different split,
the number is one constant.**

---

## 9. Refusals visible on a release build

### 9.1 What happened

Those 56 refusals happened **in silence**. Nothing on the owner's phone, in either app, said that
anything was being refused. They were found only because 4Home had gained a logging line that
afternoon for an unrelated reason, and because the other PM counted them by hand.

Meanwhile the owner spent an evening reporting symptoms — a stuck key colour, a frozen dot, a dead
press — that were all one cause, and both PMs between us produced six mechanisms for it, of which
five were wrong. **A counter would have replaced four hours with one look.**

### 9.2 The rule

**A gate that refuses a call counts it, and the count is readable on a release build.**

* The library's gate keeps, per caller and per reason code, the number of refusals in the current
  minute and a running total since process start.
* Reasons are §4's existing codes — `rate_limited`, `not_paired`, `not_granted`,
  `unknown_function`, `bad_arguments`, `refused`, `failed` — with no new vocabulary.
* **Counters, not log lines.** A count is cheap, bounded and free of anything about what the user
  was doing. Logging each refusal would put a caller's package name into a released log, which
  4Zones' own §H10 forbids, and would bury the signal in exactly the flood it is there to report.

### 9.3 Where they are read

Two ways, and the second is what makes this worth building:

* **The provider's own diagnostics surface.** Each app already has one.
* **A caller can ask about itself.** A family caller may read *its own* refusal counts from a
  provider. That is not a new permission: it is telling an app what that provider has already done
  to that app, and nothing about anybody else.

The second one lets a subscriber notice it is being refused and say so, instead of going quiet. A
surface that has stopped being told anything currently looks exactly like a surface where nothing
has changed — which is precisely how this hid.

### 9.4 What is not in them

No task id, display id, focused-task id, desk or root id, input-device identifier, argument value
or key name. A reason code and a number. That keeps them inside 4Zones' §H10 and means they can be
shown to the owner without a judgement call each time.

---

## 10. Migration

### 10.1 The constraint that decides everything

**Five apps, updated one at a time, by one person, at times he chooses.** 4Zones, 4Keys, 4Dictate,
4Home and 4PDFs each go to Play separately and reach his phone separately. §14's links sat unadopted
for weeks for exactly this reason.

**So: no change may require two apps to move together, and there is no day on which anything
switches over.**

### 10.2 The version is the library's, not the app's

An app's own version says nothing about what it can speak. What matters is which 4Link library it
carries, and that is what the catalogue's `version` already reports (§10). Nothing new is needed:
a v2 library announces itself the way every version has.

### 10.3 The four combinations, all of which must work

| provider | subscriber | what happens |
|---|---|---|
| v1 | v1 | exactly as today |
| **v1** | **v2** | the ping carries no `values`, so the subscriber reads back — today's behaviour, minus the subscriber's own waste |
| **v2** | **v1** | the extras are additive and a v1 subscriber ignores them, so it reads back as it always did |
| v2 | v2 | the full saving |

**Every extra is additive and optional.** `values`, `birth` and `ended` are extras on a ping that
was already valid without them; `states` is a new field on an answer that was already valid without
it. A reader that does not know a field drops it, which is v1's own parsing rule (§3 says readers
use v1's limits).

**A v2 subscriber must not require a v2 provider.** The test is simple and worth stating: with
every provider pinned to v1, a v2 subscriber behaves as a v1 one, and the only difference is that
it makes fewer calls of its own.

### 10.4 The order of adoption, and what each step buys on its own

Nothing waits for anything else:

1. **The library ships.** Nothing changes until an app takes it.
2. **A subscriber takes it** (4Zones first, since it is the one that flooded). It gains §7's
   retry-is-not-an-unsubscription, §8's reserve on its own outgoing calls, and §9's counters.
   **Buys something with every provider still on v1.**
3. **A provider takes it** (4Home first, since it has the only 5 Hz source). Its pings start
   carrying values, and any v2 subscriber stops reading back.
4. **The rest follow whenever he uploads them.**

### 10.5 What must be measured before it is called done

§12's list, plus one: **with a v1 provider and a v2 subscriber, the dot still moves.** That is the
combination most likely to be broken by accident and least likely to be tested by hand, because
every device in the family will be v2 within a week of adoption and the case stops being
reachable.

### 10.6 What we are NOT doing

* **No flag day, no coordinated release, no "v2 mode" setting.**
* **No change to §15c's existing wire** beyond additive extras — a v1 ping stays a valid v2 ping.
* **No MQTT broker** (§2), and no family broadcast permission: the apps are signed with different
  keys, so a shared permission cannot install and a per-provider one is not granted to a subscriber
  installed before it. §4's sender identity replaces it.

## 11. Who does what

- Library (4Dictate PM): the value object, value pings with identity sharing, the sender check
  helper for receivers, the snapshot in the subscribe reply, per-kind coalescing, birth and ended,
  all unit-tested. One library version for all five apps.
- 4Dictate and 4Home (4Dictate PM): providers; take the library, nothing else to change.
- 4Zones, 4Keys (4Zones PM): subscriber rules in sections 4, 4a, 6 and 7, the read cap, and
  sections 8 to 10.
- **§8 needs one change in the library before it can be enforced (4Dictate PM):** the origin is
  read at `ProviderCore` *after* `gate.gateInvoke` has already decided, so `gateInvoke` gains an
  origin parameter. The gate cannot reserve for a user's call while it is told who called only
  afterwards.

## 12. Measured before sign-off

On the owner's S25, the desk moved bottom to top and back, with a 4Keys dial on screen:
calls from 4Zones into 4Home per minute (v1 measured 221; the target is under 20), refusals
(target 0), every press landing, and the dot ending where Home Assistant says the desk is.
Then §15d's latency numbers again, for the mic light.
