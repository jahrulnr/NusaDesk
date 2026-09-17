# ADR-0032: Bounded request params and calendar read/write

## Status
Accepted — implementation complete; read and write verified on Samsung S10e
API 31, including two provider-shape defects found only on the device and now
regression-guarded. Wider OEM/API coverage remains open.

## Date
2026-09-17

## Context

The capability bridge envelope was deliberately param-free (ADR-0030): every
method was self-describing, and the host owned all input, so the guest could not
influence a query. Calendar access existed only as a declared permission and was
documented as an unimplemented limit.

The user asked for calendar access from the guest and chose to carry a bounded
`params` object in the request envelope rather than introducing a second data
channel (a file or command surface) for arguments. Calendar writes are the
reason a parameter surface is needed at all: creating or updating an event
requires caller data, and inventing the event content on the host would be
dishonest.

The forces that shaped the decision:

- the JSONL channel stays a bounded control channel (16 KiB frames), never a
  media or bulk-data transport;
- fail closed: input the host does not understand must be rejected, never
  silently ignored;
- exactly one guest session owns the bridge;
- attendee and invitation data is other people's personal data, and sending
  mail on the user's behalf is out of scope;
- calendar provider shape is OEM/API-sensitive, so the contract must be
  verified on a real device, not only against a JVM fake.

## Decision

1. **Bounded `params`.** `params` is an optional flat object in the request
   envelope, accepted only for methods that declare it. It carries at most 8
   keys, each key at most 32 characters of `[A-Za-z0-9_-]`, and string values at
   most 256 characters without control characters; nested objects, arrays, and
   other scalar types are rejected. A method that does not declare parameters
   answers the typed `unsupported-parameter`, and any top-level field other than
   `v`/`id`/`token`/`method`/`params` fails closed at decode time.

2. **Only calendar writes declare parameters.** `calendar.insert`,
   `calendar.update`, and `calendar.delete` are the declaring methods.
   `calendar.list` declares none: the window and row cap are host-owned.

3. **Bounded read.** `calendar.list` reads the platform's instance table for a
   fixed window (now to seven days later) with a minimal projection — event id,
   title, begin/end in UTC milliseconds, all-day flag, calendar id and display
   name, event timezone, and an optional location. Description, attendees,
   organizer address, reminders, and every other provider column are never
   read. Rows are capped at the shared 50-row policy and the cap is reported
   through `truncated`, so a bounded answer cannot look complete.

