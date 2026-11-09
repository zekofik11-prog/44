package dev.npcdialogue;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Данные одного NPC + его диалога. */
public final class Npc {
    public final String id;
    public String name;
    public String model = "";
    public String idle = "";
    public String world = "world";
    public double x, y, z;
    public float yaw;
    public double scale = 1.0;
    public boolean nameVisible = true;
    public double camDistance = 2.2, camSide = 0.8, camHeight = 0.0;
    public String start = "start";
    public final Map<String, Node> nodes = new LinkedHashMap<>();

    public Npc(String id) {
        this.id = id;
        this.name = "<yellow>" + id;
    }

    public Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, 0);
    }

    public void setLocation(Location l) {
        world = l.getWorld().getName();
        x = l.getX();
        y = l.getY();
        z = l.getZ();
        yaw = l.getYaw();
    }

    public static Npc createDefault(String id, Location l) {
        Npc n = new Npc(id);
        n.setLocation(l);
        Node s = new Node();
        s.lines.add(new Line("Привет, %player%!"));
        Option o = new Option("Пока");
        o.close = true;
        s.options.add(o);
        n.nodes.put("start", s);
        return n;
    }

    public static final class Line {
        public String text;
        public String emote = "";

        public Line(String text) {
            this.text = text;
        }
    }

    public static final class Option {
        public String text;
        public String next = "";
        public String emote = "";
        public boolean close = false;
        public final List<String> commands = new ArrayList<>();

        public Option(String text) {
            this.text = text;
        }
    }

    public static final class Node {
        public final List<Line> lines = new ArrayList<>();
        public final List<Option> options = new ArrayList<>();
        public String next = ""; // если вариантов ответа нет
    }
}
