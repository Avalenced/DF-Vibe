# Credits

DF Vibe is glue. Almost everything underneath it is someone else's work.

## The mod
Built on **[CodeClient](https://github.com/DFOnline/CodeClient)** by GeorgeRNG and contributors.
The scan/place plumbing, the local API, and the dev utilities all come from it. MIT.

## The codec
The template ⇄ `.py` codec (`dfpy.py`) is built on **[pyre / dfpyre](https://github.com/Amp63/pyre)**
by Amp63 — the library that figured out how to read and write DiamondFire code templates from
Python. Without it there is no codec. MIT.

## The data
`actiondump.json` (every action, tag, value, particle, sound, and their rules) is DiamondFire's
own action data, kept accessible by the **[DFOnline](https://dfonline.dev)** community.

## The server
**[DiamondFire](https://mcdiamondfire.com)** — the whole reason any of this exists.

Missing something? These are the big ones, but if a tool or library helped and isn't here,
open an issue and it gets added.

---

## Licenses

Both the mod's base and the codec's base are MIT. Their notices, in full:

```
Copyright (c) 2022 GeorgeRNG          (CodeClient)
Copyright (c) 2024 Amp                (pyre / dfpyre)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

DF Vibe's own additions are MIT too (© 2026 Avalenced).
