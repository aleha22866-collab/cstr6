package ru.example.customattributes;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Периодическая задача:
 *  1) определяет способности у предметов, которые взял другой игрок;
 *  2) выдаёт эффекты и модификаторы брони по состоянию игрока.
 */
public class AbilityTicker implements Runnable {

    private static final int EFFECT_TICKS = 100;
    private static final int REFRESH_BELOW = 10;

    private static final Set<PotionEffectType> NEGATIVE = Set.of(
            PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.SLOWNESS,
            PotionEffectType.WEAKNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.BLINDNESS,
            PotionEffectType.NAUSEA, PotionEffectType.HUNGER, PotionEffectType.LEVITATION,
            PotionEffectType.DARKNESS, PotionEffectType.UNLUCK);

    private final CustomAttributesPlugin plugin;
    private final AbilityManager abilities;
    private final AbilityListener listener;
    private final NamespacedKey waterKey;
    private final NamespacedKey lowKey;
    private final NamespacedKey titanKey;
    private final NamespacedKey lifeKey;
    private final Map<UUID, Set<PotionEffectType>> applied = new HashMap<>();
    private final Map<UUID, Long> secondWindCooldown = new HashMap<>();
    private int tick = 0;

    public AbilityTicker(CustomAttributesPlugin plugin, AbilityManager abilities, AbilityListener listener) {
        this.plugin = plugin;
        this.abilities = abilities;
        this.listener = listener;
        this.waterKey = new NamespacedKey(plugin, "ability_water_armor");
        this.lowKey = new NamespacedKey(plugin, "ability_low_hp_armor");
        this.titanKey = new NamespacedKey(plugin, "ability_titan_knockback");
        this.lifeKey = new NamespacedKey(plugin, "ability_life_gift");
    }

    private double d(String key, double def) {
        return plugin.getConfig().getDouble("ability-settings." + key, def);
    }

