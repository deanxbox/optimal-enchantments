# Optimal Enchantments

A client-side Fabric enchantment planner, heavily inspired by [iamcal's Enchantment Ordering Tool](https://iamcal.github.io/enchant-order/). Implementation is independent; no website code or assets are bundled.

Created by [deanxbox](https://github.com/deanxbox).

## Use

Install Fabric API and Mod Menu alongside the version-matched mod jar. Open **Mods → Optimal Enchantments → Configure**, or click the enchanted book at the top-left of an anvil.

1. Choose equipment from the selection box at the top; its item icon appears on the left. The dropdown supports searching by name or registry ID. Choosing different equipment clears the old enchantment selection.
2. Click a numbered level beside an enchantment; click the selected level again to remove it. Only enchantments supported by the chosen item appear. Clear removes the entire selection, including entries unavailable on the current server.
3. Below the list, choose least total levels, least XP points, or least final prior-work penalty. Checkboxes allow mutually incompatible enchantments and selections above ten; they never expose enchantments unsupported by the item.
4. Click **Calculate order** and follow each combination **left operand + right operand**. The planner uses a centered, vanilla-style panel. Results appear alongside the selection on wide windows, or below it on narrow windows. Scroll to reach longer lists/results, and hover truncated text to read it in full.

Your selection and its calculated order are saved in the config, so they persist across closing the planner and restarting the game. The order is live: it recalculates whenever your selection, planning options or inventory change, and the anvil guidance and planner always show the same plan. Finishing the planned steps on an item clears the selection automatically; Clear does so manually.

Inside an anvil, cyan outlines identify the closest matching piece of your selected equipment and the next left operand; gold identifies the next right book. Existing enchantments and repair penalties are included. Guidance above the anvil has no background box. It lists what the next combination is still missing (levels in Roman numerals) or, when you hold everything, shows the combination in green. Outlines are drawn beneath item tooltips. When both ingredients are in your inventory but not yet in the anvil inputs, a hammer (small anvil icon) button under the arrow moves them into the two input slots with ordinary inventory clicks; you still take the result yourself. The mod never changes enchanting rules.

Settings are stored in `config/optimal-enchantments.json`. On the title screen, settings can be opened but registry-dependent choices require joining a world/server first.

## Costs and limits

- **Least levels:** minimize the sum of the levels charged for all combinations.
- **Least XP:** minimize the sum of XP needed to reach each operation's cost **from level zero**. This conventional comparison is not the exact XP spent from your current experience level.
- **Least prior work:** minimize the final item's stored repair penalty, then total levels.
- Costs use the connected game's enchantment registry: supported items, maximum levels, incompatibilities, and book multipliers (`max(1, anvil_cost / 2)`). This includes synced datapack/mod enchantments without a hardcoded enchantment list.
- Exact search covers up to ten books, subject to a 2-second/8-million-merge budget. Larger/budget-exhausted searches use a bounded fallback, clearly marked **Approximate**; they are not guaranteed optimal. Failure of an approximate search does not prove no valid order exists.
- A preview assumes one desired-level book per missing enchantment and a fresh item. Inventory guidance uses the closest matching item, existing repair penalties, and suitable available books. Disjoint multi-enchantment books are supported; overlapping/extraneous books and combining lower-level books into desired levels are not modeled. Missing books are shown as needed ingredients, not fabricated in your inventory. “Exact” concerns ordering the chosen ingredients, not a global search over every possible inventory ingredient selection.
- **Allow incompatible** is hypothetical planning, not a bypass of server restrictions. Likewise, disabling the survival cost cap does not permit otherwise-illegal operations on a vanilla server. Existing over-level enchantments or custom server behavior can differ from the simplified model.

### Server behavior

Registry-provided costs/compatibility are used from the active server. Costs shown are predictions from the registry; the anvil's own cost is authoritative.

Arbitrary server plugin formulas are not transmitted to a vanilla client. The mod cannot safely infer a universal formula from one observed operation and does not claim to re-optimize around unknown formulas. Renaming, durability repairs, and combining two equipment items are outside this book-application planner. Remove an unintended rename before comparing costs.

## Development

Requires Java 25. Dependencies and Gradle wrapper are version-pinned.

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runClientGameTest
```

`build` runs the pure-Java solver tests. Client GameTests use their own disposable run directory under `build/run/clientGameTest`; they open the actual UI/anvil, load a test-only custom enchantment, verify config persistence, check closest-item selection, and exercise returning to the anvil without losing inputs. Test fixtures are not packaged in the production jar. Screenshots are generated under that run directory.

Branches: `main` targets 26.3; `26.2` and `26.1` hold their version-specific sources and dependency pins. Build the branch matching your game. Jars are written to `build/libs`.

## Modrinth publishing

Minotaur is configured for [optimal-enchantments](https://modrinth.com/project/optimal-enchantments). The default version type is alpha. Publishing is explicit and is **not** part of `build` or `push`:

```powershell
# Supply MODRINTH_TOKEN through your environment or CI secrets; never commit it.
.\gradlew.bat build modrinth
```

Update `mod_version` for releases. Each upload is named with its Minecraft version, and declares Fabric API and Mod Menu as required dependencies. No remote or automatic release workflow is configured.
