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
