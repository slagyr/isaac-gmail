# isaac.comm.gmail — Gmail comm

You are a crew running inside Isaac. This chapter covers **isaac-gmail**: the
comm that watches one Gmail INBOX, turns each thread into a session, gates
and routes inbound mail, and sends replies and new mail through
`comm__send`. Read `isaac.foundation` first if you haven't (config paths,
`handbook__configure`, hot reload); read `isaac.agent` for crews, tools, and
`comm__send` itself — this chapter only covers what's specific to Gmail.
isaac-gmail is built on **isaac-google**, which owns all auth and Pub/Sub
plumbing — see `isaac.google` for organizations, OAuth login, the push
door, and registration renewal; this chapter only covers the one line Gmail
contributes to that renewal (Watch and delivery mode, below).

## The Gmail comm

**What it is.** A Gmail comm is an entry in the `comms` table (`type
:gmail`, or, on a slot literally named `gmail`, `type` can be omitted).
Each entry watches exactly one Google mailbox and speaks for exactly one
Google organization (`isaac.google#google-organizations`). Every gated
INBOX message maps to a session keyed by its Gmail *thread* id — one
thread, one session, never one session per sender or address: replying
continues that thread's session; a new thread starts a new one.

Fields on a comm entry: `gmail/account` (the watched mailbox's email,
informational — identity actually comes from the organization's signed-in
token), `gmail/google` (which Google organization this comm speaks for;
omit on a single-organization host), and `gmail/crew` (the crew a message
starts a turn on when its matched route names none of its own, or when no
`gmail-routes` are configured at all).

**How to change it.** Using the fictional *Marigold* crew and a placeholder
mailbox:

```
config set comms.gmail.gmail/account isaac@example.com
config set comms.gmail.gmail/crew cordelia
```

**How to verify.** `config get comms.gmail` shows the entry as written; no
restart needed — changes take effect on the next push or pull tick.

### Troubleshooting

- **A message starts a turn on the wrong crew.** Check whether a matched
  `gmail-routes` entry named its own `:crew` — that wins over `gmail/crew`,
  which is only the fallback.
- **Two Gmail comms seem to fight over the same mailbox.** Each entry
  should name a distinct `gmail/account`/`gmail/google` pair.

## Inbound routing: gmail-routes

**What it is.** `config:gmail-routes` is the whitelist for what inbound
mail does: a named table of routes — one `.edn` file per route under
`config/gmail-routes/`, or inline — evaluated by `:order` ascending (ties
broken by name); the first match wins. A message matching nothing
configured, including every message when the table is empty, is
`:unrouted`: nothing converses. There's no separate allow-list anymore — a
`:converse` or `:task` route's own `:match :from` **is** the whitelist, and
a route missing it is a config error naming the route (isaac-sb6d,
isaac-3427).

A few signals gate a message to `:ignore` before any route runs, and
always win over a route that would otherwise match: `Precedence:
bulk|list|junk`, `Auto-Submitted` set to anything but `no`, a
`List-Unsubscribe` header, or a Gmail category label in
`gmail/ignore-categories` (default the four non-personal categories).

