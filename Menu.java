package dev.npcdialogue;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Простое GUI-меню с обработчиками кликов по слотам. */
public class Menu implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    public Menu(Component title, int rows) {
        this.inv = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    public static final class Listener implements org.bukkit.event.Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (e.getInventory().getHolder() instanceof Menu m) {
                e.setCancelled(true);
                if (e.getClickedInventory() != e.getInventory()) return;
                Consumer<InventoryClickEvent> a = m.actions.get(e.getRawSlot());
                if (a != null) a.accept(e);
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
        }
    }
}
