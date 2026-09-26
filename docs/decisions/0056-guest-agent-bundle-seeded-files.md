# ADR-0056: The guest agent bundle is seeded, not managed

- Status: accepted
- Date: 2026-09-27

## Context

AI agents working inside the guest need durable, discoverable instructions
about the environment — most importantly which of the two Android doors a
task belongs on: the capability-bridge commands (`termux-*`, `nusadesk-*`,
ADR-0036/0049) or the native tool dispatcher (`android-cli`, ADR-0045). The
conventional homes for that are `/root/.agents/AGENTS.md` and
`/root/.agents/skills/`.

The existing guest writers cannot provide this as-is. Every file they ship
is *managed*: rewritten whenever its bytes differ from the current generated
text, which is right for product docs and CLIs but wrong for a file the user
(or their agent) is expected to own and edit. A managed AGENTS.md would
silently discard edits on the next session start, and a deleted one would
reappear.

## Decision

1. `/root/.agents/AGENTS.md` and
   `/root/.agents/skills/termux-api-vs-android-cli/SKILL.md` are *seeded* by
   `GuestAgentSeedWriter`, registered in the same per-session ensure pass as
   the managed bundle (`GuestAwarenessReadmeWriter.ensure`). The tree stays
   ordinary writable guest files; nothing is bound read-only.
2. Seed semantics are digest-based, recorded in
   `/var/lib/nusadesk/agent-seed.state` — deliberately outside `.agents`, so
   deleting the whole tree keeps the record and does not masquerade as a
   fresh install:
   - a path with no record and no file is written;
   - a file matching its recorded digest is the unmodified install — a new
     shipped text replaces it, which is how upgrades reach users who never
     edited;
   - a present file whose digest differs from the record was edited and is
     never overwritten;
   - a recorded path whose file is absent was deleted and is never
     recreated, so recurring session starts do not undo removals;
   - a present path with no record is pinned `-` (foreign), so a file that
     predates seeding is never adopted, and creating-then-deleting an own
     file does not later flip the path into a seed target.
3. Occupancy is respected, not fought: a symlink or non-directory on a fixed
   parent — or a seed path that is not a plain regular file — marks the path
   foreign rather than being followed or replaced, and an unsafe state-file
   path skips the whole bundle for that run. A corrupt state file is rebuilt
   conservatively: present bundled paths are recorded foreign (nothing is
   ever overwritten from an unreadable baseline) and absent ones are seeded
   again.
4. The seeded texts carry no app-version stamp on purpose: the recorded
   digest is the change signal, so an app rebuild with unchanged text writes
   nothing. The state file's comment header records the writing version for
   diagnostics only.
5. The generated guest docs name the bundle and its user-owned contract, and
   the seeded `AGENTS.md` itself documents the detach/reseed paths: deleting
   the state file pins every present path user-owned; deleting seeded files
   together with it reseeds cleanly.

## Consequences

- The user can edit or delete anything under `/root/.agents/` without
  fighting the app; upgrades touch only files still byte-identical to what
  was installed.
- The seed state is app bookkeeping under `/var/lib`, inside the guest
  backup scope, so a backup/restore keeps files and records consistent and a
  fresh rootfs reseeds.
- Removing a bundled file in a later version leaves the seeded copy in
  place — the stale-file sweep used for managed `termux-*` scripts is
  deliberately not applied to user-owned files.
- The `AGENTS.md`/`SKILL.md` content is generated Java text like the other
  guest files (no mutable rootfs asset), and `GuestAgentSeedWriterTest` pins
  the seed/upgrade/edit/delete/occupancy matrix on the JVM.
