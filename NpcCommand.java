package dev.npcdialogue;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NpcCommand implements CommandExecutor, TabCompleter {
    private final NpcDialoguePlugin plugin;

    public NpcCommand(NpcDialoguePlugin plugin) {
        this.plugin = plugin;
    }

    private void help(CommandSender s) {
        s.sendMessage("§6/npc §7— конструктор (GUI)");
        s.sendMessage("§6/npc create <id> §7— создать NPC здесь");
        s.sendMessage("§6/npc edit <id> §7· §6remove <id> §7· §6tp <id> §7· §6list");
        s.sendMessage("§6/npc talk <id> [игрок] §7— запустить диалог");
        s.sendMessage("§6/npc emote <id> <анимация> §7— проиграть эмоцию");
        s.sendMessage("§6/npc reload §7— перечитать npcs.yml");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("npcdialogue.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }
        if (a.length == 0) {
            if (sender instanceof Player p) plugin.editor.main(p);
            else help(sender);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player p) || a.length < 2) {
                    sender.sendMessage("§cИспользование (из игры): /npc create <id>");
                    return true;
                }
                String id = a[1].toLowerCase(Locale.ROOT);
                if (!NpcStorage.ID.matcher(id).matches() || plugin.storage.npcs.containsKey(id)) {
                    sender.sendMessage("§cНекорректный или занятый id.");
                    return true;
                }
                Npc n = plugin.storage.create(id, p.getLocation());
                plugin.npcs.spawn(n);
                plugin.editor.npc(p, n);
            }
            case "remove", "delete" -> {
                Npc n = find(sender, a);
                if (n == null) return true;
                plugin.npcs.despawn(n);
                plugin.storage.npcs.remove(n.id);
                plugin.storage.save();
                sender.sendMessage("§aNPC удалён.");
            }
            case "edit" -> {
                if (!(sender instanceof Player p)) return true;
                if (a.length < 2) plugin.editor.main(p);
                else {
                    Npc n = find(sender, a);
                    if (n != null) plugin.editor.npc(p, n);
                }
            }
            case "list" -> sender.sendMessage("§6NPC: §f" + String.join(", ", plugin.storage.npcs.keySet()));
            case "tp" -> {
                Npc n = find(sender, a);
                if (n != null && sender instanceof Player p && n.location() != null) p.teleport(n.location());
            }
            case "talk" -> {
                Npc n = find(sender, a);
                if (n == null) return true;
                Player target = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage("§cИгрок не найден.");
                    return true;
                }
                if (!plugin.dialogues.start(target, n)) sender.sendMessage("§cНе удалось начать диалог.");
            }
            case "emote" -> {
                Npc n = find(sender, a);
                if (n == null || a.length < 3) return true;
                plugin.npcs.playEmote(n, String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
            }
            case "reload" -> {
                plugin.dialogues.endAll();
                plugin.npcs.despawnAll();
                plugin.reloadConfig();
                plugin.storage.load();
                plugin.npcs.spawnAll();
                sender.sendMessage("§aПерезагружено: " + plugin.storage.npcs.size() + " NPC.");
            }
            default -> help(sender);
        }
        return true;
    }

    private Npc find(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage("§cУкажи id NPC.");
            return null;
        }
        Npc n = plugin.storage.npcs.get(a[1].toLowerCase(Locale.ROOT));
        if (n == null) s.sendMessage("§cNPC не найден.");
        return n;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("create", "remove", "edit", "list", "tp", "talk", "emote", "reload"))
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("create") && !a[0].equalsIgnoreCase("list") && !a[0].equalsIgnoreCase("reload")) {
            for (String id : plugin.storage.npcs.keySet()) if (id.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(id);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("emote")) {
            Npc n = plugin.storage.npcs.get(a[1].toLowerCase(Locale.ROOT));
            if (n != null) out.addAll(plugin.models.animations(n.model).keySet());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("talk")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        return out;
    }
}
