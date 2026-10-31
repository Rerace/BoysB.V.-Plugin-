# Boys B.V. — Teams and Sleep

A standalone server plugin for **Paper 26.3**, requiring **Java 25 or newer**.
The Paper API is pinned to `26.3.build.222-stable`. No extra plugin is required.
LuckPerms can manage the standard Bukkit permission `boysbv.team`.

## Install

1. Build with `./gradlew.bat build` from the repository root (Linux: `sh ./gradlew build`).
2. Stop the server and copy `build/libs/BoysBV-1.3.0.jar` into `plugins/`. Remove the previous BoysBV JAR when upgrading.
3. Start Paper 26.3. In game, run `/team help`; operators can run `/sleep help` and `/boys help`.

The plugin creates `plugins/BoysBV/config.yml` and saves teams in `plugins/BoysBV/teams.yml`.
Changes from commands save automatically. Back up both files when moving servers.
Restart after manual configuration edits. Do not edit `teams.yml` while the server runs.
Invalid saved team data stops the plugin from enabling, preserving the file for repair.
Failed saves roll back the in-memory change and report an error.

Use `/boysbv:team`, `/boysbv:sleep` and `/boysbv:boys` if another plugin owns those commands.
The workspace's older SurvivalExtra plugin also manages sleeping; disable its sleep feature
before installing BoysBV. Avoid running two sleep or locator managers together.

## Teams

| Command | Behaviour |
| --- | --- |
| `/team create <name>` | Every player can create an invite-only team with themselves as chosen one and a random color. Leave your current team first. Names are unique ignoring case, 1–32 characters. Spaces and periods are supported. |
| `/team join <name>` | Joins an open team or accepts your invitation. One team per player. |
| `/team leave` | Members can leave; leaders must transfer leadership first. |
| `/team disabandon` | Deletes your team only when you are its sole member and leader. `/team disband` also works. |
| `/team invite <player>` | Peasants and the chosen one can invite an online or previously known player. Invitations survive restarts. |
| `/team accept <team name>` | Accepts an invitation. Names with spaces are supported. |
| `/team deny <team name>` | Declines an invitation. |
| `/team chat` | Toggles between private team chat and normal global chat. Resets on logout/removal/leave. |
| `/team info` | Shows your team's name, chosen one, members, ranks, join mode, PvP setting, and each member's deaths, total play time and player kills, plus team totals. |
| `/team info <team>` | Shows the same information for a named team, even if you are not a member. Names can contain spaces. |
| `/team player info [player]` | Everyone can view deaths, total play time and player kills for themselves or any named player who has logged in before. Console must supply a name. |
| `/team promote <player>` | Chosen one promotes muggle → peasant → chosen one. Promoting a peasant transfers leadership; the former chosen one becomes peasant. |
| `/team remove <player>` | Leader removes another member. |
| `/team safe` | Lists shared saved messages, numbered, and the current limit (default five). |
| `/team safe add <message>` | Peasants and the chosen one can save a message, up to 256 characters. `/team safe <message>` also works with the same rank restriction. All members can read saved messages. |
| `/team safe remove <number>` | Leader removes a saved message, freeing a slot. |
| `/team settings <open\|invite\|closed>` | Leader changes joining rules. Closed blocks invitations and joins and clears outstanding invitations. |
| `/team color <color>` | Leader chooses a standard color name or `#RRGGBB`, used in team info, private chat and team locator dots. |
| `/team name <name>` | Leader renames the team without losing members or invitations. |
| `/team pvp <allow\|deny>` | Leader changes friendly damage. Default: deny. Applies to player-caused melee, projectile and other attributed damage; environmental damage still applies. |
| `/team hearts` | Chosen one toggles health labels above teammates. Only other members of that team can see them. Default: off; the setting survives restarts. |
| `/team place <player> <team>` | Operator/console assigns a previously known player to a team, bypassing invitations and closed joining. Team names can contain spaces. |
| `/team promote <player> <team>` | Operator/console directly appoints an existing member of the specified team as chosen one, regardless of their current rank. The former chosen one becomes peasant. |
| `/team safe <amount>` | Operator/console sets the message limit for all teams, from 0 to 10000. Existing messages are retained, including across restarts. |
| `/team compass <team\|global\|off>` | **Server operator only.** Sets the locator mode for everyone. |
| `/team help [page]` | Shows only commands available for your membership, rank and operator status. Eight commands per page, one per line, orange commands and white descriptions. Click Previous/Next to navigate. |

Lowering the saved-message limit never deletes old messages. For example, lowering from
seven stored messages to a limit of two keeps all seven readable; delete messages until
only one remains before adding a new one. A limit of zero disables new messages. To save
a message consisting only of a number, use `/team safe add <number>`; the bare numeric
form is reserved for the operator limit command.

When an operator moves a chosen one to another team, the old team appoints a remaining
peasant first, otherwise the first remaining member. An empty old team is removed.
The destination's existing leader remains chosen one until explicitly changed with the
operator promotion command. Placement and leader reassignment are saved atomically.

Health labels show current/max hearts plus golden absorption hearts. They follow players
without replacing scoreboards. They are hidden for players outside the team, sneaking,
invisible or vanished targets, spectators, and targets in another world, and do not show
through walls. Disabling hearts, leaving the team or shutting down removes the labels.

Global chat and the player list show a colored `[Team name]` before the player name.
Team name and color changes update both automatically; leaving/removal removes the prefix.
Global chat keeps the existing chat renderer, so display names and other formatting remain
available. On shutdown, the plugin restores player-list names it changed.
The old `/team invite accept <team>` and `/team invite deny <team>` forms remain as aliases.
New teams get a random readable color, which is saved across restarts. Existing teams keep
their saved color, and the chosen one can still change it with `/team color`.

