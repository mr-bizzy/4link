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
last it emitted for that key, across restarts AND reboots (the library stores the last `seq` per
key on disk and starts above it, whatever the clock says). A reader keeps, per key, the value with
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
- A ping for a key that only carries `level` or `on` never makes the subscriber read
  `links.list` or `catalogue`. Only a key that a link names for `offered`, `name`, `short` or
  `icon` (§15f) does, and then only links.list.

## 8. The budget: the user's press always gets through

The 4Zones PM's section. Agreed in outline: a user's own action (an invoke with origin `user`)
gets a budget that background traffic (catalogue, state, links.list) cannot use up; refusals
counted where both PMs can read them on a release build.

## 9. Refusals visible on a release build

The 4Zones PM's section.

## 10. Migration

The 4Zones PM's section. Agreed in outline: the version is the library's; extras are additive;
v1 provider with v2 subscriber and v2 provider with v1 subscriber both keep working; no day on
which two apps must update together.

## 11. Who does what

- Library (4Dictate PM): the value object, value pings with identity sharing, the sender check
  helper for receivers, the snapshot in the subscribe reply, per-kind coalescing, birth and ended,
  all unit-tested. One library version for all five apps.
- 4Dictate and 4Home (4Dictate PM): providers; take the library, nothing else to change.
- 4Zones, 4Keys (4Zones PM): subscriber rules in sections 4, 4a, 6 and 7, the read cap, and
  sections 8 to 10.

## 12. Measured before sign-off

On the owner's S25, the desk moved bottom to top and back, with a 4Keys dial on screen:
calls from 4Zones into 4Home per minute (v1 measured 221; the target is under 20), refusals
(target 0), every press landing, and the dot ending where Home Assistant says the desk is.
Then §15d's latency numbers again, for the mic light.