A route's `:match` map ANDs every key it sets; an omitted key matches
anything: `:to`/`:from` are globs (`*` only, case-insensitive) — a
`*@domain` `:from` is honored only when Gmail's own Authentication-Results
vouch for the sender's domain (dmarc pass, or spf+dkim both pass and
aligned); a domain claim Gmail doesn't back is a drop, logged `:warn
:gmail/message-dropped :reason :unauthenticated`, not a quiet non-match.
`:subject` is a regex; `:label` a Gmail label the message must carry;
`:list-id` an exact match on the `List-Id` header.

`:action` is `:converse` (start/continue the thread's session), `:ignore`
(label and stop), or `:task` (send a hail — see Task routes). Every gated
message — routed, ignored, or unrouted — gets a Gmail label
(`isaac/<route>`, `isaac/ignored`, `isaac/unrouted`; prefix and read-marking
configurable, see Labels) applied *before* any turn starts. That label is
both the audit trail and the idempotency check letting a push host and a
pull host (Watch and delivery mode) share one inbox safely — a message
already carrying an `isaac/`-prefixed label is skipped outright on any
later pass.

**How to change it.** Using the fictional *Marigold* crew:

```
config set gmail-routes.ops.order 10
config set gmail-routes.ops.match.from *@marigold.example
config set gmail-routes.ops.action converse
config set gmail-routes.ops.crew cordelia
```

`:comm` on a route restricts it to one named Gmail comm slot, once more
than one mailbox is configured.

**How to verify.** `config get gmail-routes` lists every configured route.
`isaac config validate` reports an unknown `:action`, or a
`:converse`/`:task` route missing `:match :from`, naming the route. A
route file added or edited under `config/gmail-routes/` takes effect on
the next message — no restart.

### Troubleshooting

- **A message that should route somewhere lands `:unrouted`.** Check
  `:order` against an earlier, broader route that might match first — first
  match wins regardless of specificity. If the sender is a `*@domain`
  pattern, confirm Gmail actually authenticated it before assuming the
  pattern is wrong.
- **A `*@domain` route never fires on mail that looks legitimate.** Gmail's
  Authentication-Results didn't show `dmarc=pass`, or both `spf=pass` and
  `dkim=pass` aligned to the From domain — Gmail's own verdict, not
  something Isaac can override.
- **A message is silently skipped on every pass.** It already carries an
  `isaac/`-prefixed label from an earlier pass — the idempotency check
  working as intended. `gmail/allow-from` is retired; express the same
  intent as a route's `:match :from` instead. `[verify]`

## Triage fallback for unrouted mail

**What it is.** `config:gmail/triage` is an optional, model-backed fallback
for mail `gmail-routes` couldn't place. When `gmail/triage.model` is set, a
message that comes back `:unrouted` (and wasn't dropped as unauthenticated)
runs one tool-less, single-cycle turn in a dedicated, reset-context session
(`gmail-triage`, recreated before every call so its transcript never grows)
instead of going straight to `:unrouted`. The model sees the configured
route names (plus each route's own `:desc`) and the literal choice
`"ignore"`, and must answer with exactly one; anything else falls back to
`gmail/triage.default` (default `"ignore"`). The verdict is always recorded
as `isaac/triage/<verdict>`, regardless of `:apply`. With
`gmail/triage.apply true`, the message is redispatched as if the verdict's
own route had matched, so that route's own label and action land too — the
default `false` is audit-only: you see the verdict label but nothing else
happens. Triage never overrides a route `gmail-routes` already matched;
it only runs on the `:unrouted` case.

**How to change it.** Using the fictional *Marigold* crew and routes named
`ops`/`newsletters`:

```
config set gmail/triage.model quantum-anvil
config set gmail/triage.crew cordelia
config set gmail/triage.choices '["ops" "newsletters" "ignore"]'
config set gmail/triage.default ignore
config set gmail/triage.apply false
```

`gmail/triage.crew` defaults to the operator's default crew if omitted; its
`:tools` are always replaced with deny-all for the triage call, regardless
of what that crew normally allows.

**How to verify.** Send (or simulate) a message no route matches and check
`gmail-triage`'s session transcript and the message's
`isaac/triage/<verdict>` label.

### Troubleshooting

- **Triage never seems to run.** Confirm `gmail/triage.model` is set —
  that's the single switch. Also confirm the message is genuinely
  `:unrouted`, not `:blocked` (unauthenticated `*@domain`) — a blocked
  message is dropped with a warning, never handed to triage.
- **The verdict is always the default, never a real route.** The model's
  raw answer isn't landing exactly on one of `gmail/triage.choices` or
  `"ignore"` — check for a mismatch (trailing punctuation, wrong case).
- **`apply true` doesn't seem to actually converse.** Confirm the verdict
  names a route still configured and spelled exactly as in `:choices` —
  `"ignore"` or an unmatched name means "do nothing further."

## Task routes: mail as a hail

**What it is.** A route with `:action :task` turns a gated message into a
hail on a band instead of starting a thread session — "file this, don't
converse" mail. The message is labelled `isaac/<route>` first, the same
idempotency check as any other route, then becomes a hail record:
`gmail/id`, `gmail/thread-id`, `from`, `subject`, and a `body-excerpt`
(capped by `gmail/task-body-cap`, default 4000 characters — the full
message stays readable through `gmail__read` by id) merged under the
route's own `:params` (mail fields win on a key collision). Hail is a
separate module resolved at runtime, since isaac-gmail doesn't depend on
it directly: if it isn't installed, a task route degrades to a warn log
and an `isaac/<route>/unsent` label rather than raising. With `:ack true`
it also replies once on the thread ("Got it — filed as `<route>`:
`<subject>`."); the default `false` sends no reply.

**How to change it.**

```
config set gmail-routes.invoices.order 20
config set gmail-routes.invoices.match.from *@marigold.example
config set gmail-routes.invoices.match.subject "(?i)\\binvoice\\b"
config set gmail-routes.invoices.action task
config set gmail-routes.invoices.band ops-inbox
config set gmail-routes.invoices.ack true
config set comms.gmail.gmail/task-body-cap 2000
```

`:band` is required on a `:task` route — an unnamed band is a config error.

**How to verify.** Check the hail band received a hail carrying the
expected params and that the message carries the route's label. If the
hail module isn't installed, check for `isaac/<route>/unsent` and the
`:gmail.route/hail-unavailable` warning in the log instead.

### Troubleshooting

- **A task route's hail never arrives.** Confirm the hail module is
  installed (`isaac modules list`) — a task route degrades quietly rather
  than failing loudly when it isn't.
- **The body excerpt is truncated unexpectedly.** Check
  `comms.gmail.gmail/task-body-cap` — it's a courtesy preview; read the
  rest with `gmail__read` by the message's `gmail/id`.
- **A duplicate hail was expected but never sent.** A message already
  carrying its route's label is treated as already-handled — the shared
  push/pull idempotency check, not a missed hail.

## Watch and delivery mode

**What it is.** `gmail/mode` decides how this comm learns about new INBOX
mail: `:push` (default) registers a Pub/Sub watch through isaac-google's
shared registration mechanism (`isaac.google#registrations-and-renewal`) —
Gmail's `users.watch`, one per mailbox, renewed on isaac-google's hourly
reconcile timer, good for up to seven days at a time. `:pull` instead
schedules an interval task (`gmail/pull`, default every
`gmail/pull-interval-ms` = 60000ms) that walks Gmail history from a stored
cursor the same way the push handler does — same gating, routes, and
labels — with no watch, topic, or public HTTP door required; a signed-in
login alone suffices, and a `:pull` comm is excluded from watch
reconciliation. A first pull tick with no stored cursor seeds it from the
mailbox's newest history id and processes nothing — no backfill flood. A
failed pull tick logs a warning and leaves the cursor for the next tick to
retry; it never raises.