Peasants can invite players and add saved messages. Only the chosen one can promote,
remove members, rename/recolor the team, change join/PvP settings or remove saved messages.
"Leader" in the remaining command descriptions means chosen one.
Operators use the explicit three-argument promotion and placement commands to reassign
leadership; operator status does not bypass other leader-only commands.
If the chosen one is offline, an operator can appoint another member. Existing teams keep their
members and promotions on upgrade; the previous leader is displayed as chosen one.

Statistics come directly from Minecraft's existing server statistics, including history
from before plugin installation. Offline team members use their saved statistics.
Play time is displayed in days, hours, minutes and seconds, using 20 game ticks per second.
Team totals sum the lifetime statistics of current members, rather than only their time
in the team. No extra statistics file or reset is required.

### XP-bar compass

`global` leaves Minecraft's ordinary locator in control. `off` suppresses receiving locator
waypoints for every player. `team` suppresses automatic player transmission and sends native XP-bar
waypoints only for online teammates in the same world, refreshed four times per second.
Reception stays enabled in team mode so the client can display those team markers.
Players without a team see no player waypoints in team mode. Team mode respects the world's
`locatorBar` game rule, vanished players, sneaking, invisibility, spectator targets,
head/pumpkin concealment and the target's transmit range.

Only this plugin's temporary receive/transmit-range modifiers are removed when returning to global,
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
| `/sleep amount` | Shows the currently saved sleep requirement. |
| `/sleep text add <text>` | Appends a sleep announcement. |
| `/sleep text remove <number>` | Removes a numbered announcement. |
| `/sleep text info` | Lists all saved texts with numbers. |
| `/sleep text random <allow\|deny>` | Chooses messages randomly, or cycles through them in order when denied. |
| `/sleep help [page]` | Lists commands with the same eight-per-page orange/white help layout. |

Announcements support `{player}`, `{world}`, `{sleeping}`, `{required}` and `{online}`.
Delete every announcement to silence them. Messages are plain text, so player text cannot
inject formatting or command actions.

Sleep counts are **per normal/Overworld world**, matching vanilla's independent nights.
Only the **latest successfully set** requirement applies: `/sleep amount 2` replaces any
previous percentage, and `/sleep amount 50%` replaces any previous count. It applies
immediately and is saved across restarts. Invalid input leaves the previous value active.
Spectators and players marked sleeping-ignored do not count. Sleepers must be in bed for
100 ticks (five seconds) before counting toward a skip. The plugin lets the server perform
the normal night transition and weather handling. Original sleep game rules are restored
on clean shutdown and recorded in world data for recovery after an interrupted run.
Daytime sleeping has been removed. The plugin no longer overrides vanilla bed-entry rules,
and the old `sleep.daytime` configuration key is removed automatically on upgrade.
Only night can skip once the latest saved sleep requirement is met.

## Server controls — operators and console only

| Command | Behaviour |
| --- | --- |
| `/boys tick-rate` | Toggles a private right-side TPS/MSPT scoreboard. Run again to hide it and restore your previous scoreboard. Console prints one sample. |
| `/boys spawner SpawnCount <amount>` | Changes how many mobs a spawner attempts to spawn per cycle. |
| `/boys spawner MaxNearbyEntities <amount>` | Changes the nearby-entity limit. |
| `/boys spawner RequiredPlayerRange <amount>` | Changes the player activation range in blocks. The amount is required. |
| `/boys help [page]` | Shows the server controls you may use. |

The tick monitor refreshes once a second and shows one-minute TPS, average milliseconds
per tick, and a lag status. TPS below 18 or tick time above 50 ms is marked as lagging.
Minecraft's standard scoreboard appears on the right side; it cannot be pinned to an
exact lower-right pixel position without client-side customisation. Monitoring affects
only the operator who enables it, and turns off on logout or loss of operator status.

Spawner amounts must be integers from 1 to 32767. Each chosen setting is saved as a
global override for all block spawners and spawner minecarts. Loaded spawners update
immediately; unloaded ones update when loaded, and newly placed spawners receive the
settings. Unconfigured properties keep their existing Minecraft/custom values. The
spawned mob type, delay and spawn radius are not changed. Overrides survive restarts;
world chunks are never force-loaded to apply them.

## LuckPerms

`boysbv.team` defaults to true. To grant it explicitly:

```text
/lp group default permission set boysbv.team true
```

Sleep settings, server controls and administrative team commands check operator status directly.
Granting a LuckPerms wildcard does not substitute for being a server operator.
The `boysbv.admin` permission defaults to operators and hides the `/boys` and `/sleep`
roots from the standard Bukkit help for ordinary players; operator checks still apply.

## Development and verification

Build and test: `./gradlew.bat build` (Java 25+; the wrapper supports Java 26).
Automated JUnit/MockBukkit checks cover team lifecycle, leadership, permissions, invitations,
shared notes, disk persistence and failed-write rollback, chat isolation, friendly melee and
projectiles, sleep thresholds, removal of daytime bed-entry overrides, game-rule restoration,
orange/white paged help, team tab/chat prefixes, short invitation commands and compass mode
switching, client reception, teammate filtering and marker removal. Administrative checks cover
placement/leadership succession, retaining over-limit notes, role-filtered help, health-label
privacy, known-player statistics, spawner updates and private tick-monitor restoration.
MockBukkit does not render the native locator: verify team/global/off using two team members
and a third player in a separate team on a Paper 26.3 test server before production use.
Also check health-label placement, the right-side tick monitor, spawner activation and a
full night transition in game.

References: [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/),
[Minecraft 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3).
