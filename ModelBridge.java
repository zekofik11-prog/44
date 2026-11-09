package dev.npcdialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Entity;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Вся работа с BetterModel изолирована здесь (через рефлексию — плагин собирается без jar BetterModel).
 * Списки моделей и анимаций читаются прямо из .bbmodel в plugins/BetterModel/models.
 * Если в твоей версии BetterModel API отличается — правятся только методы attach/animate/detach.
 */
public final class ModelBridge {
    private final NpcDialoguePlugin plugin;
    private final Map<UUID, Object> trackers = new HashMap<>();
    private final Map<String, Info> cache = new HashMap<>();

    private record Info(long modified, Map<String, Double> anims) {}

    public ModelBridge(NpcDialoguePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- файлы моделей ----------

    private Path modelsDir() {
        return plugin.getDataFolder().getParentFile().toPath().resolve("BetterModel").resolve("models");
    }

    public List<String> modelNames() {
        Path d = modelsDir();
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.walk(d)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".bbmodel"))
                    .map(p -> {
                        String f = p.getFileName().toString();
                        return f.substring(0, f.length() - ".bbmodel".length());
                    })
                    .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path modelFile(String name) {
        Path d = modelsDir();
        if (!Files.isDirectory(d)) return null;
        try (Stream<Path> s = Files.walk(d)) {
            return s.filter(p -> p.getFileName().toString().equalsIgnoreCase(name + ".bbmodel"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Имя анимации -> длина в секундах (из .bbmodel). */
    public Map<String, Double> animations(String model) {
        if (model == null || model.isBlank()) return Map.of();
        Path f = modelFile(model);
        if (f == null) return Map.of();
        try {
            long mod = Files.getLastModifiedTime(f).toMillis();
            Info c = cache.get(model);
            if (c != null && c.modified == mod) return c.anims;
            Map<String, Double> res = new LinkedHashMap<>();
            try (Reader r = Files.newBufferedReader(f)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                JsonArray a = o.has("animations") ? o.getAsJsonArray("animations") : new JsonArray();
                for (JsonElement el : a) {
                    JsonObject an = el.getAsJsonObject();
                    if (!an.has("name")) continue;
                    res.put(an.get("name").getAsString(), an.has("length") ? an.get("length").getAsDouble() : 1.0);
                }
            }
            cache.put(model, new Info(mod, res));
            return res;
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось прочитать анимации модели " + model + ": " + e.getMessage());
            return Map.of();
        }
    }

    // ---------- BetterModel (рефлексия) ----------

    public boolean attach(Entity e, String model) {
        detach(e);
        if (model == null || model.isBlank()) return false;
        try {
            Object renderer = renderer(model);
            if (renderer == null) renderer = renderer(model.toLowerCase(Locale.ROOT));
            if (renderer == null) {
                plugin.getLogger().warning("BetterModel: модель '" + model + "' не найдена (/bettermodel reload?)");
                return false;
            }
            Object tracker = createTracker(renderer, e);
            if (tracker == null) return false;
            trackers.put(e.getUniqueId(), tracker);
            return true;
        } catch (Throwable ex) {
            plugin.getLogger().warning("BetterModel attach: " + root(ex));
            return false;
        }
    }

    private Object renderer(String name) throws Exception {
        Class<?> c = Class.forName("kr.toxicity.model.api.BetterModel");
        for (String m : new String[]{"modelOrNull", "model", "renderer"}) {
            try {
                Method md = c.getMethod(m, String.class);
                Object r = md.invoke(null, name);
                if (r instanceof Optional<?> o) r = o.orElse(null);
                if (r != null) return r;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private Object createTracker(Object renderer, Entity e) {
        Throwable last = null;
        for (Method m : renderer.getClass().getMethods()) {
            if (!m.getName().equals("create") || m.getParameterCount() != 1) continue;
            Class<?> pt = m.getParameterTypes()[0];
            try {
                m.setAccessible(true);
                if (pt.isInstance(e)) return m.invoke(renderer, e);
                Object ad = adapt(e);
                if (ad != null && pt.isInstance(ad)) return m.invoke(renderer, ad);
            } catch (Throwable ex) {
                last = ex;
            }
        }
        plugin.getLogger().warning("BetterModel: не удалось вызвать ModelRenderer#create" + (last != null ? ": " + root(last) : ""));
        return null;
    }

    private Object adapt(Entity e) {
        try {
            Class<?> c = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            for (Method m : c.getMethods()) {
                if (m.getName().equals("adapt") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(e)) {
                    return m.invoke(null, e);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object modifier(boolean once) {
        try {
            Class<?> c = Class.forName("kr.toxicity.model.api.animation.AnimationModifier");
            Field f = c.getField(once ? "DEFAULT_WITH_PLAY_ONCE" : "DEFAULT");
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public void animate(Entity e, String anim, boolean once) {
        Object t = trackers.get(e.getUniqueId());
        if (t == null || anim == null || anim.isBlank()) return;
        try {
            Object mod = modifier(once);
            for (Method m : t.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("animate") && p.length == 2 && p[0] == String.class && mod != null && p[1].isInstance(mod)) {
                    m.setAccessible(true);
                    m.invoke(t, anim, mod);
                    return;
                }
            }
            for (Method m : t.getClass().getMethods()) {
                if (m.getName().equals("animate") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    m.invoke(t, anim);
                    return;
                }
            }
            plugin.getLogger().warning("BetterModel: у трекера нет метода animate(String[, modifier])");
        } catch (Throwable ex) {
            plugin.getLogger().warning("BetterModel animate '" + anim + "': " + root(ex));
        }
    }

    public void detach(Entity e) {
        Object t = trackers.remove(e.getUniqueId());
        if (t == null) return;
        try {
            Method m = t.getClass().getMethod("close");
            m.setAccessible(true);
            m.invoke(t);
        } catch (Throwable ignored) {
        }
    }

    public void closeAll() {
        for (Object t : new ArrayList<>(trackers.values())) {
            try {
                Method m = t.getClass().getMethod("close");
                m.setAccessible(true);
                m.invoke(t);
            } catch (Throwable ignored) {
            }
        }
        trackers.clear();
    }

    private static String root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.toString();
    }
}
