#!chezscheme
;;; (igropyr quickjs) end to end: boot a tiny JS bundle, call globals,
;;; the JS-error boundary, and the crash-only rebuild (a throwing call
;;; discards the heap, bumps the generation, and recovers -- the next
;;; call still works). SHIM-GATED: the native dylib is a build artifact
;;; (build-quickjs-shim.sh, needs QuickJS installed), so when it is
;;; absent this test SKIPS cleanly rather than failing.

(import (chezscheme) (igropyr quickjs))

(define (str-has? s sub)                       ; is sub a substring of s?
  (let ((n (string-length s)) (m (string-length sub)))
    (let loop ((i 0))
      (cond ((> (+ i m) n) #f)
            ((string=? sub (substring s i (+ i m))) #t)
            (else (loop (+ i 1)))))))

;; the resolution paths (igropyr quickjs)'s load-so! searches, plus the
;; env override; if none resolves, the shim was never built -> skip
(define (shim-present?)
  (or (let ((e (getenv "IGROPYR_QUICKJS_SO"))) (and e (> (string-length e) 0)))
      (file-exists? "igropyr/libigropyr-quickjs.dylib")
      (file-exists? ".build-links/igropyr/libigropyr-quickjs.dylib")
      (file-exists? "libigropyr-quickjs.dylib")
      (file-exists? "igropyr/libigropyr-quickjs.so")
      (file-exists? ".build-links/igropyr/libigropyr-quickjs.so")
      (file-exists? "libigropyr-quickjs.so")))

(unless (shim-present?)
  (display "quickjs: shim not built (run build-quickjs-shim.sh), test skipped\n")
  (exit 0))

(define failures 0)
(define (check label ok)
  (unless ok
    (set! failures (+ failures 1))
    (display "FAIL ") (display label) (newline)))

;; user input is DATA (the arg), never code -- the bundle is fixed here
(define bundle "
function greet(x){ return 'hi ' + x; }
function echo(x){ return x; }
function boom(x){ throw new Error('nope: ' + x); }
function sym(x){ return Symbol('s'); }         // not string-coercible
function thrower(x){ return { toString: function(){ throw new Error('ts boom'); } }; }
Object.defineProperty(globalThis, 'badgetter', { get: function(){ throw new Error('getter boom'); } });
")

(qjs-boot! bundle)
(check "healthy-after-boot" (qjs-healthy?))
(check "generation-1" (= 1 (qjs-generation)))

;; a plain call: global function, one string arg, string back
(let-values (((ok s) (qjs-call "greet" "world")))
  (check "call-ok" ok)
  (check "call-result" (string=? s "hi world")))

;; qjs-call/bytes: same result as raw UTF-8 bytes (no decode)
(let-values (((ok b) (qjs-call/bytes "greet" "world")))
  (check "bytes-ok" ok)
  (check "bytes-val" (and (bytevector? b) (string=? (utf8->string b) "hi world"))))

;; utf-8 round-trips across the C boundary (multibyte + astral)
(check "unicode" (string=? (qjs-call! "echo" "café—漢字🙂") "café—漢字🙂"))

;; a missing global is an error, not a crash
(let-values (((ok s) (qjs-call "nope" "x")))
  (check "missing-fn-flag" (not ok))
  (check "missing-fn-text" (str-has? s "no such function")))

;; the JS-exception boundary: qjs-call returns #f + the error text, and
;; crash-only rebuild fires -- generation bumps, engine stays healthy
(let-values (((ok s) (qjs-call "boom" "z")))
  (check "throw-flag" (not ok))
  (check "throw-text" (str-has? s "nope: z")))
(check "generation-bumped" (= 2 (qjs-generation)))
(check "healthy-after-throw" (qjs-healthy?))

;; ...and the engine still works after the rebuild
(check "recovers-after-throw" (string=? (qjs-call! "greet" "again") "hi again"))

;; the raising variant surfaces a JS throw as a Scheme error
(check "call!-raises"
  (guard (e (#t #t)) (qjs-call! "boom" "q") #f))

;; non-string-coercible result (Symbol / throwing toString): -> (#f msg), the
;; pending exception is drained so the NEXT call is not poisoned
(let-values (((ok s) (qjs-call "sym" "")))
  (check "sym-not-ok" (not ok)) (check "sym-msg" (and (string? s) (> (string-length s) 0))))
(check "sym-next-clean" (string=? (qjs-call! "greet" "A") "hi A"))
(let-values (((ok s) (qjs-call "thrower" ""))) (check "thrower-not-ok" (not ok)))
(check "thrower-next-clean" (string=? (qjs-call! "greet" "B") "hi B"))

;; a throwing property getter is reported accurately, not "no such function"
(let-values (((ok s) (qjs-call "badgetter" "")))
  (check "getter-not-ok" (not ok))
  (check "getter-msg-accurate" (and (string? s) (not (string=? s "no such function")))))
(check "getter-next-clean" (string=? (qjs-call! "greet" "C") "hi C"))

;; bad options rejected before any side effect; a negative cap can't bypass the
;; memory limit, and the live engine is untouched
(check "reject-negative-mem"
  (guard (e (#t #t)) (qjs-boot! "function f(x){return x;}" '((mem-mb . -1))) #f))
(check "reject-bad-timeout"
  (guard (e (#t #t)) (qjs-boot! "function f(x){return x;}" '((timeout-ms . 0))) #f))
(check "engine-survives-bad-boot" (string=? (qjs-call! "greet" "D") "hi D"))

(qjs-shutdown!)
;; a call after shutdown reports cleanly ("qjs-boot! first"), not a silent
;; empty-engine re-boot from a freed bundle
(check "post-shutdown-clean-error"
  (guard (e ((and (message-condition? e) (str-has? (condition-message e) "qjs-boot")) #t)
            (#t #f))
    (qjs-call "greet" "z") #f))

(if (zero? failures)
    (begin (display "quickjs: all tests passed") (newline) (exit 0))
    (begin (display failures) (display " failures") (newline) (exit 1)))
