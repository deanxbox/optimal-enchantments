# Verification — 26.3

**Latest edits (live plan, persistence, item tooltips, hammer, auto-clear, stable ordering):** the user reports a successful `gradlew build`. GameTests and in-game behaviour have not been run. The historical results below describe earlier revisions.

| Current follow-up check | Result | Evidence / next check |
| --- | --- | --- |
| Compact panel and vanilla framing | not_run | Source uses a centered frame capped at 640 x 320 GUI pixels including side borders, shrinking with the viewport; manual visual approval needed at normal and high GUI scales |
| Retain order on close/reopen | not_run | Shared planner calculation survives screen removal; selection/connection changes invalidate it; updated client test awaits execution |
| Specific missing inputs without background | not_run | Removed guidance fill; wraps equipment/book names and levels; updated client test awaits execution |
| Build, solver tests, client launch | not_run | Build reported successful by the user; solver tests, runClient and GameTests not re-run on the latest edits |

Observed on 2026-10-19 with Java 25.0.1, Gradle 9.7.0, Loom 1.17.21, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3 and Mod Menu 21.0.0.

| Check | Result | Evidence |
| --- | --- | --- |
| Build and solver tests | verified | `gradlew.bat build`; 19 tests, zero failures/errors |
| Small-case optimality | verified | Independent brute-force oracle across 90 objective/cap/input combinations |
| Large selections, prior work, cancellation, overflow | verified | Pure-Java solver regression tests; larger searches explicitly approximate |
| Game starts with production mod | verified | `gradlew.bat runClient` exited successfully |
| Client integration | verified | `gradlew.bat runClientGameTest` exited successfully |
| Registry custom enchantments | verified | Test-only Test Edge registry entry: maximum level 2 and anvil cost 14; calculated book cost 14 for level 2 |
| Compatibility and count toggles | verified | Client test rejects incompatible/over-ten selections with switches off, accepts hypothetical selection with switches on |
| Offline configuration | verified | Mod Menu screen factory opens without a connection; screenshot taken without a crash |
| Equipment matching and highlights | verified | Actual server-opened anvil with two swords; closest enchanted sword selected; cyan/gold screenshot inspected |
| Anvil planner transition | verified | Enchanted-book button opens planner; Done returns; populated input slots remain present |
| Actual vanilla combination | verified | Server-side survival anvil charges three levels, output is taken, completed target produces no further plan steps |
| Persistence | verified | Save/load roundtrip within the client test; full process-restart UI confirmation not_run |
| Arbitrary multiplayer plugin formulas | not_run | No external multiplayer server fixture; only registry-driven prediction is implemented; no live-cost comparison |
| Modrinth upload | not_run | No credentials, upload, push, or remote setup attempted |

Generated logs, test reports, screenshots, worlds and jars are ignored build/runtime artifacts. Re-run the commands above to reproduce. Tests run against a disposable local integrated server, not an external multiplayer server. Test-only enchantment data is excluded from the production jar.
