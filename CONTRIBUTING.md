# Contributing to RaspberryJuice

Thanks for helping revive RaspberryJuice! This is a modernized fork of the (EOL)
[zhuowei/RaspberryJuice](https://github.com/zhuowei/RaspberryJuice), targeting current
Paper / Java. Contributions of all sizes are welcome.

## Prerequisites

- **JDK 25** — Paper 26.2 is compiled to Java 25 bytecode; the plugin will not build or
  load on an older JDK.
- Git. **No system Maven** is needed — the repo ships a Maven Wrapper (`./mvnw`).

## Build & test

```bash
git clone https://github.com/sakebomb/RaspberryJuice
cd RaspberryJuice
./mvnw clean package        # build the plugin jar -> target/raspberryjuice-*.jar
./mvnw test                 # run the unit + characterization tests
```

There's also an end-to-end check, `scripts/smoke_test.py`, that speaks the raw mcpi socket
protocol against a live server (drop the jar into a Paper server's `plugins/`, start it,
then `python3 scripts/smoke_test.py [host] [port]`). CI runs this automatically against a
real Paper 26.2 server.

## Making a change

1. **Branch** off `master` with a typed name: `feat/…`, `fix/…`, `refactor/…`,
   `chore/…`, `test/…`, `ci/…`, or `docs/…`.
2. **Write a test** for behaviour changes. Bug fixes should include a regression test;
   protocol/command changes should be pinned by a characterization test.
3. Keep changes focused — small PRs are much easier to review.
4. **Open a PR** into `master`. CI (build + unit tests on JDK 25, plus a live Paper 26.2
   smoke test) must be green before merge.

## Style

- Functions small and single-purpose (aim < 50 lines); prefer early returns over deep
  nesting.
- Match the surrounding code's conventions (this codebase uses tabs).
- No new dependencies without a clear justification.
- Don't commit build artifacts or IDE files (see `.gitignore`).

## Protocol compatibility

The mcpi wire protocol uses **legacy numeric block/entity ids**, which existing Python
`mcpi` scripts depend on. These are bridged to the modern `BlockData` API via
`LegacyBlocks` / `LegacyEntities`. **Preserve the wire protocol** — changing the numeric
ids or command formats breaks every existing client. See
[`docs/modernization-notes.md`](docs/modernization-notes.md) for background.

## Licence

By contributing you agree that your contributions are licensed under the project's
[Apache License 2.0](LICENSE).
