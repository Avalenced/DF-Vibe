# Variables and data

## The four scopes (use these exact strings)
| `var()` scope | DF name | Lifetime / sharing |
|---|---|---|
| `'line'` | LINE | this one code-line execution only — a scratch register |
| `'local'` | LOCAL | this **thread/invocation** only; cleared when the func/process ends |
| `'unsaved'` | GAME | **whole plot, shared by everyone**, wiped when the plot stops/empties — runtime state |
| `'saved'` | SAVE | **persists forever** across restarts — the database (stats, currency, unlocks) |

These are the literal strings the codec emits and DF expects. `'global'`/`'save'` are
**wrong** — a wrong scope string passes `dfpy.py check` but silently breaks the variable
in-game. `var('coins','saved')` persists; `var('temp','local')` does not.

### CRITICAL: `'line'` does NOT survive `CallFunc` — use `'local'` to pass values
A function called with `CallFunc` runs on the **same thread but as a separate code line**.
So **LINE variables do not carry between caller and callee**, but **LOCAL variables do**
(local = whole thread). This silently breaks things and passes `check`:
```python
# caller (genTerrain):  SetVar('=', var('x','local'), num('5'))   # MUST be 'local'
#                        CallFunc('helper')                       # reads x, writes h
#                        ... uses var('h','local') ...            # MUST be 'local'
# callee (helper):       reads var('x','local'); writes var('h','local')
```
A real bug from this: a per-column terrain helper used `var('localx','line')`/`var('h','line')`
→ the callee always saw `localx = 0` and the caller always saw `h = 0`, so the whole map
generated flat. The fix was changing the **shared** vars to `'local'`. Rule: **any variable
read or written across a `CallFunc` boundary must be `'local'`** (or `'unsaved'`/`'saved'`).
Scratch vars used within one function only can stay `'line'`.

## Limits (real caps a big game can hit)
- **SAVE data: 10 MB** uncompressed per plot (~4–7 MB compressed). Check with
  `/plot data vars`. Don't dump huge blobs into save-vars.
- **GAME + SAVE share a cap of 500,000 loaded variables**; **LOCAL and LINE cap at 50,000
  per thread.** Per-player `%uuid` vars multiply fast — a save-var per player per stat adds up.
- **Lists and dictionaries: 10,000 entries max.** Nested lists/dicts count **flattened**
  (`[1,[2,3]]` = 3). A dict key counts as 1 and its value as however many sub-values it has,
  so ~5,000 scalar key→value pairs.
- **Numbers** are fixed-point, 3 decimals, range **±9,223,372,036,854,775.807**. Math on huge
  values can **overflow and wrap** — multiplication/division inputs must stay under ~9.2
  trillion. Rarely matters, but a runaway counter or a big multiply can silently wrap.

## Per-player state in shared loops: `%uuid` / `%default`
GAME (`unsaved`) vars are shared, so a loop running "for each player" can't use a bare
`var('timer','unsaved')` — everyone clobbers it. **Namespace the variable name** with a
target code:
```python
SetVar('+=', var('%uuid loop tick', 'unsaved'))
with IfVar('>=', var('%uuid loop tick', 'unsaved'), num('3')):
    SetVar('=', var('%uuid loop tick', 'unsaved'), num('0'))
    CallFunc('on every 3 ticks')
```
Each player gets their own `<uuid> loop tick`. Use **`%uuid`** for anything `saved`
(UUIDs never change); `%default` (name) is fine for transient `unsaved` counters. See
`placeholders.md`.

## Lists and dictionaries
Variables can hold **lists** (1-indexed) and **dictionaries** (key→value). Common ops
(grep `actiondump.json` for exact names/args): `CreateList`, `AppendValue`,
`GetListValue`, `RemoveListIndex`, `CreateDict`, `SetDictValue`, `GetDictValue`;
conditionals `IfVar('ListContains', …)`. Read inline in text with `%index(list,n)` and
`%entry(dict,key)` (see `placeholders.md`).

## Buckets — cross-plot storage with namespaces
`saved` vars persist but are **per-plot**. **Buckets** are named collections of variables that
live in a **namespace** and can be shared **across plots** (a plot network). The pieces:

- A **bucket variable** is a value item — like a variable, but it lives in a *bucket* inside a
  *namespace* rather than on the plot. In `.py`: **`bucketvar('varName', 'bucketKey')`** (add
  `namespace_type=`/`namespace_alias=` for a shared/named namespace; default is your own).
- A **namespace** is the access scope that decides which plots may read/write a bucket. Manage
  it in-game with **`/namespace`** (`/namespace help`). A bucket that's saved + unloaded can be
  loaded by any plot that has access to its namespace — that's how data crosses plots.
- **Set Variable bucket actions** drive them: `LoadBucket(result, bucket, [namespace])` loads a
  bucket (stays loaded until unloaded); `GetBucketVar`/`GetBucketVars` read from it;
  `SaveBucket` persists; `SaveUnloadBucket` persists **and frees it** so other plots can load
  it; `PurgeBucket` clears it; `LoadedBuckets` lists what's currently loaded.

Use buckets for data shared between separate plots (network economy, cross-plot profiles,
global leaderboards). For single-plot persistence, a `saved` var is simpler. (The exact
`/namespace` setup/grant flow isn't fully captured here — confirm in-game with `/namespace help`.)

## Container-as-database (advanced, very common)
DF has no real database, so builders **store structured data in chests/barrels placed in
the plot, using items as records and item tags as fields.** This is how teams, shops,
profiles, kits, and leaderboards persist. The moves:
- **Find a free slot**: `Repeat('Grid', var('loc','local'), loc(...), loc(...))` then
  `IfGame('BlockEquals', var('loc','local'), txt('air'))` → place a barrel,
  `Control('End')` to stop searching.
- **Place the store**: `GameAction(' SetBlock ', loc, item('…barrel…'))`,
  `GameAction('SetContainerName', loc, name)`, `LockContainer`.
- **Records are tagged items**: `SetVar('SetItemTag', out, base_item, key, value)` writes
  a field; `GameAction('SetItemInSlot', loc, item, num('slot'))` files it; read back with
  a get-item-in-slot + `IfVar('ItemHasTag', …)`.

Use this when persistence is richer than flat save-vars (per-team data, a shop's stock,
records keyed by something) instead of inventing dozens of save-vars.
