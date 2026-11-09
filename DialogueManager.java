package dev.npcdialogue;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DialogueManager implements Listener {
    private final NpcDialoguePlugin plugin;
    private final Map<UUID, DialogueSession> sessions = new HashMap<>();

    public DialogueManager(NpcDialoguePlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (DialogueSession s : new ArrayList<>(sessions.values())) s.tick();
        }, 1L, 1L);
    }

    public boolean active(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public boolean start(Player p, Npc npc) {
        if (active(p)) return false;
        LivingEntity ne = plugin.npcs.entity(npc);
        if (ne == null || ne.getWorld() != p.getWorld()) return false;
        if (p.isInsideVehicle() || p.isSleeping() || p.isDead()) return false;
        if (ne.getLocation().distanceSquared(p.getLocation()) > 15 * 15) return false;
        if (!npc.nodes.containsKey(npc.start)) {
            p.sendMessage("§cУ NPC нет стартового узла диалога.");
            return false;
        }
        DialogueSession s = new DialogueSession(plugin, p, npc, ne);
        sessions.put(p.getUniqueId(), s);
        s.begin();
        return true;
    }

    /** Мягкое завершение (с плавным возвратом) или мгновенное. */
    public void end(Player p, boolean instant) {
        DialogueSession s = sessions.get(p.getUniqueId());
        if (s == null) return;
        if (instant) s.finish();
        else s.leave();
    }

    public void endAll() {
        for (DialogueSession s : new ArrayList<>(sessions.values())) s.finish();
        sessions.clear();
    }

    void remove(Player p) {
        sessions.remove(p.getUniqueId());
    }

    private boolean click(Player p) {
        DialogueSession s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        long now = System.currentTimeMillis();
        if (now - s.lastClick >= 150) {
            s.lastClick = now;
            s.click();
        }
        return true;
    }

    // ---------- события ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (click(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (active(e.getPlayer())) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND) click(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(PlayerAnimationEvent e) {
        click(e.getPlayer());
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && active(e.getPlayer()) && plugin.getConfig().getBoolean("dialogue.sneak-exit", true)) {
            end(e.getPlayer(), false);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (active(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (active(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        end(e.getPlayer(), true);
    }

    // ---------- MiniMessage-утилиты для печати текста ----------

    /** Количество видимых символов (теги <...> не считаются). */
    static int visible(String raw) {
        int n = 0;
        for (int i = 0; i < raw.length(); ) {
            if (raw.charAt(i) == '<') {
                int e = raw.indexOf('>', i);
                if (e > 0) {
                    i = e + 1;
                    continue;
                }
            }
            n++;
            i++;
        }
        return n;
    }

    /** Префикс строки с заданным числом видимых символов; теги сохраняются целиком. */
    static String prefix(String raw, int visible) {
        StringBuilder sb = new StringBuilder();
        int vis = 0, i = 0;
        while (i < raw.length() && vis < visible) {
            char c = raw.charAt(i);
            if (c == '<') {
                int e = raw.indexOf('>', i);
                if (e > 0) {
                    sb.append(raw, i, e + 1);
                    i = e + 1;
                    continue;
                }
            }
            sb.append(c);
            vis++;
            i++;
        }
        return sb.toString();
    }
}
