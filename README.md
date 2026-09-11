# kotoba-lang/org-babashka-nbb

**A fork of [babashka/nbb](https://github.com/babashka/nbb) 1.5.212 whose classpath
resolver also finds `.cljk` files.** It is the engine behind `kotoba-lang/kotoba`
`bin/kbb` for Clojure-shaped `.cljk` scripts (`kbb --backend sci <script.cljk>`),
and it is what `nbb` on PATH must be in a workspace that renamed its Clojure
source to `.cljk` (com-junkawasaki/root ADR-2609111500).

Origin plane: babashka.org → `org-babashka`. Upstream README is at
[`doc/UPSTREAM-README.md`](doc/UPSTREAM-README.md). Upstream base: tag
`v1.5.212` = `ea6683e` in babashka/nbb (rebased from `v1.4.208` = `4fff82e` on
2026-09-11 -- see "Why 1.5.212" below). Upstream git history is **not** in this
repository: GitHub refused the push because upstream commits touch
`.github/workflows/ci.yml` and the workspace token has no `workflow` scope —
and this workspace runs no GitHub Actions anyway (root ADR-2607300900), so
`.github/` is dropped. To take a newer nbb, re-apply the one patch on top of
the new tag and rebuild; the patch is 10 lines.

## The one patch

`src/nbb/core.cljs` `find-file-on-classpath`. Upstream probes
`foo.cljs` → `foo.cljc` → `foo.clj`. This fork probes, in order:

```
foo.cljk  foo.cljs.cljk  foo.cljc.cljk  foo.cljs  foo.cljc  foo.clj.cljk  foo.clj
```

`foo.cljk` is the plain rename; `foo.cljs.cljk` / `foo.cljc.cljk` / `foo.clj.cljk`
are the collision spellings the rename used when two of the old extensions shared
a basename. Nothing else in nbb changes: direct execution (`nbb x.cljk`) already
worked upstream because it opens the path it is given; only `require` of a
namespace from the classpath needed the probe.

**Why a fork and not a shim.** Stock nbb's probe list is a literal inside the
compiled bundle (`lib/nbb_core.js`); there is no hook to extend it. Measured
2026-09-11: every `require` of a workspace namespace (`cheshire.core`,
`babashka.process`, `scripts.nbb-compat`, `kotoba.lang.text` …) fails with
`Could not find namespace` on stock nbb once the compat shims are `.cljk`; on this
build the same 9 PreToolUse hooks exit 0 with byte-identical hook sources.

## Why 1.5.212 and not 1.4.208

The first build of this fork (`33575ae`, 2026-09-11 morning) sat on `v1.4.208`.
`kotoba-lang/amu` had been running on stock nbb `1.5.212`, and moving it onto the
1.4.208 fork to recover from the `.cljk` rename made `amu check` **~1.45x slower**
with the compiler source held fixed -- the engine was the only variable:

```
same amu tree (pre-rename source), K=384 buildbench workload, ABAB x3, one host
  fork on 1.4.208    5695 / 5637 / 5457 ms
  stock 1.5.212      3507 / 3728 / 3572 ms
same engine patch rebased onto 1.5.212, amu post-rename tree, ABAB x3
  fork on 1.5.212    3691 / 3893 / 3858 ms
  fork on 1.4.208    5555 / 5325 / 5805 ms
```

The 1.4 -> 1.5 difference is upstream's newer SCI; the `.cljk` probe adds four
`existsSync` calls per unresolved namespace and is not measurable against it.
The numbers above are from a host at `load1` 22-37 and are a ratio, not a
portable absolute. Re-measure with amu's `bin/amu check` on
`kotoba-lang/buildbench`'s generated workload.

Known on this base: upstream's `repl-test` (6 assertions in
`script/nbb_repl_tests.clj`) errors on Node 26 with
`ERR_USE_AFTER_CLOSE: readline was closed` when stdin is a closed pipe.
Stock `nbb@1.5.212` from npm fails the same way on the same Node, so it is not
this patch; the other 31 integration tests pass.

## What is committed

- `src/` — upstream source, one function patched. Files keep upstream's `.cljs`
  spelling because shadow-cljs (the build) reads only `.cljs`/`.cljc`; this is
  third-party source, not Kotoba source, and the rename ADR's mechanism has no
  exclusion list — do not run it over this repository.
- `lib/*.js` — the **built** engine (2.1 MB), committed so a machine without a
  JDK (fleet nodes, launchd) can run it. Source maps are not committed.
- `cli.js` — `node cli.js <script>` is the whole entry point.
- `node_modules/import-meta-resolve/` — nbb's single runtime dependency (MIT,
  4.2.0, no dependencies of its own), committed for the same reason as `lib/`:
  a fresh `west update` must yield a runnable engine without `npm install`.
  Measured 2026-09-11: without it a clean clone fails at
  `Cannot find package 'import-meta-resolve'` before evaluating anything.

## Rebuild

Build-time only, JVM via shadow-cljs (`:structural` in
`manifest/dependency-substitution.edn`); the artifact runs on Node alone.

```bash
npm install --ignore-scripts
clojure -M -m shadow.cljs.devtools.cli --force-spawn release modules
node cli.js -e '(require (quote [cheshire.core :as j])) (j/generate-string {:cljk true})'  # from a dir whose nbb.edn :paths hold a .cljk cheshire shim
```

Measured 2026-09-11 on a 10-core Mac under load: 24.5 s, 223 files, 0 warnings.
Run the rebuild through `scripts/resource-guard.mjs run build -- …` in the
workspace.

## Discriminating check

Both directions, same input, same tree:

```bash
node cli.js some-script-requiring-a-cljk-namespace.cljk      # exit 0
npx nbb@1.5.212 the-same-script.cljk                          # Could not find namespace
```

## Not in scope

This fork does not make nbb a Kotoba runtime. Scripts that are amu-admissible
Kotoba (`.kotoba`, admitted `.cljk`) go through `kbb`'s native / js backends;
this engine is the SCI-on-Node host for the Clojure-shaped operational surface
that predates them (hooks, gates, bots), until each is migrated as a whole
component.
