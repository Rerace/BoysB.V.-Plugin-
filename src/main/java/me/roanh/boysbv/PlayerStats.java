package me.roanh.boysbv;

import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

/** Use Minecraft's existing saved statistics, including history before installation. */
record PlayerStats(long deaths, long playTicks, long playerKills) {
    static PlayerStats read(OfflinePlayer player) {
        var online = player.getPlayer();
        OfflinePlayer source = online == null ? player : online;
        return new PlayerStats(source.getStatistic(Statistic.DEATHS),
                source.getStatistic(Statistic.PLAY_ONE_MINUTE), source.getStatistic(Statistic.PLAYER_KILLS));
    }

    PlayerStats plus(PlayerStats other) {
        return new PlayerStats(deaths + other.deaths, playTicks + other.playTicks, playerKills + other.playerKills);
    }

    String summary() {
        return "Deaths: " + deaths + " | Play time: " + duration(playTicks) + " | Player kills: " + playerKills;
    }

    static String duration(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return seconds / 86400 + "d " + seconds / 3600 % 24 + "h " + seconds / 60 % 60 + "m " + seconds % 60 + "s";
    }
}