    @Override
    public void run() {
        tick++;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.isDead()) continue;
            if (tick % 2 == 0) scan(p);
            apply(p);
        }
        if (tick % 200 == 0) {
            applied.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        }
    }

    /* ---------- определение способностей при получении предмета ---------- */

    private void scan(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || !it.hasItemMeta()) continue;
            if (!abilities.needsRoll(it, p.getUniqueId())) {
                // предмет уже готов: при необходимости обновляем оформление (сортировка по цвету и т.д.)
                if (abilities.migrateLore(it)) inv.setItem(i, it);
                continue;
            }

            List<Ability> result = abilities.roll(it);
            inv.setItem(i, it);

            String msg;
            if (result.isEmpty()) {
                msg = plugin.getConfig().getString("messages.rolled-none",
                        "&7Предмет не пробудил ни одной способности");
            } else {
                StringBuilder sb = new StringBuilder();
                for (Ability a : result) {
                    if (sb.length() > 0) sb.append("&7, ");
                    sb.append(abilities.name(a));
                }
                msg = plugin.getConfig().getString("messages.rolled",
                        "&6Предмет пробудил способности: &f%abilities%").replace("%abilities%", sb.toString());
            }
            p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
        }
    }

    /* ---------- постоянные эффекты ---------- */

    private void apply(Player p) {
        Set<Ability> set = abilities.getAbilities(p.getInventory().getItemInOffHand());
        Map<PotionEffectType, Integer> want = new HashMap<>();

        if (!set.isEmpty()) {
            boolean burning = p.getFireTicks() > 0;

            if (set.contains(Ability.CALM_REGEN) && !burning) want.merge(PotionEffectType.REGENERATION, 0, Math::max);
            if (set.contains(Ability.CALM_STRENGTH) && !burning) want.merge(PotionEffectType.STRENGTH, 0, Math::max);

            if (set.contains(Ability.ADVERSITY) && hasNegative(p)) {
                want.merge(PotionEffectType.RESISTANCE, 0, Math::max);
                want.merge(PotionEffectType.REGENERATION, 0, Math::max);
            }

            if (set.contains(Ability.ELYTRA_REGEN) && p.isGliding()) {
                want.merge(PotionEffectType.REGENERATION, 1, Math::max);
            }

            if (set.contains(Ability.GILLS)) {
                want.merge(PotionEffectType.WATER_BREATHING, 0, Math::max);
                if (p.isInWater()) want.merge(PotionEffectType.DOLPHINS_GRACE, 0, Math::max);
            }

            if (set.contains(Ability.WIND_SPIRIT)) {
                want.merge(PotionEffectType.SPEED, 1, Math::max);
                want.merge(PotionEffectType.JUMP_BOOST, 1, Math::max);
            }

            if (set.contains(Ability.DRAGON_WINGS) && p.isGliding()) {
                want.merge(PotionEffectType.RESISTANCE, 1, Math::max);
                want.merge(PotionEffectType.STRENGTH, 1, Math::max);
            }

            if (set.contains(Ability.VOLCANO_HEART)) {
                want.merge(PotionEffectType.FIRE_RESISTANCE, 0, Math::max);
            }

            if (set.contains(Ability.HAND_POWER)) {
                want.merge(PotionEffectType.STRENGTH, 0, Math::max);
                want.merge(PotionEffectType.SPEED, 0, Math::max);
            }
        }
        applyEffects(p, want);
        secondWind(p, set);

        setModifier(p, Attribute.ARMOR, waterKey,
                set.contains(Ability.WATER_ARMOR) && p.isInWater(),
                d("water-armor", 1));
        setModifier(p, Attribute.ARMOR, lowKey,
                set.contains(Ability.LAST_STAND) && p.getHealth() < d("last-stand-hearts", 5) * 2.0,
                d("last-stand-armor", 2));

        setModifier(p, Attribute.KNOCKBACK_RESISTANCE, titanKey, set.contains(Ability.TITAN_SKIN), 1.0);
        setModifier(p, Attribute.MAX_HEALTH, lifeKey, set.contains(Ability.LIFE_GIFT),
                d("life-gift-hearts", 4) * 2.0);

        if (set.contains(Ability.PURITY)) {
            for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) {
                if (NEGATIVE.contains(pe.getType())) p.removePotionEffect(pe.getType());
            }
        }

        if (listener.isElytraBlocked(p) && p.isGliding()) {
            p.setGliding(false);
        }
    }

    /** 27) Второе дыхание: при низком здоровье Регенерация III и золотые сердца, есть откат. */
    private void secondWind(Player p, Set<Ability> set) {
        if (!set.contains(Ability.SECOND_WIND)) return;
        if (p.getHealth() >= d("second-wind-hearts", 3) * 2.0) return;
        long now = System.currentTimeMillis();
        Long until = secondWindCooldown.get(p.getUniqueId());
        if (until != null && now < until) return;
        secondWindCooldown.put(p.getUniqueId(), now + (long) (d("second-wind-cooldown", 60) * 1000));

        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                (int) Math.round(d("second-wind-seconds", 4) * 20), (int) d("second-wind-amplifier", 2)));

        double max = plugin.getConfig().getDouble("golden-heart.max-absorption", 20.0);
        AttributeInstance inst = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (inst != null) {
            NamespacedKey capKey = new NamespacedKey(plugin, "golden_heart_cap");
            if (inst.getModifier(capKey) == null) {
                inst.addTransientModifier(new AttributeModifier(capKey, max, AttributeModifier.Operation.ADD_NUMBER));
            }
            p.setAbsorptionAmount(Math.min(p.getAbsorptionAmount()
                    + d("second-wind-absorb-hearts", 2) * 2.0, max));
        }
        String msg = plugin.getConfig().getString("messages.second-wind", "&dВторое дыхание!");
        p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }

    private boolean hasNegative(Player p) {
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (NEGATIVE.contains(pe.getType())) return true;
        }
        return false;
    }

    private void applyEffects(Player p, Map<PotionEffectType, Integer> want) {
        Set<PotionEffectType> tracked = applied.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());

        for (Map.Entry<PotionEffectType, Integer> en : want.entrySet()) {
            PotionEffectType type = en.getKey();
            int amp = en.getValue();
            PotionEffect cur = p.getPotionEffect(type);
            boolean ours = tracked.contains(type);
            boolean needs = cur == null
                    || cur.getAmplifier() < amp
                    || (ours && cur.getAmplifier() == amp && cur.getDuration() <= REFRESH_BELOW);
            if (needs) {
                p.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, amp, true, false, true));
                tracked.add(type);
            }
        }

        Iterator<PotionEffectType> it = tracked.iterator();
        while (it.hasNext()) {
            PotionEffectType type = it.next();
            if (want.containsKey(type)) continue;
            PotionEffect cur = p.getPotionEffect(type);
            if (cur != null && cur.getDuration() <= EFFECT_TICKS) {
                p.removePotionEffect(type);
            }
            it.remove();
        }
    }

    private void setModifier(Player p, Attribute attr, NamespacedKey key, boolean on, double amount) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier cur = inst.getModifier(key);
        if (on) {
            if (cur != null && cur.getAmount() == amount) return;
            if (cur != null) inst.removeModifier(key);
            inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
        } else if (cur != null) {
            inst.removeModifier(key);
        }
    }

    public void shutdown() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            setModifier(p, Attribute.ARMOR, waterKey, false, 0);
            setModifier(p, Attribute.ARMOR, lowKey, false, 0);
            setModifier(p, Attribute.KNOCKBACK_RESISTANCE, titanKey, false, 0);
            setModifier(p, Attribute.MAX_HEALTH, lifeKey, false, 0);
            Set<PotionEffectType> tracked = applied.get(p.getUniqueId());
            if (tracked != null) {
                for (PotionEffectType t : tracked) p.removePotionEffect(t);
            }
        }
        applied.clear();
    }
}
