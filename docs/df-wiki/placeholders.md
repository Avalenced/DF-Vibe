# Placeholders / text codes

`%codes` are tokens resolved at runtime. Put them in `txt(...)` (display) or `num(...)`
(numbers). They are **not** in `actiondump.json`; this list is from DiamondFire's own
code-value picker (captured) and live-tested. Placeholders **nest**, resolving inside-out.

## Variable & target codes (work in text and numbers)
- **`%var(name)`** — a variable's value. Names may contain spaces (`%var(atk spd)`); also
  works inside Call Function arguments. Nests: `%var(%uuid coins)` builds the name
  `<uuid> coins` first, then reads it.
- **Target codes** — who/what an event or selection refers to; each has a `…uuid` twin
  (`%defaultuuid`, etc.) giving the UUID instead of the name:
  - `%default` — whatever (player/entity) triggered the event.
  - `%selected` — current Select Object targets. `%uuid` — the selected object's UUID.
  - `%victim` (took damage) / `%damager` (dealt it) — damage events.
  - `%killer` (who killed) / `%shooter` (who shot) / `%projectile` (the projectile).
  - `%player` is **deprecated** — always write `%default` instead.
  - For **persistent** per-player keys prefer `%uuid`/`%defaultuuid` (stable) over names.

## Function codes
- **`%math(expr)`** — arithmetic. **Two rules, both live-confirmed:**
  1. **Only evaluates inside a `num()` value, not `txt()`.** In `txt()` it stays
     unresolved. All real code uses `num('%math(…)')`. Always put math in a number.
  2. **Left-to-right, NO operator precedence** (DF's own example `%math(1+2*2)` = `6`).
     `a+b*c` = `(a+b)*c`. Write expressions in evaluation order; don't assume PEMDAS.
     No spaces inside (`num('%math(%var(x)-1)')`). Nests `%var`/`%random`.
- **`%round(number)`** — **truncates toward zero (drops the decimals)**, live-confirmed:
  `%round(1.9)` = 1, `%round(-1.5)` = **-1**. (DF's in-game help calls it "floor," but
  that's wrong for negatives — it's truncation. Trust this.)
- **`%random(min,max)`** — random **integer** between the bounds (which may be reversed,
  `%random(1,-1)`, to pick a sign). Works in text and numbers.
- **`%index(list,index)`** — value at a position in a **list** variable (1-based). Also
  indexes a **location** (`1`–`5` = x, y, z, pitch, yaw) and a **vector** (`1`–`3` = x, y, z).
- **`%entry(dict,key)`** — value for a key in a **dictionary** variable.

## Where each resolves
| code | in `txt()` | in `num()` |
|---|---|---|
| `%var`, target codes, `%random`, `%round`, `%index`, `%entry` | ✅ | ✅ |
| `%math` | ❌ (stays literal) | ✅ |

(DiamondFire's picker has more pages; the above is the core, verified set.)
