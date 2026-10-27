package me.roanh.boysbv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import java.util.List;

final class HelpPages {
    static final int PAGE_SIZE = 8;
    static final TextColor ORANGE = TextColor.color(0xFFA500);
    record Entry(String command, String description) { }

    static int pages(List<Entry> entries) { return Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE); }

    static void show(CommandSender sender, String root, List<Entry> entries, String[] args) {
        BoysPlugin.require(args.length <= 2, "Use /" + root + " help [page].");
        String number = args.length < 2 ? "1" : args[1];
        BoysPlugin.require(number.matches("[0-9]{1,6}"), "Choose a help page from 1 to " + pages(entries) + ".");
        int page = Integer.parseInt(number);
        BoysPlugin.require(page >= 1 && page <= pages(entries), "Choose a help page from 1 to " + pages(entries) + ".");
        sender.sendMessage(Component.text("Boys B.V. /" + root + " — page " + page + "/" + pages(entries), ORANGE));
        for (Entry entry : entries.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, entries.size()))) {
            sender.sendMessage(Component.text(entry.command(), ORANGE)
                    .append(Component.text(" — " + entry.description(), NamedTextColor.WHITE)));
        }
        Component navigation = Component.empty();
        if (page > 1) navigation = navigation.append(link("« Previous", root, page - 1));
        if (page > 1 && page < pages(entries)) navigation = navigation.append(Component.text(" | ", NamedTextColor.WHITE));
        if (page < pages(entries)) navigation = navigation.append(link("Next »", root, page + 1));
        if (pages(entries) > 1) sender.sendMessage(navigation);
    }

    private static Component link(String label, String root, int page) {
        return Component.text(label, ORANGE).clickEvent(ClickEvent.runCommand("/" + root + " help " + page));
    }
}
