# Verification — 26.1

Observed 2026-10-20 on this branch: gradlew build and gradlew runClientGameTest both succeeded (Fabric API 0.145.1+26.1, Mod Menu 18.0.2). Ordering, anvil highlight, hammer, tooltip and auto-clear behaviour were not exercised by hand in game on this branch.

Behaviour verified on main (26.3) is listed below; it was not re-run per branch beyond the GameTest above.

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
