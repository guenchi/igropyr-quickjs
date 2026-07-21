# chez-quickjs

`(igropyr quickjs)` — embed a JavaScript engine
([QuickJS](https://github.com/quickjs-ng/quickjs)) in a
[Chez Scheme](https://cisco.github.io/ChezScheme/) process behind a small,
hardened C shim. Load a **fixed** JS bundle at boot, then call its global
functions with one UTF-8 string argument and get a string back. User input is
the **argument**, never code — the bundle is baked at build time, so the attack
surface is "a C library parsing a user string", the same class as zlib or a
database driver.

This is the **C-shim** binding: QuickJS is statically linked into one
self-contained dylib, version-pinned, so the only native runtime dependency is
that dylib. [Igropyr](https://github.com/guenchi/Igropyr) also ships a
pure-Scheme binding with the **same exports** (`(igropyr quickjs)`, binding a
stock `libquickjs` directly over the FFI); this repo is a drop-in replacement
for it — reach for the shim when a stock `libquickjs` is awkward to obtain (for
example Homebrew ships only a static archive) or when you want a self-contained,
version-pinned artifact.

## Dependency

`(igropyr quickjs)` imports
[`(igropyr platform)`](https://github.com/guenchi/igropyr-platform) (host
detection + shared-object loading). Put it on your library path so the import
resolves.

## Build the shim

Requires the QuickJS library and headers:

```sh
# macOS
brew install quickjs
# Debian/Ubuntu: quickjs from the distro, or built from source
#   (expects quickjs/quickjs.h + libquickjs)

./build-quickjs-shim.sh        # -> libigropyr-quickjs.dylib  (or .so on Linux)
```

QuickJS is statically linked, so the resulting dylib is the only native runtime
dependency. Without it the library still imports fine; `qjs-boot!` reports the
missing shim.

## Use

```scheme
(import (igropyr quickjs))

(qjs-boot! "function slugify(s){ return s.toLowerCase().replace(/\\s+/g,'-') }")
(qjs-call! "slugify" "Hello World")     ; => "hello-world"
```

- `(qjs-boot! source [opts])` — load (or reload) the bundle. `opts` is an
  alist: `(mem-mb . 64)`, `(stack-kb . 1024)`, `(timeout-ms . 2000)`,
  `(so-path . "...")`. Options are validated; negative caps are rejected.
- `(qjs-call fname arg)` → `(values ok? string)` — call a global function with
  one string argument; the result on `#t`, the JS error text on `#f`. Never
  raises on a JS error.
- `(qjs-call! fname arg)` → string — the raising variant.
- `(qjs-healthy?)` / `(qjs-generation)` / `(qjs-shutdown!)`.

Resolution order for the dylib: explicit `(so-path ...)` > the
`IGROPYR_QUICKJS_SO` env var > `igropyr/libigropyr-quickjs.{dylib,so}` > the
plain name on the loader path.

## What the shim gives you

- **Memory limit** — `JS_SetMemoryLimit`: allocation past the cap becomes an
  in-JS OOM exception, never touches system memory.
- **Stack limit** — `JS_SetMaxStackSize` + `JS_UpdateStackTop` per call: deep
  recursion is a `RangeError`, not a C stack smash. The stack base is
  re-anchored each call, so calls may come from any host thread.
- **Wall-clock deadline** — `JS_SetInterruptHandler` + a monotonic deadline: a
  call over `timeout-ms` is aborted by the engine.
- **Exception boundary** — every call is checked; a JS error comes back as a
  string, nothing propagates past the ABI. A throwing property getter and a
  non-string-coercible result are reported (with the pending exception drained),
  not swallowed or leaked into the next call.
- **Crash-only rebuild** — any failed call discards the whole JS heap and
  reboots the runtime from the saved bundle; `qjs-generation` counts rebuilds,
  and a call after `qjs-shutdown!` reports cleanly.
- **Serialized engine** — a pthread mutex guards the single runtime, so the
  shim is safe to call from any OS thread.

## Test

Build the shim, then run `test/quickjs.sc` with `(igropyr quickjs)` and
`(igropyr platform)` resolvable on your library path (and `CHEZSCHEMELIBEXTS`
set so `.sc` files load). The test is shim-gated: it skips cleanly when the
dylib is not built, so it is safe to run on a host without QuickJS.

## Files

- `quickjs.sc` — the `(igropyr quickjs)` library (binds the C shim).
- `c/quickjs-shim.c` — the hardened C shim (a four-function C ABI).
- `build-quickjs-shim.sh` — builds the shim dylib.
- `test/quickjs.sc` — end-to-end test.

## License

MIT — see [LICENSE](LICENSE).
