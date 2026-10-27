# Boys B.V. — Teams and Sleep

A standalone server plugin for **Paper 26.3**, requiring **Java 25 or newer**.
The Paper API is pinned to `26.3.build.222-stable`. No extra plugin is required.
LuckPerms can manage the standard Bukkit permission `boysbv.team`.

## Install

1. Build with `./gradlew.bat build` from the repository root (Linux: `sh ./gradlew build`).
2. Stop the server and copy `build/libs/BoysBV-1.0.0.jar` into `plugins/`.
3. Start Paper 26.3. In game, run `/team help`; operators can run `/sleep help`.

The plugin creates `plugins/BoysBV/config.yml` and saves teams in `plugins/BoysBV/teams.yml`.
Changes from commands save automatically. Back up both files when moving servers.
Restart after manual configuration edits. Do not edit `teams.yml` while the server runs.
Invalid saved team data stops the plugin from enabling, preserving the file for repair.
Failed saves roll back the in-memory change and report an error.

Use `/boysbv:team` and `/boysbv:sleep` if another plugin owns those commands.
The workspace's older SurvivalExtra plugin also manages sleeping; disable its sleep feature
before installing BoysBV. Avoid running two sleep or locator managers together.

## Teams

| Command | Behaviour |
| --- | --- |
| `/team create <name>` | Creates an invite-only team with you as leader. Names are unique ignoring case, 1–32 characters. Spaces and periods are supported. |
| `/team join <name>` | Joins an open team or accepts your invitation. One team per player. |
| `/team leave` | Members can leave; leaders must transfer leadership first. |
| `/team disabandon` | Deletes your team only when you are its sole member and leader. `/team disband` also works. |
| `/team invite <player>` | Leader invites an online or previously known player. Invitations survive restarts. |
| `/team invite accept <team name>` | Accepts an invitation. |
| `/team invite deny <team name>` | Declines an invitation. |
| `/team chat` | Toggles between private team chat and normal global chat. Resets on logout/removal/leave. |
| `/team info` | Shows team name, leader, members, ranks, join mode and PvP setting. |
| `/team promote <player>` | Leader promotes muggle → peasant → leader. Promoting a peasant transfers leadership; the former leader becomes peasant. |
| `/team remove <player>` | Leader removes another member. |
| `/team safe` | Lists up to five shared saved messages, numbered. |
| `/team safe add <message>` | Any member saves a message, up to 256 characters. `/team safe <message>` also works. |
| `/team safe remove <number>` | Leader removes a saved message, freeing a slot. |
| `/team settings <open\|invite\|closed>` | Leader changes joining rules. Closed blocks invitations and joins and clears outstanding invitations. |
| `/team color <color>` | Leader chooses a standard color name or `#RRGGBB`, used in team info, private chat and team locator dots. |
| `/team name <name>` | Leader renames the team without losing members or invitations. |
| `/team pvp <allow\|deny>` | Leader changes friendly damage. Default: deny. Applies to player-caused melee, projectile and other attributed damage; environmental damage still applies. |
| `/team compass <team\|global\|off>` | **Server operator only.** Sets the locator mode for everyone. |

Peasants have the same commands as muggles until promoted to leader.
An operator does not bypass team leadership checks.
If a leader is offline, team leadership remains theirs.

### XP-bar compass

`global` leaves Minecraft's ordinary locator in control. `off` suppresses receiving locator
waypoints for every player. `team` suppresses the ordinary locator and sends native XP-bar
waypoints only for online teammates in the same world, refreshed four times per second.
Players without a team see no player waypoints in team mode. Team mode respects the world's
`locatorBar` game rule, vanished players, sneaking, invisibility, spectator targets,
head/pumpkin concealment and the target's transmit range.

Only this plugin's temporary receive-range modifier is removed when returning to global,
on logout or on clean shutdown. Other plugins' attribute modifiers are preserved.
Team mode uses a small adapter to Minecraft 26.3's waypoint packets because Paper's public
API does not expose per-viewer waypoint filtering. If that adapter is unavailable, selecting
team mode is refused; an already configured team mode hides all dots and logs the failure.
Recheck this adapter before upgrading Minecraft.

## Sleeping — server operators only

| Command | Behaviour |
| --- | --- |
| `/sleep amount <number>` | Sets a positive number of sleepers required. If fewer eligible players are online, all of them sleeping is sufficient. |
| `/sleep amount <percentage>%` | Sets 1%–100%, rounded up. Default: 50%. Example: `/sleep amount 50%`. |
| `/sleep text add <text>` | Appends a sleep announcement. |
| `/sleep text remove <number>` | Removes a numbered announcement. |
| `/sleep text info` | Lists all saved texts with numbers. |
| `/sleep text random <allow\|deny>` | Chooses messages randomly, or cycles through them in order when denied. |
| `/sleep daytime <allow\|deny>` | Allows resting in beds during the day. Default: deny. Daytime resting never skips a full day. |

Announcements support `{player}`, `{world}`, `{sleeping}`, `{required}` and `{online}`.
Delete every announcement to silence them. Messages are plain text, so player text cannot
inject formatting or command actions.

Sleep counts are **per normal/Overworld world**, matching vanilla's independent nights.
Spectators and players marked sleeping-ignored do not count. Sleepers must be in bed for
100 ticks (five seconds) before counting toward a skip. The plugin lets the server perform
the normal night transition and weather handling. Original sleep game rules are restored
on clean shutdown and recorded in world data for recovery after an interrupted run.

## LuckPerms

`boysbv.team` defaults to true. To grant it explicitly:

```text
/lp group default permission set boysbv.team true
```

Sleep settings and compass settings check operator status directly, as requested.
Granting a LuckPerms wildcard does not substitute for being a server operator.

## Development and verification

Build and test: `./gradlew.bat build` (Java 25+; the wrapper supports Java 26).
Automated JUnit/MockBukkit checks cover team lifecycle, leadership, permissions, invitations,
shared notes, disk persistence and failed-write rollback, chat isolation, friendly melee and
projectiles, sleep thresholds, daytime skip prevention and game-rule restoration.
MockBukkit does not render the native locator: verify team/global/off using two team members
and a third player in a separate team on a Paper 26.3 test server before production use.
Also check real bed entry during daytime and a full night transition in game.

References: [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/),
[Minecraft 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3).