Whichever mode, the cursor itself (the last Gmail `historyId` processed)
is durable runtime state under `<root>/google/gmail-cursor.edn` — not
config. A push matching the stored cursor exactly is a no-op; one where
the *starting* cursor aged out of Gmail's history window triggers a full
resync from `messages.list` instead of a history walk, logged `:warn
:gmail/resync`.

This module contributes the Gmail half of isaac-google's Pub/Sub plumbing:
the `gmail.modify`/`gmail.send` OAuth scopes (added to the login scope
union the moment isaac-gmail is installed — see
`isaac.google#oauth-login-and-scopes`) and the `gmail-watch` registration
entry itself. The door, OIDC verification, and reconcile timer are
entirely isaac-google's.

**How to change it.**

```
config set comms.gmail.gmail/mode pull
config set comms.gmail.gmail/pull-interval-ms 120000
```

**How to verify.** `isaac google status` lists the `gmail-watch`
registration key (per mailbox) and expiry in `:push` mode; in `:pull`
mode, no watch key appears for that mailbox — expected, not a problem.

### Troubleshooting

- **A mailbox stops getting new turns.** In `:push` mode, check `isaac
  google status` for the `gmail-watch` key's expiry and any
  `:google/registration-failed` in the log (commonly a stale token — see
  `isaac.google#oauth-login-and-scopes`). In `:pull` mode, confirm the
  server process is actually running — the pull tick only fires inside
  `isaac server`.
- **`:gmail/resync` shows up in the log.** The stored cursor aged out of
  Gmail's history retention window (commonly an extended outage) — a full
  inbox re-walk, not an error; already-labelled messages are skipped.
- **Switching `gmail/mode` doesn't seem to take effect.** Confirm the
  process actually cycled this module's component — the pull tick is
  scheduled/cancelled at component (re)start, not mid-tick. `[verify]`

## Sending mail

**What it is.** isaac-gmail ships exactly one path to send mail:
`comm__send` (`isaac.agent`) with `"comm": "gmail"` plus this module's own
fields: `gmail.to` + `gmail.subject` (a new message; both required
together, no `gmail.thread`), or `gmail.thread` (a reply on that thread —
wins over `gmail.to` if both are given). With neither set, a reply falls
back to the turn's own origin thread. A `gmail.thread` reply always
fetches that thread's *current* last message fresh from the Gmail API for
its headers, rather than trusting anything cached from turn start —
`comm__send` crosses the delivery queue, so nothing about the originating
session necessarily survives to send time. There's no `gmail__send` tool;
sending is `comm__send`'s job exclusively, run as the comm's own Google
organization with that organization's token
(`isaac.google#google-organizations`).

The model's own end-of-turn text is *always* delivered back over the
channel the message came in on — `comm__send` is for *additional* mail
during the same turn, never a substitute for ending the turn with the
actual answer.

