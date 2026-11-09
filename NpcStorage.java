package dev.npcdialogue;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class NpcStorage {
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final NpcDialoguePlugin plugin;
    private final File file;
    public final Map<String, Npc> npcs = new LinkedHashMap<>();

    public NpcStorage(NpcDialoguePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
    }

    public Npc create(String id, Location l) {
        Npc n = Npc.createDefault(id, l);
        npcs.put(id, n);
        save();
        return n;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    public void load() {
        npcs.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("npcs");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            Npc n = new Npc(id);
            n.name = c.getString("name", n.name);
            n.model = c.getString("model", "");
            n.idle = c.getString("idle", "");
            n.world = c.getString("world", "world");
            n.x = c.getDouble("x");
            n.y = c.getDouble("y");
            n.z = c.getDouble("z");
            n.yaw = (float) c.getDouble("yaw");
            n.scale = c.getDouble("scale", 1.0);
            n.nameVisible = c.getBoolean("name-visible", true);
            n.camDistance = c.getDouble("camera.distance", 2.2);
            n.camSide = c.getDouble("camera.side", 0.8);
            n.camHeight = c.getDouble("camera.height", 0.0);
            n.start = c.getString("start", "start");
            ConfigurationSection ns = c.getConfigurationSection("nodes");
            if (ns != null) {
                for (String nid : ns.getKeys(false)) {
                    ConfigurationSection nc = ns.getConfigurationSection(nid);
                    if (nc == null) continue;
                    Npc.Node node = new Npc.Node();
                    node.next = nc.getString("next", "");
                    for (Map<?, ?> m : nc.getMapList("lines")) {
                        Npc.Line l = new Npc.Line(str(m.get("text")));
                        l.emote = str(m.get("emote"));
                        node.lines.add(l);
                    }
                    for (Map<?, ?> m : nc.getMapList("options")) {
                        Npc.Option o = new Npc.Option(str(m.get("text")));
                        o.next = str(m.get("next"));
                        o.emote = str(m.get("emote"));
                        o.close = Boolean.TRUE.equals(m.get("close"));
                        if (m.get("commands") instanceof List<?> li) {
                            for (Object x : li) o.commands.add(str(x));
                        }
                        node.options.add(o);
                    }
                    n.nodes.put(nid, node);
                }
            }
            npcs.put(id, n);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Npc n : npcs.values()) {
            String b = "npcs." + n.id + ".";
            y.set(b + "name", n.name);
            y.set(b + "model", n.model);
            y.set(b + "idle", n.idle);
            y.set(b + "world", n.world);
            y.set(b + "x", n.x);
            y.set(b + "y", n.y);
            y.set(b + "z", n.z);
            y.set(b + "yaw", (double) n.yaw);
            y.set(b + "scale", n.scale);
            y.set(b + "name-visible", n.nameVisible);
            y.set(b + "camera.distance", n.camDistance);
            y.set(b + "camera.side", n.camSide);
            y.set(b + "camera.height", n.camHeight);
            y.set(b + "start", n.start);
            for (Map.Entry<String, Npc.Node> e : n.nodes.entrySet()) {
                String nb = b + "nodes." + e.getKey() + ".";
                Npc.Node node = e.getValue();
                y.set(nb + "next", node.next);
                List<Map<String, Object>> lines = new ArrayList<>();
                for (Npc.Line l : node.lines) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("text", l.text);
                    m.put("emote", l.emote);
                    lines.add(m);
                }
                y.set(nb + "lines", lines);
                List<Map<String, Object>> opts = new ArrayList<>();
                for (Npc.Option o : node.options) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("text", o.text);
                    m.put("next", o.next);
                    m.put("emote", o.emote);
                    m.put("close", o.close);
                    m.put("commands", new ArrayList<>(o.commands));
                    opts.add(m);
                }
                y.set(nb + "options", opts);
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Не удалось сохранить npcs.yml: " + ex.getMessage());
        }
    }
}
