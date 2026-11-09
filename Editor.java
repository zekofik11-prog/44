package dev.npcdialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Конструктор NPC и диалогов: GUI-меню + ввод текста через чат. */
public final class Editor {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final NpcDialoguePlugin plugin;

    public Editor(NpcDialoguePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- утилиты ----------

    private static Component mm(String s) {
        return MM.deserialize(s);
    }

    private static String esc(String s) {
        String t = MM.escapeTags(s == null ? "" : s);
        return t.length() > 44 ? t.substring(0, 44) + "…" : t;
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.displayName(mm("<!italic>" + name));
        if (lore.length > 0) {
            im.lore(Arrays.stream(lore).map(s -> mm("<!italic><gray>" + s)).collect(Collectors.toList()));
        }
        i.setItemMeta(im);
        return i;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private void save() {
        plugin.storage.save();
    }

    private void ask(Player p, String prompt, java.util.function.Consumer<String> ok, Runnable back) {
        plugin.prompts.ask(p, prompt, ok, back);
    }

    /** ЛКМ +шаг, ПКМ −шаг, Shift ×5. */
    private static double step(ClickType c, double step) {
        double s = c.isShiftClick() ? step * 5 : step;
        return c.isRightClick() ? -s : s;
    }

    // ---------- универсальный список со страницами ----------

    private <T> void picker(Player p, String title, List<T> items, Function<T, ItemStack> icon,
                            BiConsumer<T, ClickType> click, Runnable back,
                            ItemStack extra, Runnable extraAct, int page) {
        Menu m = new Menu(mm(title), 6);
        int per = 45;
        int pages = Math.max(1, (items.size() + per - 1) / per);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per; i++) {
            int idx = pg * per + i;
            if (idx >= items.size()) break;
            T t = items.get(idx);
            m.set(i, icon.apply(t), e -> click.accept(t, e.getClick()));
        }
        if (pg > 0) m.set(45, item(Material.ARROW, "<yellow>← Предыдущая"), e -> picker(p, title, items, icon, click, back, extra, extraAct, pg - 1));
        if (pg < pages - 1) m.set(53, item(Material.ARROW, "<yellow>Следующая →"), e -> picker(p, title, items, icon, click, back, extra, extraAct, pg + 1));
        m.set(49, item(Material.BARRIER, "<red>Назад"), e -> back.run());
        if (extra != null) m.set(47, extra, e -> extraAct.run());
        m.open(p);
    }

    // ---------- главное меню ----------

    public void main(Player p) {
        picker(p, "<dark_gray>NPC · конструктор", new ArrayList<>(plugin.storage.npcs.values()),
                n -> item(Material.VILLAGER_SPAWN_EGG, "<yellow>" + n.id,
                        "Модель: " + (n.model.isBlank() ? "—" : n.model),
                        "Узлов диалога: " + n.nodes.size(), "", "<green>Клик — редактировать"),
                (n, c) -> npc(p, n), p::closeInventory,
                item(Material.EMERALD, "<green>+ Создать NPC здесь"), () -> createFlow(p), 0);
    }

    private void createFlow(Player p) {
        ask(p, "Введи id нового NPC (a-z, 0-9, _ -)", s -> {
            String id = s.toLowerCase();
            if (!NpcStorage.ID.matcher(id).matches() || plugin.storage.npcs.containsKey(id)) {
                p.sendMessage(mm("<red>Некорректный или занятый id."));
                main(p);
                return;
            }
            Npc n = plugin.storage.create(id, p.getLocation());
            plugin.npcs.spawn(n);
            npc(p, n);
        }, () -> main(p));
    }

    // ---------- меню NPC ----------

    public void npc(Player p, Npc n) {
        Menu m = new Menu(mm("<dark_gray>NPC: " + n.id), 5);

        m.set(10, item(Material.NAME_TAG, "<yellow>Имя", "Сейчас: <white>" + MM.escapeTags(n.name), "(MiniMessage: <gold>, <bold>…)", "", "<green>Клик — изменить"),
                e -> ask(p, "Новое имя NPC (MiniMessage)", s -> {
                    n.name = s;
                    save();
                    plugin.npcs.respawn(n);
                    npc(p, n);
                }, () -> npc(p, n)));

        m.set(11, item(Material.ARMOR_STAND, "<yellow>Модель BetterModel", "Сейчас: <white>" + (n.model.isBlank() ? "—" : n.model), "", "<green>Клик — выбрать"),
                e -> modelPicker(p, n));

        m.set(12, item(Material.NOTE_BLOCK, "<yellow>Idle-анимация", "Сейчас: <white>" + (n.idle.isBlank() ? "—" : n.idle), "Играется по кругу, когда NPC молчит", "", "<green>Клик — выбрать"),
                e -> animPicker(p, n, "Idle-анимация", a -> {
                    n.idle = a;
                    save();
                    plugin.npcs.respawn(n);
                    npc(p, n);
                }, () -> npc(p, n), "Без idle"));

        m.set(13, item(Material.SLIME_BALL, "<yellow>Масштаб / хитбокс", "Сейчас: <white>" + fmt(n.scale), "ЛКМ +0.1 · ПКМ −0.1 · Shift ×5"),
                e -> {
                    n.scale = Math.max(0.1, Math.min(10, n.scale + step(e.getClick(), 0.1)));
                    save();
                    plugin.npcs.respawn(n);
                    npc(p, n);
                });

        m.set(14, item(Material.OAK_SIGN, "<yellow>Имя над головой", "Сейчас: " + (n.nameVisible ? "<green>видно" : "<red>скрыто"), "", "<green>Клик — переключить"),
                e -> {
                    n.nameVisible = !n.nameVisible;
                    save();
                    plugin.npcs.respawn(n);
                    npc(p, n);
                });

        m.set(15, item(Material.ENDER_PEARL, "<yellow>Поставить сюда", "Позиция и поворот — как у тебя", "", "<green>Клик — переместить"),
                e -> {
                    n.setLocation(p.getLocation());
                    n.yaw = p.getLocation().getYaw() + 180f;
                    save();
                    plugin.npcs.respawn(n);
                    npc(p, n);
                });

        m.set(16, item(Material.WRITABLE_BOOK, "<gold>Диалог", "Узлов: " + n.nodes.size(), "", "<green>Клик — открыть редактор диалога"),
                e -> dialogue(p, n));

        m.set(19, item(Material.SPYGLASS, "<aqua>Камера: расстояние", "Сейчас: <white>" + fmt(n.camDistance), "ЛКМ +0.1 · ПКМ −0.1 · Shift ×5"),
                e -> {
                    n.camDistance = Math.max(0.6, Math.min(8, n.camDistance + step(e.getClick(), 0.1)));
                    save();
                    npc(p, n);
                });
        m.set(20, item(Material.COMPASS, "<aqua>Камера: сдвиг вбок", "Сейчас: <white>" + fmt(n.camSide), "Больше — NPC дальше влево, больше места для ответов", "ЛКМ +0.1 · ПКМ −0.1 · Shift ×5"),
                e -> {
                    n.camSide = Math.max(-3, Math.min(3, n.camSide + step(e.getClick(), 0.1)));
                    save();
                    npc(p, n);
                });
        m.set(21, item(Material.LADDER, "<aqua>Камера: высота", "Сейчас: <white>" + fmt(n.camHeight), "ЛКМ +0.1 · ПКМ −0.1 · Shift ×5"),
                e -> {
                    n.camHeight = Math.max(-2, Math.min(4, n.camHeight + step(e.getClick(), 0.1)));
                    save();
                    npc(p, n);
                });

        m.set(23, item(Material.LIME_DYE, "<green>Проверить диалог", "Запустит диалог прямо сейчас"),
                e -> {
                    p.closeInventory();
                    if (!plugin.dialogues.start(p, n)) p.sendMessage(mm("<red>Не удалось начать диалог (NPC далеко/не заспавнен)."));
                });
        m.set(24, item(Material.ENDER_EYE, "<yellow>Телепорт к NPC"),
                e -> {
                    var l = n.location();
                    if (l != null) p.teleport(l);
                    p.closeInventory();
                });
        m.set(25, item(Material.TNT, "<red>Удалить NPC", "<dark_red>Shift + клик для подтверждения"),
                e -> {
                    if (!e.isShiftClick()) return;
                    plugin.npcs.despawn(n);
                    plugin.storage.npcs.remove(n.id);
                    save();
                    main(p);
                });

        m.set(40, item(Material.BARRIER, "<red>Назад"), e -> main(p));
        m.open(p);
    }

    // ---------- выбор модели / анимации ----------

    private void modelPicker(Player p, Npc n) {
        List<String> names = plugin.models.modelNames();
        picker(p, "<dark_gray>Модель", names,
                s -> item(Material.ARMOR_STAND, "<yellow>" + s, "<green>Клик — выбрать"),
                (s, c) -> setModel(p, n, s), () -> npc(p, n),
                item(Material.WRITABLE_BOOK, "<aqua>Ввести имя вручную", names.isEmpty() ? "<red>В plugins/BetterModel/models нет .bbmodel" : "Если модели нет в списке"),
                () -> ask(p, "Имя модели BetterModel", s -> setModel(p, n, s), () -> modelPicker(p, n)), 0);
    }

    private void setModel(Player p, Npc n, String model) {
        n.model = model;
        n.idle = plugin.models.animations(model).containsKey("idle") ? "idle" : "";
        save();
        plugin.npcs.respawn(n);
        npc(p, n);
    }

    /** Список анимаций модели NPC; extraLabel — пункт «без анимации» (передаёт пустую строку). */
    private void animPicker(Player p, Npc n, String title, java.util.function.Consumer<String> pick, Runnable back, String extraLabel) {
        Map<String, Double> anims = plugin.models.animations(n.model);
        if (anims.isEmpty()) {
            ask(p, "В модели не найдено анимаций (или файл не читается). Введи имя анимации вручную", pick, back);
            return;
        }
        picker(p, "<dark_gray>" + title, new ArrayList<>(anims.keySet()),
                a -> item(Material.MUSIC_DISC_CAT, "<yellow>" + a, "Длина: " + fmt(anims.get(a)) + " c", "<green>Клик — выбрать"),
                (a, c) -> pick.accept(a), back,
                item(Material.RED_DYE, "<red>" + extraLabel), () -> pick.accept(""), 0);
    }

    // ---------- диалог: узлы ----------

    public void dialogue(Player p, Npc n) {
        picker(p, "<dark_gray>Диалог: " + n.id, new ArrayList<>(n.nodes.keySet()),
                id -> {
                    Npc.Node nd = n.nodes.get(id);
                    boolean start = id.equals(n.start);
                    return item(start ? Material.LIME_BANNER : Material.PAPER, (start ? "<green>★ " : "<yellow>") + id,
                            "Реплик: " + nd.lines.size() + " · Ответов: " + nd.options.size(), "",
                            "<green>ЛКМ — открыть", "ПКМ — сделать стартовым", "<red>Shift+ПКМ — удалить");
                },
                (id, c) -> {
                    if (c == ClickType.SHIFT_RIGHT) {
                        if (n.nodes.size() > 1) {
                            n.nodes.remove(id);
                            if (n.start.equals(id)) n.start = n.nodes.keySet().iterator().next();
                            save();
                        }
                        dialogue(p, n);
                    } else if (c.isRightClick()) {
                        n.start = id;
                        save();
                        dialogue(p, n);
                    } else node(p, n, id);
                },
                () -> npc(p, n),
                item(Material.EMERALD, "<green>+ Новый узел"),
                () -> ask(p, "id нового узла (a-z, 0-9, _ -)", s -> {
                    String id = s.toLowerCase();
                    if (!NpcStorage.ID.matcher(id).matches() || n.nodes.containsKey(id)) {
                        p.sendMessage(mm("<red>Некорректный или занятый id."));
                        dialogue(p, n);
                        return;
                    }
                    Npc.Node nd = new Npc.Node();
                    nd.lines.add(new Npc.Line("Новая реплика"));
                    n.nodes.put(id, nd);
                    save();
                    node(p, n, id);
                }, () -> dialogue(p, n)), 0);
    }

    public void node(Player p, Npc n, String id) {
        Npc.Node nd = n.nodes.get(id);
        if (nd == null) {
            dialogue(p, n);
            return;
        }
        Menu m = new Menu(mm("<dark_gray>Узел: " + id), 3);
        m.set(10, item(Material.BOOK, "<yellow>Реплики NPC", "Всего: " + nd.lines.size(), "Каждая реплика может запускать эмоцию", "", "<green>Клик — редактировать"),
                e -> lines(p, n, id));
        m.set(12, item(Material.OAK_BUTTON, "<yellow>Варианты ответа", "Всего: " + nd.options.size(), "Выбираются курсором в диалоге", "", "<green>Клик — редактировать"),
                e -> options(p, n, id));
        m.set(14, item(Material.ARROW, "<yellow>Дальше (если нет ответов)", "Сейчас: <white>" + (nd.next.isBlank() ? "конец диалога" : nd.next), "", "<green>Клик — выбрать узел"),
                e -> nodePicker(p, n, "Следующий узел", t -> {
                    nd.next = t;
                    save();
                    node(p, n, id);
                }, () -> node(p, n, id), "Завершить диалог"));
        m.set(22, item(Material.BARRIER, "<red>Назад"), e -> dialogue(p, n));
        m.open(p);
    }

    private void nodePicker(Player p, Npc n, String title, java.util.function.Consumer<String> pick, Runnable back, String extraLabel) {
        picker(p, "<dark_gray>" + title, new ArrayList<>(n.nodes.keySet()),
                id -> item(Material.PAPER, "<yellow>" + id, "<green>Клик — выбрать"),
                (id, c) -> pick.accept(id), back,
                item(Material.RED_DYE, "<red>" + extraLabel), () -> pick.accept(""), 0);
    }

    // ---------- реплики ----------

    private void lines(Player p, Npc n, String id) {
        Npc.Node nd = n.nodes.get(id);
        picker(p, "<dark_gray>Реплики: " + id, new ArrayList<>(nd.lines),
                l -> item(Material.PAPER, "<white>" + esc(l.text),
                        "Эмоция: <white>" + (l.emote.isBlank() ? "—" : l.emote), "",
                        "<green>ЛКМ — изменить текст", "ПКМ — эмоция", "Shift+ЛКМ — выше", "<red>Shift+ПКМ — удалить"),
                (l, c) -> {
                    int i = nd.lines.indexOf(l);
                    if (c == ClickType.SHIFT_LEFT) {
                        if (i > 0) {
                            nd.lines.remove(i);
                            nd.lines.add(i - 1, l);
                            save();
                        }
                        lines(p, n, id);
                    } else if (c == ClickType.SHIFT_RIGHT) {
                        nd.lines.remove(l);
                        save();
                        lines(p, n, id);
                    } else if (c.isRightClick()) {
                        animPicker(p, n, "Эмоция реплики", a -> {
                            l.emote = a;
                            save();
                            lines(p, n, id);
                        }, () -> lines(p, n, id), "Без эмоции");
                    } else {
                        ask(p, "Текст реплики (MiniMessage, %player% — ник игрока)", s -> {
                            l.text = s;
                            save();
                            lines(p, n, id);
                        }, () -> lines(p, n, id));
                    }
                },
                () -> node(p, n, id),
                item(Material.EMERALD, "<green>+ Добавить реплику"),
                () -> ask(p, "Текст новой реплики", s -> {
                    nd.lines.add(new Npc.Line(s));
                    save();
                    lines(p, n, id);
                }, () -> lines(p, n, id)), 0);
    }

    // ---------- варианты ответа ----------

    private void options(Player p, Npc n, String id) {
        Npc.Node nd = n.nodes.get(id);
        picker(p, "<dark_gray>Ответы: " + id, new ArrayList<>(nd.options),
                o -> item(Material.OAK_BUTTON, "<white>" + esc(o.text),
                        "Дальше: <white>" + (o.close || o.next.isBlank() ? "конец" : o.next),
                        "Эмоция: <white>" + (o.emote.isBlank() ? "—" : o.emote),
                        "Команд: " + o.commands.size(), "",
                        "<green>ЛКМ — настроить", "Shift+ЛКМ — выше", "<red>Shift+ПКМ — удалить"),
                (o, c) -> {
                    int i = nd.options.indexOf(o);
                    if (c == ClickType.SHIFT_LEFT) {
                        if (i > 0) {
                            nd.options.remove(i);
                            nd.options.add(i - 1, o);
                            save();
                        }
                        options(p, n, id);
                    } else if (c == ClickType.SHIFT_RIGHT) {
                        nd.options.remove(o);
                        save();
                        options(p, n, id);
                    } else option(p, n, id, o);
                },
                () -> node(p, n, id),
                item(Material.EMERALD, "<green>+ Добавить ответ"),
                () -> ask(p, "Текст нового варианта ответа", s -> {
                    Npc.Option o = new Npc.Option(s);
                    o.close = true;
                    nd.options.add(o);
                    save();
                    option(p, n, id, o);
                }, () -> options(p, n, id)), 0);
    }

    private void option(Player p, Npc n, String id, Npc.Option o) {
        Menu m = new Menu(mm("<dark_gray>Ответ: " + esc(o.text)), 3);
        m.set(10, item(Material.NAME_TAG, "<yellow>Текст ответа", "<white>" + esc(o.text), "", "<green>Клик — изменить"),
                e -> ask(p, "Текст ответа (MiniMessage)", s -> {
                    o.text = s;
                    save();
                    option(p, n, id, o);
                }, () -> option(p, n, id, o)));
        m.set(11, item(Material.ARROW, "<yellow>Куда ведёт", "Сейчас: <white>" + (o.next.isBlank() ? "конец диалога" : o.next), "", "<green>Клик — выбрать узел"),
                e -> nodePicker(p, n, "Куда ведёт ответ", t -> {
                    o.next = t;
                    o.close = t.isBlank();
                    save();
                    option(p, n, id, o);
                }, () -> option(p, n, id, o), "Завершить диалог"));
        m.set(12, item(Material.NOTE_BLOCK, "<yellow>Эмоция при выборе", "Сейчас: <white>" + (o.emote.isBlank() ? "—" : o.emote), "", "<green>Клик — выбрать анимацию"),
                e -> animPicker(p, n, "Эмоция ответа", a -> {
                    o.emote = a;
                    save();
                    option(p, n, id, o);
                }, () -> option(p, n, id, o), "Без эмоции"));
        m.set(13, item(Material.IRON_DOOR, "<yellow>Закрывать диалог", "Сейчас: " + (o.close ? "<green>да" : "<red>нет"), "", "<green>Клик — переключить"),
                e -> {
                    o.close = !o.close;
                    save();
                    option(p, n, id, o);
                });
        List<String> cl = new ArrayList<>();
        for (String c : o.commands) cl.add("<white>" + esc(c));
        cl.add("");
        cl.add("<green>ЛКМ — добавить команду");
        cl.add("<red>ПКМ — убрать последнюю");
        cl.add("Префикс <white>player:</white> — от имени игрока,");
        cl.add("по умолчанию — от консоли. %player% — ник.");
        m.set(14, item(Material.COMMAND_BLOCK, "<yellow>Команды при выборе", cl.toArray(new String[0])),
                e -> {
                    if (e.getClick().isRightClick()) {
                        if (!o.commands.isEmpty()) o.commands.remove(o.commands.size() - 1);
                        save();
                        option(p, n, id, o);
                    } else {
                        ask(p, "Команда без «/» (например: give %player% diamond 1 или player:spawn)", s -> {
                            o.commands.add(s);
                            save();
                            option(p, n, id, o);
                        }, () -> option(p, n, id, o));
                    }
                });
        m.set(22, item(Material.BARRIER, "<red>Назад"), e -> options(p, n, id));
        m.open(p);
    }
}
