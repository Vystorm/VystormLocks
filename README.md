# Vystorm Locks

Free container protection for Paper 1.20.6–26.x, Java 21 or newer (the build compiles it against the Paper 1.20.6 API). Tested on Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2. It needs [Vystorm Core](https://vystorm.net) 0.65.0 or newer (free).

- **Automatic protection:** chests, barrels, shulker boxes, furnaces, hoppers, dispensers, droppers and brewing stands you place are protected right away. Double chests share the protection.
- **Chest buddies:** `/chestbuddy add NAME`, `remove NAME` and `list` manage friends who may open all your protected containers.
- **Passwords:** `/vlock password WORD` puts a password on a container. Others open it with `/vlock unlock WORD` until they log out. Only a salted hash is stored.
- **Menu (1.5.0):** `/vlock` (or `/lock`, also in `/vmenu`) opens a menu with the container you look at (protect, remove, set or enter a password) and your chest buddies (remove, "Add buddy" through Vystorm Core's player picker on Core 0.71.0 or newer, else a name prompt). Works as a Vystorm Screen and as a chest menu. Sneak and right-click a player for "Add as chest buddy".
- **Manage protection:** `/vlock info` shows the owner, `/vlock on` protects a container by hand, and `/vlock off` removes the protection.
- **No loopholes:** strangers cannot break protected containers, hoppers cannot pull items out (unless they belong to the same owner), and explosions, pistons, withers and fire do not get them either.
- **Permissions:** `vystormlocks.use` (default true) and `vystormlocks.bypass` (op).
- **Languages:** 7 languages through the Vystorm Core language system, with a tutorial in `/vmenu`.

Settings are in `config.yml`: `protect` (block list, `*` wildcards), `max-buddies` and the switches.

## Switches

Every feature has an on/off switch, all on by default, listed in Core's `/vadmin features` with a one-line description. They apply at once, except `tutorial.enabled` (restart). A switched-off feature answers "This feature is switched off on this server." and its placeholders are empty; locks, passwords and buddy lists stay saved.

- `locks.enabled`: the container protection itself (off: nothing is protected, `/vlock` and `/chestbuddy` are off).
- `auto-protect`, `manual-locks.enabled` (`/vlock`, `/vlock on`, `/vlock off`), `chest-buddies`, `passwords`.
- `hopper-protection`, `explosion-protection`, `piston-protection.enabled`, `wither-protection.enabled`, `fire-protection.enabled`.
- `tutorial.enabled`.
- `rankings.enabled`: the ranking `locks:containers` (needs Vystorm Core 0.67.0 or newer).

## Placeholders

Namespace `locks`, listed by `/vadmin placeholders locks`: `%vystorm_locks_locks_count%` (containers the player protected), `%vystorm_locks_buddies_count%` and `%vystorm_locks_locks_total%`. Short form in Vystorm texts: `{locks:locks_count}`.

## Rankings (1.4.0)

With Vystorm Core 0.67.0 or newer the plugin offers one ranking: `locks:containers`, protected containers per player (offline players included, no locations). Vystorm Boards 1.21.0 or newer shows it on a board; put a text element above it as heading:

```yaml
elements:
  - {type: text, text: "<gold>Most protected containers", x: 16, y: 12, size: 22}
  - {type: top, source: "locks:containers", x: 16, y: 48, width: 480, rows: 10, highlight: true}
```

| Placeholder | Value |
| --- | --- |
| `%vystorm_locks_top_containers_<n>_name%` | Name on place n (1 to 100), empty if nobody holds it |
| `%vystorm_locks_top_containers_<n>_value%` | Containers on place n, formatted in the viewer's language (`_raw` for the plain number) |
| `%vystorm_locks_rank_containers%` | The viewer's place, `-` if none |
| `%vystorm_locks_value_containers%` | The viewer's containers, `-` if none |

Short form in Vystorm texts: `{locks:top_containers:1:name}`, `{locks:rank_containers}`. `/vadmin placeholders rankings` lists the ranking. Switch: `rankings.enabled`. With an older Core the plugin offers no ranking and works as before.

## Build
`./gradlew build -PvystormCoreJar=/path/to/Vystorm_Core.jar`

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.1.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.1.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.1.0.txt](LICENSE-MIT-until-1.1.0.txt)) and stay available under those terms.
