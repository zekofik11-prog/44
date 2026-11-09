package dev.npcdialogue;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Ввод текста через чат для конструктора. */
public final class ChatPrompt implements Listener {
    private record Pending(Consumer<String> ok, Runnable cancel) {}

    private final NpcDialoguePlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ChatPrompt(NpcDialoguePlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String prompt, Consumer<String> ok, Runnable cancel) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(ok, cancel));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<gold>▶ <white>" + prompt + " <gray>(напиши в чат; «отмена» — выйти)"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.remove(e.getPlayer().getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (msg.equalsIgnoreCase("отмена") || msg.equalsIgnoreCase("cancel") || msg.isEmpty()) pd.cancel().run();
            else pd.ok().accept(msg);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
