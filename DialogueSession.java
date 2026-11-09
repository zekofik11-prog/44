package dev.npcdialogue;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Одна сессия диалога: камера (игрок «сидит» на невидимом стойке-камере), кино-полосы,
 * текст NPC и варианты ответа, выбираемые прицелом.
 */
final class DialogueSession {
    enum State { MOUNTING, ENTERING, TYPING, WAIT, CHOOSING, LEAVING }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CINEMA_FRAMES = 6;
    private static final ItemStack[] OVERLAYS = new ItemStack[CINEMA_FRAMES + 1];

    final NpcDialoguePlugin plugin;
    final Player p;
    final Npc npc;
    final LivingEntity npcEntity;
    final World world;

    // настройки
    private final int enterTicks, leaveTicks, cps;
    private final boolean sounds, cinemaOn;
    private final double panelD, scale, panelX, msgY, optY0, charW, lineH, spacing;
    private final int msgWidth;

    // исходное состояние
    private final Location origLoc;
    private final Vector origEye;
    private final float npcOrigYaw;
    private final boolean wasInvulnerable;

    // камера
    private ArmorStand stand;
    private Vector standOffset = new Vector();
    private Vector camEye, fwd, right;
    private float camYaw;
    private Vector moveFrom, moveTo;
    private float yawFrom, yawTo, pitchFrom, pitchTo;
    private int moveT, moveDur = 1;

    // диалог
    State state = State.MOUNTING;
    boolean finalizing;
    long lastClick;
    private int tick;
    private String nodeId = "";
    private Npc.Node node;
    private int lineIdx;
    private int shown, total;
    private String lineRaw = "";
    private TextDisplay msg;
    private final List<TextDisplay> optDisplays = new ArrayList<>();
    private List<Npc.Option> opts = List.of();
    private int hover = -1;
    private int cinema = 0;
    private int lastSentCinema = -1;

    DialogueSession(NpcDialoguePlugin plugin, Player p, Npc npc, LivingEntity ne) {
        this.plugin = plugin;
        this.p = p;
        this.npc = npc;
        this.npcEntity = ne;
        this.world = ne.getWorld();
        var c = plugin.getConfig();
        enterTicks = Math.max(1, c.getInt("dialogue.transition-ticks", 28));
        leaveTicks = Math.max(1, c.getInt("dialogue.leave-ticks", 18));
        cps = Math.max(1, c.getInt("dialogue.typing-chars-per-tick", 1));
        sounds = c.getBoolean("dialogue.sounds", true);
        cinemaOn = c.getBoolean("cinema.enabled", true);
        panelD = c.getDouble("panel.distance", 1.0);
        scale = c.getDouble("panel.text-scale", 0.3);
        panelX = c.getDouble("panel.x", 0.62);
        msgY = c.getDouble("panel.message-y", 0.30);
        optY0 = c.getDouble("panel.options-y", 0.0);
        msgWidth = c.getInt("panel.message-width-px", 140);
        charW = 0.15 * scale;
        lineH = 0.25 * scale;
        spacing = lineH * 1.9;

        origLoc = p.getLocation().clone();
        origEye = origLoc.toVector().add(new Vector(0, p.getEyeHeight(), 0));
        npcOrigYaw = ne.getLocation().getYaw();
        wasInvulnerable = p.isInvulnerable();

        // геометрия камеры: перед NPC, со стороны игрока, смещена вправо — NPC уходит влево, справа место для ответов
        Vector base = ne.getLocation().toVector();
        Vector toP = origLoc.toVector().subtract(base).setY(0);
        if (toP.lengthSquared() < 0.01) toP = new Vector(0, 0, 1);
        toP.normalize();
        Vector dir = toP.clone().multiply(-1);
        fwd = dir;
        right = new Vector(-dir.getZ(), 0, dir.getX());
        Vector focus = base.clone().add(new Vector(0, 1.5 * npc.scale + npc.camHeight, 0));
        Vector eye = focus.clone().add(toP.clone().multiply(npc.camDistance)).add(right.clone().multiply(npc.camSide));
        Vector rd = eye.clone().subtract(focus);
        double dist = rd.length();
        rd.normalize();
        RayTraceResult hit = world.rayTraceBlocks(focus.toLocation(world), rd, dist, FluidCollisionMode.NEVER, true);
        if (hit != null) {
            double d = Math.max(0.7, hit.getHitPosition().distance(focus) - 0.3);
            eye = focus.clone().add(rd.multiply(d));
        }
        camEye = eye;
        camYaw = yawOf(dir);

        // NPC поворачивается к игроку
        float ny = yawOf(toP);
        ne.setRotation(ny, 0f);
        ne.setBodyYaw(ny);
    }

