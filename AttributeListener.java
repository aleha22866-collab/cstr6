package ru.example.customattributes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

public class AttributeListener implements Listener {

    private final CustomAttributesPlugin plugin;
    private final AttributeManager mgr;
    private final NamespacedKey capKey;
    /** защита от рекурсии при отражении урона */
    private boolean reflecting = false;

    public AttributeListener(CustomAttributesPlugin plugin, AttributeManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.capKey = new NamespacedKey(plugin, "golden_heart_cap");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        // 2) Сопротивление урону в воде
        if (victim.isInWater()) {
            double r = mgr.total(victim, Attr.WATER_RESIST);
            if (r > 0) e.setDamage(e.getDamage() * (1.0 - r / 100.0));
        }

        // Сопротивление по типу урона: падение, взрывы, снаряды
        EntityDamageEvent.DamageCause cause = e.getCause();
        double typeRes = 0;
        if (cause == EntityDamageEvent.DamageCause.FALL) {
            typeRes = mgr.total(victim, Attr.FALL_REDUCE);
        } else if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            typeRes = mgr.total(victim, Attr.BLAST_RESIST);
        } else if (cause == EntityDamageEvent.DamageCause.PROJECTILE) {
            typeRes = mgr.total(victim, Attr.ARROW_RESIST);
        }
        if (typeRes > 0) e.setDamage(e.getDamage() * (1.0 - typeRes / 100.0));

        if (!(e instanceof EntityDamageByEntityEvent ee)) return;

        // 1) Шанс поглотить весь урон
        if (roll(mgr.total(victim, Attr.ABSORB))) {
            e.setCancelled(true);
            return;
        }

        if (reflecting) return; // не запускаем цепочку эффектов от отражённого урона

        LivingEntity attacker = resolveAttacker(ee);
        boolean validAttacker = attacker != null && attacker != victim;

        // 4) Золотое сердце + иссушение атакующему
        double heartChance = mgr.total(victim, Attr.GOLDEN_HEART);
        if (roll(heartChance)) {
            giveGoldenHearts(victim);
            if (validAttacker) {
                attacker.addPotionEffect(new PotionEffect(
                        PotionEffectType.WITHER,
                        ticks("golden-heart.wither-seconds", 2),
                        plugin.getConfig().getInt("golden-heart.wither-amplifier", 0)));
            }
        }

        // 5) Левитация атакующему + регенерация себе
        if (roll(mgr.total(victim, Attr.LEVITATION))) {
            if (validAttacker) {
                attacker.addPotionEffect(new PotionEffect(
                        PotionEffectType.LEVITATION,
                        ticks("levitation.levitation-seconds", 1),
                        plugin.getConfig().getInt("levitation.levitation-amplifier", 0)));
            }
            victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.REGENERATION,
                    ticks("levitation.regen-seconds", 2),
                    plugin.getConfig().getInt("levitation.regen-amplifier", 1)));
        }

        // Морозная защита: замедление атакующему
        if (validAttacker && roll(mgr.total(victim, Attr.FROST_GUARD))) {
            attacker.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    ticks("frost-guard.seconds", 3),
                    plugin.getConfig().getInt("frost-guard.amplifier", 1)));
        }

        // Адреналин: ускорение себе при получении удара
        if (roll(mgr.total(victim, Attr.ADRENALINE))) {
            victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED,
                    ticks("adrenaline.seconds", 3),
                    plugin.getConfig().getInt("adrenaline.amplifier", 1)));
        }

        // 6) Вернуть 150% полученного урона
        if (validAttacker && roll(mgr.total(victim, Attr.REFLECT))) {
            double base = e.getFinalDamage() > 0 ? e.getFinalDamage() : e.getDamage();
            final double dmg = base * plugin.getConfig().getDouble("reflect.multiplier", 1.5);
            if (dmg > 0) {
                if (plugin.getConfig().getBoolean("reflect.feedback", true)) {
                    victim.sendActionBar(Component.text(
                            "Отражение! Урон вернулся: " + String.format("%.1f", dmg / 2.0) + " ♥",
                            NamedTextColor.RED));
                }
                // наносим урон на следующем тике, чтобы не вмешиваться в текущий удар
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!attacker.isValid() || attacker.isDead() || !victim.isValid()) return;
                    reflecting = true;
                    try {
                        attacker.setNoDamageTicks(0);
                        attacker.damage(dmg, victim);
                    } finally {
                        reflecting = false;
                    }
                });
            }
        }
    }

    // 3) -25% к длительности горения
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        double r = mgr.total(p, Attr.FIRE_REDUCE);
        if (r <= 0) return;
        float newDuration = (float) (e.getDuration() * (1.0 - r / 100.0));
        if (newDuration <= 0f) {
            e.setCancelled(true);
        } else {
            e.setDuration(newDuration);
        }
    }

    /* ---------- вспомогательное ---------- */

    private LivingEntity resolveAttacker(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof LivingEntity le) return le;
        if (e.getDamager() instanceof LivingEntity le) return le;
        return null;
    }

    private boolean roll(double percent) {
        return percent > 0 && ThreadLocalRandom.current().nextDouble() * 100.0 < percent;
    }

    private int ticks(String path, double def) {
        return (int) Math.round(plugin.getConfig().getDouble(path, def) * 20);
    }

    /**
     * В 1.21 золотые сердца ограничены атрибутом MAX_ABSORPTION (по умолчанию 0),
     * поэтому на время действия поднимаем лимит временным модификатором.
     */
    private void giveGoldenHearts(Player p) {
        double max = plugin.getConfig().getDouble("golden-heart.max-absorption", 20.0);
        double add = plugin.getConfig().getDouble("golden-heart.hearts", 1) * 2.0;
        AttributeInstance inst = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (inst == null) return;
        if (inst.getModifier(capKey) == null) {
            inst.addTransientModifier(new AttributeModifier(capKey, max, AttributeModifier.Operation.ADD_NUMBER));
        }
        p.setAbsorptionAmount(Math.min(p.getAbsorptionAmount() + add, max));
    }

    /** Убираем временный модификатор, когда золотые сердца закончились. */
    public void cleanupModifiers() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            AttributeInstance inst = p.getAttribute(Attribute.MAX_ABSORPTION);
            if (inst != null && inst.getModifier(capKey) != null && p.getAbsorptionAmount() <= 0) {
                inst.removeModifier(capKey);
            }
        }
    }

    public void removeAllModifiers() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            AttributeInstance inst = p.getAttribute(Attribute.MAX_ABSORPTION);
            if (inst != null && inst.getModifier(capKey) != null) inst.removeModifier(capKey);
        }
    }
}