4. **The window travels as a URI path.** The instance query appends the begin
   and end ids to the `Instances` URI (the documented shape), relies on the
   provider to expand recurrences, sorts by begin, and enforces the row cap in
   Java because a SQL `LIMIT` token is not portable across providers
   (ADR-0019's lesson).

5. **Validated writes.** `CalendarWriteRequest` validates every field before a
   provider call: title and location at most 200 characters without control
   characters, `begin < end`, duration at most 24 hours, start no older than
   24 hours and no further ahead than 366 days, and an all-day event whose range
   must sit on whole UTC days — an update that sets the all-day flag must carry
   the range it is checked against. The target calendar must be writable
   (access level contributor or better) and an update/delete target must exist.

6. **Every write carries a timezone.** An all-day event writes UTC; a timed
   event writes the calendar's zone when it can be read and the device zone
   otherwise. A write never depends on an optional read succeeding, because the
   provider rejects an event without a timezone.

7. **Typed results only.** Permission, unavailability, invalid-argument,
   read-only, not-found, and provider-failure outcomes are typed errors; no
   provider exception or message crosses the wire. Each write logs one bounded
   line with the operation and the event/calendar ids — never the title,
   location, or any other content.

8. **The CLI keeps its allowlist property.** `nusadesk-android calendar ...`
   builds the bounded `params` object from typed flags (`--title`, `--begin-ms`,
   `--end-ms`, `--all-day`, `--timed`, `--location`) and never forwards a raw
   JSON argument, so an unknown key cannot be expressed from the guest.

## Alternatives considered

### A second channel for arguments

Rejected by the user's decision: a second channel adds session state, binds, and
failure modes (partial writes, staleness) for data that fits a bounded object.

### Raw JSON `params` on the CLI

Rejected: `nusadesk-android calendar add '<json>'` would let the guest express
arbitrary keys, moving the fail-closed guarantee from the client into the host.
Typed flags keep the "no user-supplied method/parameter name" property.

### A generic `call <method>` passthrough

Rejected: it would turn the CLI into an arbitrary-method surface and defeat the
fixed allowlist.

### Reading `Calendars`/`Events` instead of `Instances`

Rejected: recurring events are expanded by the platform in the instance table.
Reading raw rows would mean reimplementing recurrence expansion and its
exceptions in NusaDesk.

### Attendee writes and invitations

Rejected: attendee addresses are other people's personal data, and sending
invitations would make a local automation send mail. No attendee column is
written and no invitation is sent.

### A guest-chosen window or row limit

Rejected: "read my whole calendar history" is an unbounded cost with no product
need; the host owns the seven-day default.

## Consequences

### Positive

- Calendar access is real: the guest reads what the user sees in the Android
  Calendar app and can create, change, and remove its own events.
- The parameter surface stays bounded, allowlisted per method, and fail-closed,
  so adding a method still requires declaring its keys.
- The read answers with UTC milliseconds and an explicit timezone, so a guest
  script needs no Android knowledge and no calendar math for recurrences.
- The audit line makes a write observable without logging event content.

### Negative and limitations

- The envelope is no longer strictly param-free; every future declaring method
  must now be reviewed for its key set, which is a new review surface.
- No attendee, invitation, reminder, or conference-data support.
- No calendar creation, deletion, sharing, or colour management.
- No per-request window, no pagination, and no "search by text" read.
- Writes go to the primary writable calendar unless `calendar_id` is given; the
  guest cannot list calendars to choose from.
- Verified on one OEM/API (Samsung S10e, API 31). Another OEM may need a
  different instance-query or timezone shape; the device matrix in
  `docs/test-plan.md` remains the gate for that claim.
- An all-day event is UTC-day based, so a consumer wanting local calendar-day
  semantics must interpret it.

## Verification

JVM (all green in the full gate): `AndroidCapabilityProtocolTest` (bounded
`params` decode, malformed and unbounded shapes, unknown top-level field),
`CalendarQueryTest`, `CalendarWriteRequestTest`, `CalendarEventSnapshotTest`,
`AndroidCalendarSourceTest` (window in the URI path, minimal projection, row
cap, typed states), `AndroidCalendarWriterTest` (grant first, writable-calendar
guard, timezone fallback, typed failures, audit line without content),
`AndroidCapabilityRequestHandlerTest` (dispatch, `params` on a non-declaring
method, typed error mapping), `GuestAwarenessCliTest` (the generated Python CLI
round-trips against a fake loopback listener, including the calendar commands
and their exact `params`), and `GuestAwarenessGeneratedDocsTest`.

Physical pass (Samsung S10e `R39M209Q3TM`, Android 12/API 31, 2026-09-17) with
both calendar grants applied and the generated CLI inside the live guest:

- `calendar list` → `count 0` (a valid empty read);
- `calendar add --title … --begin-ms … --end-ms …` → `written true`,
  `event_id 138`; the next `calendar list` returned that event with
  `calendar_name "My calendar"`, `timezone "Asia/Jakarta"`, and no
  description/attendee field;
- `calendar update 138 --title …` → the read reflected the new title;
- `calendar delete 138` → the read returned `count 0`;
- an all-day insert → `event_id 139`, `all_day true`, `timezone "UTC"` on
  day-aligned bounds; deleted afterwards;
- a 25-hour request → `calendar-invalid-argument` (validation before the
  provider);
- with `WRITE_CALENDAR` revoked, the write answered
  `calendar-permission-required` while `calendar list` still worked, showing the
  read and write grants are independent;
- logcat contained only `calendar write op=… event=… calendar=…` lines; the
  probe titles appear nowhere in the log.

Two defects were found only on the device and are now fixed and guarded:

1. The `Instances` window must be part of the URI path. Passing it as a
   selection argument made the provider throw, and the read degraded to
   `capability-unavailable` — invisible to the JVM fake provider, which ignores
   the URI shape.
2. The provider rejects an insert whose values lack `eventTimezone`
   (`IllegalArgumentException: Event values must include an eventTimezone`).
   The writer now always sends one instead of depending on the calendar-zone
   read.

A third defect was caught by the existing protocol test rather than a device:
accepting the optional `params` field had widened the decoder to tolerate any
fifth top-level field, so an unknown field was silently dropped. The decoder
now rejects any field other than `params`.
