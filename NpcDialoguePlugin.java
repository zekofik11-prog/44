package dev.npcdialogue;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class NpcDialoguePlugin extends JavaPlugin {
    public ModelBridge models;
    public NpcStorage storage;
    public NpcManager npcs;
    public DialogueManager dialogues;
    public ChatPrompt prompts;
    public Editor editor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        models = new ModelBridge(this);
        storage = new NpcStorage(this);
        npcs = new NpcManager(this);
        dialogues = new DialogueManager(this);
        prompts = new ChatPrompt(this);
        editor = new Editor(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(npcs, this);
        pm.registerEvents(dialogues, this);
        pm.registerEvents(prompts, this);
        pm.registerEvents(new Menu.Listener(), this);

        NpcCommand cmd = new NpcCommand(this);
        PluginCommand pc = getCommand("npc");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        storage.load();
        getServer().getScheduler().runTask(this, npcs::spawnAll);
    }

    @Override
    public void onDisable() {
        if (dialogues != null) dialogues.endAll();
        if (npcs != null) npcs.despawnAll();
        if (models != null) models.closeAll();
    }
}