**Attachments.** `comm__send`'s `attachments` (local file paths, resolved
against the session's working directory and its tool-directory grants —
`isaac.agent`'s Tools and directories) become a `multipart/mixed` raw
message; total size is capped at 25 MB, refused before any Gmail API call.

**How to change it.** Nothing here is a config path — `comm__send`'s
fields come from this module's manifest. The one config surface is
granting the tool to a crew:

```
config set crew.cordelia.tools.allow '[:comm/send]'
```

**How to verify.** Have a crew call `comm__send` with `"comm": "gmail"` and
either `gmail.to`/`gmail.subject` or `gmail.thread`, then check the mailbox
(or, in a test, the recorded outbound HTTP request).

### Troubleshooting

- **`comm__send` refuses with a missing-field error.** `gmail.subject` is
  required whenever `gmail.to` is set with no `gmail.thread`.
- **A reply lands on the wrong thread, or fails "Gmail thread not
  found."** Confirm `gmail.thread` is a real, current thread id — it's
  always re-fetched fresh, never trusted stale.
- **An attachment is refused.** Either the total size crossed 25 MB, or
  the path resolved outside this crew's allowed directories.

## Inbound attachments

**What it is.** When an inbound message that starts a turn (`:converse`)
carries file attachments, each is downloaded and saved under
`attachments/<gmail-message-id>/<filename>` inside the session's working
directory before the turn starts, and the turn's input names each one with
its type and size. A single attachment over 25 MB is not saved (`(too
large, not saved)` in the input instead); a download failure is likewise
reported inline. The model cannot view images yet — it's told so
explicitly rather than silently failing.

**How to change it.** Nothing here is configurable — automatic for every
`:converse`-routed message with attachments. Reading one back is an
ordinary file-tool read against the session's working directory.

**How to verify.** Send (or simulate) a message with an attachment to a
`:converse` route and check `attachments/<id>/<filename>` under that
session's working directory, and that the turn's input names the file.

### Troubleshooting

- **An attachment never shows up on disk.** Confirm the route actually
  converses — attachments are only saved on that path, not `:ignore` or
  `:task`. `[verify]`
- **A file landed but is not a valid image.** Check the file bytes: a PNG begins with `89 50 4E 47`, not UTF-8 replacement bytes `EF BF BD`. That indicates attachment bytes were decoded as text and corrupted, not a missing download. Re-fetch the original attachment from Gmail; the corrupted file cannot be repaired.
- **The turn's input says "(download failed)."** Gmail's `attachments.get`
  call failed — check the log for `:gmail.attachment/download-failed` and
  retry once the underlying (usually transient) cause clears.

## Gmail tools for the crew

**What it is.** Three read-only tools, on Isaac's own token and scopes,
not a crew's own OAuth grant: `gmail__search` (Gmail's own query syntax —
`from:`, `subject:`, `newer_than:`, `is:unread`, `has:attachment`, …),
`gmail__read` (one message by id: headers plus decoded plain-text body),
and `gmail__labels` (the mailbox's labels). None send mail — that's
`comm__send`'s job (Sending mail, above); `gmail__send` is retired.

**How to change it.** Grant them like any other tool:

```
config set crew.cordelia.tools.allow '[:gmail__search :gmail__read :gmail__labels]'
```

**How to verify.** Call `gmail__search` with a query and confirm it
returns message ids; call `gmail__read` with one and confirm headers and
body come back.

### Troubleshooting

- **The tools aren't callable from a crew that should have them.** Confirm
  they're on that crew's (or the global default) `tools.allow` — installed
  doesn't mean callable; see `isaac.agent`'s allow/deny cascade.
- **A search returns no results for mail you know exists.** Gmail's query
  syntax is exact — check it against Gmail's documented search operators.

## Labels

**What it is.** Every gated message gets exactly one verdict label,
created on first use and cached per Google organization:
`<prefix>/<route>`, `<prefix>/ignored`, `<prefix>/unrouted`, or
`<prefix>/triage/<verdict>`. `gmail/label-prefix` (default `"isaac"`) is
the one knob; an `:ignore` verdict also removes Gmail's `UNREAD` label
unless `gmail/ignore-marks-read` is explicitly `false`.

**How to change it.**

```
config set comms.gmail.gmail/label-prefix isaac
config set comms.gmail.gmail/ignore-marks-read false
```

**How to verify.** `gmail__labels` lists every label currently in the
mailbox, including verdict labels once at least one message has routed.

### Troubleshooting

- **Verdict labels use an unexpected prefix.** Check
  `comms.gmail.gmail/label-prefix` on this specific comm entry — it's per
  comm, not global.
- **An ignored message stays unread when you expected it marked read.**
  `gmail/ignore-marks-read` defaults `true`; check whether it's explicitly
  `false` on this comm.