    // ---------- жизненный цикл ----------

    void begin() {
        stand = world.spawn(origLoc, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setSmall(true);
            a.setGravity(false);
            a.setSilent(true);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.setBasePlate(false);
        });
        p.setInvulnerable(true);
        stand.addPassenger(p);
    }

    void tick() {
        tick++;
        if (finalizing) return;
        if (!p.isOnline() || !p.isValid() || npcEntity == null || !npcEntity.isValid() || p.getWorld() != world) {
            finish();
            return;
        }
        if (state != State.LEAVING && p.getVehicle() != stand) stand.addPassenger(p);

        if (state == State.LEAVING) cinema = Math.max(0, cinema - 1);
        else cinema = Math.min(CINEMA_FRAMES, cinema + 1);
        sendCinema();

        switch (state) {
            case MOUNTING -> {
                if (tick >= 3) {
                    standOffset = p.getEyeLocation().toVector().subtract(stand.getLocation().toVector());
                    beginMove(camEye, camYaw, 0f, enterTicks);
                    state = State.ENTERING;
                }
            }
            case ENTERING -> {
                stepMove();
                if (moveT >= moveDur) {
                    msg = spawnText(planePos(panelX, msgY), Component.empty(), msgWidth, 110);
                    showNode(npc.start);
                }
            }
            case TYPING -> {
                shown = Math.min(total, shown + cps);
                if (sounds && tick % 2 == 0) p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 0.25f, 1.7f);
                renderMsg(false);
                if (shown >= total) onTyped();
            }
            case CHOOSING -> updateHover();
            case LEAVING -> {
                stepMove();
                if (moveT >= moveDur && cinema == 0) finish();
            }
            default -> {
            }
        }
    }

    void leave() {
        if (state == State.LEAVING || finalizing) return;
        state = State.LEAVING;
        removeDisplays();
        npcEntity.setRotation(npcOrigYaw, 0f);
        npcEntity.setBodyYaw(npcOrigYaw);
        if (standOffset.lengthSquared() == 0) { // ещё не успели посадить — сразу завершаем
            finish();
            return;
        }
        beginMove(origEye, origLoc.getYaw(), origLoc.getPitch(), leaveTicks);
    }

    void finish() {
        if (finalizing) return;
        finalizing = true;
        removeDisplays();
        if (stand != null) {
            if (p.isOnline()) p.leaveVehicle();
            stand.remove();
        }
        if (p.isOnline()) {
            p.teleport(origLoc);
            p.setInvulnerable(wasInvulnerable);
            ItemStack real = p.getInventory().getHelmet();
            p.sendEquipmentChange(p, EquipmentSlot.HEAD, real == null ? new ItemStack(Material.AIR) : real);
        }
        if (npcEntity != null && npcEntity.isValid()) {
            npcEntity.setRotation(npcOrigYaw, 0f);
            npcEntity.setBodyYaw(npcOrigYaw);
        }
        plugin.dialogues.remove(p);
    }

    // ---------- движение камеры ----------

    private void beginMove(Vector to, float yawTarget, float pitchTarget, int dur) {
        moveFrom = p.getEyeLocation().toVector();
        moveTo = to.clone();
        yawFrom = p.getLocation().getYaw();
        pitchFrom = p.getLocation().getPitch();
        yawTo = yawTarget;
        pitchTo = pitchTarget;
        moveT = 0;
        moveDur = Math.max(1, dur);
    }

    private void stepMove() {
        if (moveT >= moveDur) return;
        moveT++;
        double t = Math.min(1.0, (double) moveT / moveDur);
        double e = t * t * (3 - 2 * t);
        Vector eye = moveFrom.clone().multiply(1 - e).add(moveTo.clone().multiply(e));
        Vector pos = eye.subtract(standOffset);
        Location sl = stand.getLocation();
        sl.setX(pos.getX());
        sl.setY(pos.getY());
        sl.setZ(pos.getZ());
        stand.teleport(sl, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        float dy = ((yawTo - yawFrom + 540f) % 360f) - 180f;
        p.setRotation(yawFrom + (float) (dy * e), (float) (pitchFrom + (pitchTo - pitchFrom) * e));
    }

    // ---------- кино-полосы ----------

    private void sendCinema() {
        if (!cinemaOn) return;
        if (cinema == lastSentCinema && !(cinema > 0 && tick % 20 == 0)) return;
        lastSentCinema = cinema;
        ItemStack it;
        if (cinema <= 0) {
            ItemStack real = p.getInventory().getHelmet();
            it = real == null ? new ItemStack(Material.AIR) : real;
        } else {
            it = overlay(cinema);
        }
        p.sendEquipmentChange(p, EquipmentSlot.HEAD, it);
    }

    private static ItemStack overlay(int frame) {
        if (OVERLAYS[frame] == null) {
            ItemStack it = new ItemStack(Material.CARVED_PUMPKIN);
            it.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD)
                    .cameraOverlay(Key.key("npcdialogue:misc/cinema_" + frame))
                    .build());
            OVERLAYS[frame] = it;
        }
        return OVERLAYS[frame];
    }

    // ---------- диалог ----------

    private String fmt(String s) {
        return s == null ? "" : s.replace("%player%", p.getName());
    }

    private void showNode(String id) {
        Npc.Node n = npc.nodes.get(id);
        if (n == null) {
            leave();
            return;
        }
        nodeId = id;
        node = n;
        lineIdx = -1;
        clearOptions();
        nextLine();
    }

    private void nextLine() {
        lineIdx++;
        if (node.lines.isEmpty()) {
            lineIdx = 0;
            lineRaw = "";
            shown = total = 0;
            onTyped();
            return;
        }
        Npc.Line l = node.lines.get(lineIdx);
        lineRaw = fmt(l.text);
        shown = 0;
        total = DialogueManager.visible(lineRaw);
        state = State.TYPING;
        plugin.npcs.playEmote(npc, l.emote);
        renderMsg(false);
    }

    private void onTyped() {
        boolean last = lineIdx >= node.lines.size() - 1;
        if (last && !node.options.isEmpty()) {
            state = State.CHOOSING;
            spawnOptions();
            renderMsg(false);
        } else {
            state = State.WAIT;
            renderMsg(true);
        }
    }

    private void renderMsg(boolean hint) {
        if (msg == null) return;
        Component c = MM.deserialize(fmt(npc.name))
                .append(Component.newline())
                .append(MM.deserialize("<white>" + DialogueManager.prefix(lineRaw, shown)));
        if (hint) c = c.append(Component.newline()).append(MM.deserialize("<gray>▼"));
        msg.text(c);
    }

    void click() {
        switch (state) {
            case TYPING -> {
                shown = total;
                renderMsg(false);
                onTyped();
            }
            case WAIT -> {
                boolean last = lineIdx >= node.lines.size() - 1;
                if (!last) nextLine();
                else if (!node.next.isBlank() && npc.nodes.containsKey(node.next)) showNode(node.next);
                else leave();
            }
            case CHOOSING -> {
                if (hover >= 0 && hover < opts.size()) choose(opts.get(hover));
            }
            default -> {
            }
        }
    }

    private void choose(Npc.Option o) {
        if (sounds) p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.6f, 1.2f);
        for (String raw : o.commands) {
            String cmd = fmt(raw).trim();
            boolean asPlayer = false;
            if (cmd.regionMatches(true, 0, "player:", 0, 7)) {
                asPlayer = true;
                cmd = cmd.substring(7).trim();
            } else if (cmd.regionMatches(true, 0, "console:", 0, 8)) {
                cmd = cmd.substring(8).trim();
            }
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (cmd.isEmpty()) continue;
            if (asPlayer) p.performCommand(cmd);
            else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
        plugin.npcs.playEmote(npc, o.emote);
        if (finalizing) return;
        if (o.close || o.next.isBlank() || !npc.nodes.containsKey(o.next)) leave();
        else showNode(o.next);
    }

    // ---------- варианты ответа ----------

    private void spawnOptions() {
        clearOptions();
        opts = node.options;
        for (int i = 0; i < opts.size(); i++) {
            optDisplays.add(spawnText(planePos(panelX, optY0 - i * spacing), optionText(i, false), 400, 70));
        }
        hover = -1;
    }

    private Component optionText(int i, boolean hovered) {
        String raw = fmt(opts.get(i).text);
        return MM.deserialize(hovered ? "<yellow>▶ <white>" + raw + " <yellow>◀" : "<gray>" + raw);
    }

    private void updateHover() {
        Location eyeL = p.getEyeLocation();
        Vector eye = eyeL.toVector();
        Vector look = eyeL.getDirection();
        double lf = look.dot(fwd);
        int h = -1;
        if (lf > 0.15) {
            double t = (panelD - eye.clone().subtract(camEye).dot(fwd)) / lf;
            Vector pt = eye.clone().add(look.clone().multiply(t)).subtract(camEye);
            double x = pt.dot(right), y = pt.getY();
            for (int i = 0; i < opts.size(); i++) {
                double oy = optY0 - i * spacing;
                double hw = DialogueManager.visible(fmt(opts.get(i).text)) * charW / 2 + 0.07 + charW * 3;
                double hh = lineH / 2 + 0.03;
                if (Math.abs(x - panelX) <= hw && Math.abs(y - oy) <= hh) {
                    h = i;
                    break;
                }
            }
        }
        if (h != hover) {
            if (hover >= 0 && hover < optDisplays.size()) setOpt(hover, false);
            if (h >= 0) {
                setOpt(h, true);
                if (sounds) p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 0.4f, 1.9f);
            }
            hover = h;
        }
    }

    private void setOpt(int i, boolean hovered) {
        TextDisplay t = optDisplays.get(i);
        t.text(optionText(i, hovered));
        t.setBackgroundColor(Color.fromARGB(hovered ? 150 : 70, 0, 0, 0));
    }

    private void clearOptions() {
        for (TextDisplay t : optDisplays) t.remove();
        optDisplays.clear();
        opts = List.of();
        hover = -1;
    }

    private void removeDisplays() {
        clearOptions();
        if (msg != null) {
            msg.remove();
            msg = null;
        }
    }

    // ---------- вспомогательное ----------

    private Vector planePos(double x, double y) {
        return camEye.clone().add(fwd.clone().multiply(panelD)).add(right.clone().multiply(x)).add(new Vector(0, y, 0));
    }

    private TextDisplay spawnText(Vector pos, Component text, int lineWidth, int bgAlpha) {
        Location l = pos.toLocation(world);
        float s = (float) scale;
        TextDisplay t = world.spawn(l, TextDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(text);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setSeeThrough(true);
            d.setShadowed(true);
            d.setLineWidth(lineWidth);
            d.setBackgroundColor(Color.fromARGB(bgAlpha, 0, 0, 0));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(0.2f);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f()));
        });
        p.showEntity(plugin, t);
        return t;
    }

    static float yawOf(Vector v) {
        return (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
    }
}
