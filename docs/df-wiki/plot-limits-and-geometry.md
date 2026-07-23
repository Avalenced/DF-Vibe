# Plot sizes, geometry, and the code-line length limit

DiamondFire plots come in sizes. Authoritative dimensions (from the placement engine;
the in-game reference book has the richest detail — consult it when available):

| Size | Play area | `codeLength` (max code-line length) | `codeWidth` |
|---|---|---|---|
| BASIC | 50 | 50 | 20 |
| **LARGE** | **100** | **100** | **20** |
| MASSIVE | 300 | 300 | 20 |
| MEGA | 1000 | 300 | 300 |

At runtime, the **`Plot size in blocks`** game value gives the current size.

## The code-line length limit (critical)
Each `.py` file is one **code line** — a single horizontal row of code blocks. A line
can be at most **`codeLength` long**, or it runs off the plot edge (which the
DF VIBE placer does **not** handle — it will fail or misplace). On a **LARGE** plot that
cap is **100**.

**Length formula (verified):** `length = (code blocks × 2) + (brackets × 1)`, where a
"bracket" is each `{`/`}` from an If/Repeat/Else (so one `with IfPlayer(...)` adds its
header block plus an open and a close bracket).

**Practical rule:** keep each file to roughly **≤ 35 statement blocks with light
nesting** (length ≲ 85) on a LARGE plot — comfortably under 100. The codec prints
`(N blocks)` on `check`; if a line is getting big, **split it into more functions**
(also better design — see `patterns.md`). When in doubt, leave margin.

## Coordinates
`[0,0,0]` is the plot's **north-west bottom corner**; locations are `loc(x,y,z)`. You
choose where your arena/build sits inside the play area — keep it within the size above so
generated blocks don't fall outside the plot. If you don't know the exact play-area
origin, expose arena corners as easily-editable `loc(...)` constants the human can adjust
in-game, rather than hard-guessing.
