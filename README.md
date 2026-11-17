# Vystorm Locks

Free container protection for Paper and Spigot 1.20.6–26.x. It needs [Vystorm Core](https://vystorm.net) (free).

- **Automatic protection:** chests, barrels, shulker boxes, furnaces, hoppers, dispensers, droppers and brewing stands you place are protected right away. Double chests share the protection.
- **Chest buddies:** `/chestbuddy add NAME`, `remove NAME` and `list` manage friends who may open all your protected containers.
- **Passwords:** `/vlock password WORD` puts a password on a container. Others open it with `/vlock unlock WORD` until they log out. Only a salted hash is stored.
- **Manage protection:** `/vlock` (or `/lock`) shows the owner, `/vlock on` protects a container by hand, and `/vlock off` removes the protection.
- **No loopholes:** strangers cannot break protected containers, hoppers cannot pull items out (unless they belong to the same owner), and explosions do not destroy them.
- **Permissions:** `vystormlocks.use` (default true) and `vystormlocks.bypass` (op).
- **Languages:** 7 languages through the Vystorm Core language system, with a tutorial in `/vmenu`.

Settings are in `config.yml`: `auto-protect`, `protect` (block list, `*` wildcards), `hopper-protection`, `explosion-protection` and `max-buddies`.

## Build
`./gradlew build -PvystormCoreJar=/path/to/Vystorm_Core.jar`

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.1.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.1.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.1.0.txt](LICENSE-MIT-until-1.1.0.txt)) and stay available under those terms.
