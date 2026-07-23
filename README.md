# DF Vibe

Pull a plot's code into local `.py` files, edit them (you, your AI, whoever), push them back.
All from in-game `/df` commands.
## Setup

1. Drop `dfvibe-1.0.0.jar` into `mods/` next to **Fabric API** (Minecraft 1.21.11). Don't run
   stock CodeClient alongside it, DF Vibe already includes it.
2. Have Python installed.
3. In-game: `/df path auto` sets everything up, then `/df pull <name>` on your plot.

`/df help` probably covers the rest. Build it yourself with `cd mod-standalone && ./gradlew build`.

## Credits

Built on [CodeClient](https://github.com/DFOnline/CodeClient) (the mod) and
[pyre / dfpyre](https://github.com/Amp63/pyre) (the codec), both MIT. Action data from the
DiamondFire / [DFOnline](https://dfonline.dev) community. Full list and licenses in CREDITS.md
