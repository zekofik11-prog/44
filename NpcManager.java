package dev.npcdialogue;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/** Спавн/удаление NPC-сущностей и запуск эмоций. Сущность — невидимый жилитель, внешний вид даёт BetterModel. */
public final class NpcManager implements Listener {
    private final NpcDialoguePlugin plugin;
    public final NamespacedKey key;
    private final Map<String, LivingEntity> live = new HashMap<>();
    private final Map<String, BukkitTask> revert = new HashMap<>();

    public NpcManager(NpcDialoguePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "npc");
    }

    public LivingEntity entity(Npc n) {
        LivingEntity e = live.get(n.id);
        return e != null && e.isValid() ? e : null;
    }

    public void spawnAll() {
        for (Npc n : plugin.storage.npcs.values()) spawn(n);
    }

    public void despawnAll() {
        for (Npc n : plugin.storage.npcs.values()) despawn(n);
    }

    public void spawn(Npc n) {
        despawn(n);
        Location l = n.location();
        if (l == null) return;
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        Villager v = l.getWorld().spawn(l, Villager.class, e -> {
            e.setAI(false);
            e.setSilent(true);
            e.setInvulnerable(true);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setInvisible(true);
            e.setCollidable(false);
            e.setGravity(false);
            e.setCanPickupItems(false);
            e.customName(MiniMessage.miniMessage().deserialize(n.name));
            e.setCustomNameVisible(n.nameVisible);
            e.getPersistentDataContainer().set(key, PersistentDataType.STRING, n.id);
            AttributeInstance sc = e.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(Math.max(0.0625, n.scale));
        });
        live.put(n.id, v);
        plugin.models.attach(v, n.model);
        if (!n.idle.isBlank()) plugin.models.animate(v, n.idle, false);
    }

    public void despawn(Npc n) {
        BukkitTask t = revert.remove(n.id);
        if (t != null) t.cancel();
        LivingEntity e = live.remove(n.id);
        if (e != null) {
            plugin.models.detach(e);
            e.remove();
        }
    }

    public void respawn(Npc n) {
        spawn(n);
    }

    /** Проиграть одноразовую анимацию-эмоцию и вернуться к idle. */
    public void playEmote(Npc n, String anim) {
        LivingEntity e = entity(n);
        if (e == null || anim == null || anim.isBlank()) return;
        plugin.models.animate(e, anim, true);
        BukkitTask old = revert.remove(n.id);
        if (old != null) old.cancel();
        if (!n.idle.isBlank() && !n.idle.equals(anim)) {
            Double len = plugin.models.animations(n.model).get(anim);
            long ticks = (long) ((len == null ? 1.5 : len) * 20) + 2;
            revert.put(n.id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
                revert.remove(n.id);
                LivingEntity c = entity(n);
                if (c != null) plugin.models.animate(c, n.idle, false);
            }, ticks));
        }
    }

    // ---------- события ----------

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en.getPersistentDataContainer().has(key, PersistentDataType.STRING) && !live.containsValue(en)) en.remove();
        }
        String w = e.getWorld().getName();
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        for (Npc n : plugin.storage.npcs.values()) {
            if (n.world.equals(w) && ((int) Math.floor(n.x) >> 4) == cx && ((int) Math.floor(n.z) >> 4) == cz) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (entity(n) == null) spawn(n);
                });
            }
        }
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            String id = en.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (id != null) {
                live.remove(id, en);
                plugin.models.detach(en);
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(key, PersistentDataType.STRING)) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        String id = e.getRightClicked().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Npc n = plugin.storage.npcs.get(id);
        Player p = e.getPlayer();
        if (n == null || plugin.dialogues.active(p)) return;
        if (p.isSneaking() && p.hasPermission("npcdialogue.admin")) plugin.editor.npc(p, n);
        else plugin.dialogues.start(p, n);
    }
}
