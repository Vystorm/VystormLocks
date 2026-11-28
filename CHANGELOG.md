## 1.5.0 — 2026-11-27

- New menu: plain `/vlock` (or `/lock`) now opens it, and it is listed in Core's main menu `/vmenu`. It shows the container you look at (owner, whether it has a password) with buttons to protect it, remove the protection, set or remove a password, or enter the password of someone else's container, and your chest buddies with a remove button each and "Add buddy". Nobody has to type `/chestbuddy add NAME` or `/vlock password WORD` any more; the buttons run the same code, with the same limits and messages.
- "Add buddy" opens Vystorm Core's player picker (online, nearby, recent and search) on Core 0.71.0 or newer. With an older Core it asks for the name in a text window. The menu works as a Vystorm Screen and as a chest menu (Spigot, Paper 1.20.6, screens off); in the chest a buddy is removed with a second click.
- Core's player menu (sneak and right-click a player) has a new entry to add that player as chest buddy or remove them again.
- The info line that plain `/vlock` showed before is now `/vlock info` (it was already there). Where no menu can open, plain `/vlock` still shows the info line. All commands stay as they were.
- Still runs with Vystorm Core 0.65.0 or newer. `locks.yml` and `config.yml` are unchanged.

## 1.4.0 — 2026-11-22

- New ranking `locks:containers`: the players with the most protected containers. Vystorm Boards shows it on a board with `{type: top, source: "locks:containers", rows: 10}`, and the same values are placeholders, for example `%vystorm_locks_top_containers_1_name%` and `%vystorm_locks_rank_containers%`. It counts the locks that `locks.yml` already holds, so players who are offline are listed from the first start. It shows names and counts only, never where a container stands.
- The ranking needs Vystorm Core 0.67.0 or newer, and Vystorm Boards 1.21.0 or newer to show it on a board. The plugin itself still runs with Vystorm Core 0.65.0 or newer; with an older Core it offers no ranking and everything else works as before.
- New switch `rankings.enabled`, on by default and listed in `/vadmin features` as "Rankings" in seven languages. Off, or with `locks.enabled` off, the ranking is not offered; the change applies at once without a restart.
- The file format of `locks.yml` is unchanged.

## 1.3.2 — 2026-11-20

- Placing or breaking a container no longer writes the whole `locks.yml` again on the server thread. Changes are collected and written together at most every 5 seconds by a background thread, so large storage rooms and hopper sorters no longer cost a short stall with every block placed.
- A normal server stop writes every change. After a crash, at most the changes of the last 5 seconds are lost: containers placed in that time are unprotected again and can be locked with `/vlock on`.
- The file format is unchanged, so 1.3.1 reads the file as before.

## 1.3.1 — 2026-11-20

- A damaged `locks.yml` no longer loses the locks: a file with a YAML syntax error is no longer replaced by a nearly empty file. The console names the line and column of the error; the file stays as it is until a lock or buddy changes, and is then first copied to `locks.yml.broken-<date>` (named in the console), so the saved locks can be recovered. While the file is broken its locks are not loaded, so fix it and restart.
- Reloading `config.yml` through `/vadmin` with a YAML syntax error keeps the last working settings instead of falling back to the defaults; the console names the line and column. The file is never changed.
- `/vadmin features` shows every switch of the plugin with a readable name in the admin's language, for example "Automatic protection" and "Wither and mob protection", instead of a name built from the config key such as "Auto protect". The names exist in all seven languages.
- A command that needs `locks.enabled` now names the switch in full: "This needs “Container protection” (locks.enabled), which is switched off on this server."
- README: names the platforms the plugin was tested on (Paper and Purpur 26.2, Paper 1.20.6, Spigot 26.2) instead of saying that no Spigot start was recorded.

## 1.3.0 — 2026-11-18

- Requires Vystorm Core 0.65.0 or newer. Update Core before you update this plugin.
- Every feature can be switched off on its own, now really every one. All switches are on by default, so a server that updates keeps working as before; the new keys are added to your existing `config.yml` with their comments. Each switch is listed in Core's `/vadmin features` with a one-line description in seven languages and applies at once, without a restart (only `tutorial.enabled` needs one).
  - The whole plugin: `locks.enabled`. Off: no container is protected and `/vlock` and `/chestbuddy` are switched off; every lock stays saved and protects again when it is back on.
  - New switches: `piston-protection.enabled`, `wither-protection.enabled` (withers and other mobs that change blocks), `fire-protection.enabled`, `manual-locks.enabled` (`/vlock`, `/vlock on`, `/vlock off`), `tutorial.enabled` (needs a restart). Before, pistons, withers and fire were always blocked.
  - The five switches of 1.1.0 keep their names: `auto-protect`, `chest-buddies`, `passwords`, `hopper-protection`, `explosion-protection`.
- A switched-off feature answers one shared message, "This feature is switched off on this server.", and its commands are hidden from help and tab completion. A command whose own switch is on while `locks.enabled` is off names that switch.
- Tab completion and help follow a switch at once, without registering the command again.
- Placeholders: the plugin registers its values with Vystorm Core under the namespace `locks`: containers the player protected, the player's chest buddies and all protected blocks on the server. Examples: `%vystorm_locks_locks_count%` (the player's protected containers) and `{locks:locks_total}`. `/vadmin placeholders locks` lists them. A value of a switched-off feature is empty.
- README: Java 21 named, pistons, withers and fire listed, and the Spigot claim replaced by what the build checks.

## 1.2.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The container protection (opening, breaking, pistons, fire, explosions and hoppers) now denies before other plugins act.
- Lighter on the server: the `protect` material patterns are prepared once when the config is loaded, and only chests are checked for a double-chest partner.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.1.0 — 2026-11-16

- **Every feature can be switched off on its own:** new switches `chest-buddies` and `passwords` next to `auto-protect`, `hopper-protection` and `explosion-protection`. With a feature off its commands say so and it gives no access; saved buddy lists and passwords stay.
- The config is in Core's editor now (`/vadmin config VystormLocks`), and with Core 0.60.0 the five switches are in `/vadmin features`. Changes apply at once, without a restart.
- Core's update check knows the SpigotMC page of this plugin.

## 1.0.0 — 2026-11-15

- First version: automatic protection of placed containers, chest buddies (`/chestbuddy`), passwords (`/vlock password`, `/vlock unlock`), hopper and explosion protection, 7 languages, tutorial.
