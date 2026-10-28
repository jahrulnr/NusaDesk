# PRoot path reverse-translation patch

The base remains termux/proot v5.1.107.96, commit
`a179d3e8a4e045aaa1fb8cc3284f23509d96d353` (GPL-2.0-or-later).
`scripts/build-proot-arm64.sh` applies this patch alongside the existing
recorded patches, builds with the pinned NDK r28c and talloc sources, and
checks exact output hashes before copying either artifact into jniLibs.

`unbound-symlink-referrer.patch` fixes the `binding_referrer != NULL` assertion
reported when Devin was opened inside the shared guest tracer. A host symlink
referrer without a matching binding cannot establish a same-binding rewrite.
Leave `follow_binding` false in that case and continue the existing rootfs or
unchanged-target handling. Known bindings and strict non-symlink checks retain
their existing behavior. This does not globally disable assertions.

The build runs `scripts/test-proot-detranslate.py <proot-source>` first. This
compiles the actual upstream path functions with deterministic external
binding/procfs fixtures and assertions enabled. The original code aborts on
the first unbound-referrer case; the patch passes 11 cases. Host `cc` and
Python 3 are required in addition to the documented native build tools.

ADR-0082 records evidence and limits. The new packaged PRoot SHA-256 is
`3f0310591d1380b972f574f10ba26607aa7378bfddb2c31f66d4555abe15b1ed`.
The loader hash remains
`12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0`.
