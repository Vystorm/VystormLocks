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
