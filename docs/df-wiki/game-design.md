# Game design — making a DF game actually good

Codec-clean ≠ fun. A game can pass every check and still be a poorly-designed, unfun
plot. Treat design as a real requirement, not an afterthought. The lessons below come from
shipping a mediocre first version — don't repeat them.

## 1. Find the core loop and make *that* fun first
Every minigame is one tight loop the player repeats: *spawn → fight → die → respawn → fight*.
Nail the **30 seconds of moment-to-moment play** before adding kits, shops, cosmetics, or
maps. If the basic fight isn't satisfying, no amount of features saves it. Build the
smallest playable loop, **playtest it**, then layer.

## 2. Feedback on everything
Players need to *feel* every action. A hit, kill, ability, or pickup should fire **sound +
particle + a number/message** within the same tick. A kill that just silently increments a
variable feels dead; a kill with a crit sound, particles, a `+1 ⚔` actionbar, and a heal
feels great. **Juice is not optional polish — it's core to whether the loop feels good.**

## 3. Balance = counterplay, not equal numbers
"Balanced" doesn't mean every kit has the same stats — it means **every strategy has a
counter** and **no single choice dominates**. Give each option a clear strength *and* a real
weakness (the glass-cannon dies to ranged; the tank is slow; the assassin is fragile). Then
**try to break your own game** — if one kit/tactic wins everything, fix it before shipping.
Avoid: unavoidable damage, infinite combos, a "best kit," safe spots with no risk.

## 4. Pacing and stakes
Fast respawns keep players in the action. Raise stakes over a life — killstreaks, escalating
rewards, announcements at milestones — so a long streak *means* something and ending one is
satisfying. Always allow comebacks; runaway leaders with no counter kill engagement.

## 5. Onboarding & "can't get stuck"
A new player should understand what to do in 5 seconds (a title, a clear menu, a one-line
tip) and **never get soft-locked** (falling forever, no kit, stuck in a wall, empty map).
Sensible defaults: auto-assign a kit, spawn somewhere safe, protect on spawn.

## 6. Progression & reasons to return
Short-term: killstreaks, the current fight. Long-term: persistent stats (K/D, best streak),
unlocks, cosmetics, leaderboards, currency. Even simple saved stats shown in the tab list
give players a reason to come back.

## 7. Scope discipline
A polished single loop beats a sprawling buggy one. Ship the core, playtest, then add **one**
feature at a time. Cut anything that isn't pulling its weight.

## 8. Learn from what's good
Top DF games are studied for a reason — clean onboarding, tight loops, heavy juice,
readable HUDs, fair balance. When unsure, ask "how would a popular game handle this moment?"
and match that bar. Pair this with `styling.md` — **how it looks and feels is half the design.**
