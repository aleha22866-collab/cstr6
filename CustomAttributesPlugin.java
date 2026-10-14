package ru.example.customattributes;

import org.bukkit.plugin.java.JavaPlugin;

public class CustomAttributesPlugin extends JavaPlugin {

    private AttributeListener attributeListener;
    private AbilityTicker ticker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        AttributeManager mgr = new AttributeManager(this);
        AbilityManager abilities = new AbilityManager(this);

        attributeListener = new AttributeListener(this, mgr);
        AbilityListener abilityListener = new AbilityListener(this, abilities);
        ticker = new AbilityTicker(this, abilities, abilityListener);

        getServer().getPluginManager().registerEvents(attributeListener, this);
        getServer().getPluginManager().registerEvents(abilityListener, this);

        AttrCommand cmd = new AttrCommand(this, mgr, abilities);
        getCommand("customattr").setExecutor(cmd);
        getCommand("customattr").setTabCompleter(cmd);

        getServer().getScheduler().runTaskTimer(this, attributeListener::cleanupModifiers, 40L, 40L);
        getServer().getScheduler().runTaskTimer(this, ticker, 20L, 5L);
    }

    @Override
    public void onDisable() {
        if (attributeListener != null) attributeListener.removeAllModifiers();
        if (ticker != null) ticker.shutdown();
    }
}
