package ru.example.customattributes;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Способности, срабатывающие при ударе игрока (предмет в левой руке). */
public class AbilityListener implements Listener {

    private final CustomAttributesPlugin plugin;
    private final AbilityManager abilities;
    private final NamespacedKey capKey;
    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<UUID, Long> elytraBlocked = new HashMap<>();
    private final Map<UUID, Long> phoenixCooldown = new HashMap<>();
    private final Map<UUID, Long> executionCooldown = new HashMap<>();
    private final Map<String, Long> marks = new HashMap<>();
    /** защита от рекурсии, когда способность сама наносит урон другим */
    private boolean busy = false;

    public AbilityListener(CustomAttributesPlugin plugin, AbilityManager abilities) {
        this.plugin = plugin;
        this.abilities = abilities;
        // тот же ключ, что и у атрибута «Золотое сердце» — очистка общая
        this.capKey = new NamespacedKey(plugin, "golden_heart_cap");
    }

    private double d(String key, double def) {
        return plugin.getConfig().getDouble("ability-settings." + key, def);
    }

    private int ticks(String key, double defSeconds) {
        return (int) Math.round(d(key, defSeconds) * 20);
    }

    private boolean roll(double percent) {
        return percent > 0 && ThreadLocalRandom.current().nextDouble() * 100.0 < percent;
    }

    /** Увеличивает счётчик ударов и сообщает, пришёлся ли удар на каждый N-й. */
    private boolean every(Player p, Ability a, String key, int def) {
        int n = Math.max(1, (int) d(key, def));
        String k = p.getUniqueId() + ":" + a.id;
        int count = counters.merge(k, 1, Integer::sum);
        return count % n == 0;
    }

    public boolean isElytraBlocked(Player p) {
        Long until = elytraBlocked.get(p.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            elytraBlocked.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (busy) return;
        if (!(e.getDamager() instanceof Player att)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        if (target == att) return;

        Set<Ability> set = abilities.getAbilities(att.getInventory().getItemInOffHand());
        if (set.isEmpty()) return;

        double add = 0;
        double mult = 1;
        boolean inWater = att.isInWater();

        // 1) каждый 10-й удар — двойной урон
        if (set.contains(Ability.TENTH_HIT) && every(att, Ability.TENTH_HIT, "tenth-hit-every", 10)) {
            mult *= 2;
        }

        // 2) удар Хранителя
        if (set.contains(Ability.WARDEN_STRIKE) && roll(d("warden-chance", 3))) {
            add += d("warden-damage", 20);
            target.getWorld().spawnParticle(Particle.SONIC_BOOM, target.getLocation().add(0, 1, 0), 1);
            target.getWorld().playSound(target.getLocation(), "entity.warden.sonic_boom", 1f, 1f);
        }

        // 3) скорость при бое
        if (set.contains(Ability.COMBAT_SPEED)) {
            att.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    ticks("combat-speed-seconds", 2), 0, true, false, true));
        }

        // 9) Холодная стойкость: пока не горите, шанс при ударе получить Сопротивление
        if (set.contains(Ability.CALM_RESIST) && att.getFireTicks() <= 0
                && roll(d("calm-resist-chance", 5))) {
            att.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    ticks("calm-resist-seconds", 3), (int) d("calm-resist-amplifier", 1), true, false, true));
        }

        // 21) Вампирский укус
        if (set.contains(Ability.VAMPIRE_BITE) && roll(d("vampire-chance", 10))) {
            AttributeInstance mh = att.getAttribute(Attribute.MAX_HEALTH);
            double maxHp = mh == null ? 20.0 : mh.getValue();
            att.setHealth(Math.min(maxHp, att.getHealth() + d("vampire-heal", 2)));
        }

        // 22) Ледяное касание
        if (set.contains(Ability.FROST_STRIKE) && roll(d("frost-strike-chance", 8))) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    ticks("frost-strike-seconds", 3), (int) d("frost-strike-amplifier", 1)));
            target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0),
                    20, 0.3, 0.5, 0.3, 0.02);
        }

        // 23) Поджигающий удар
        if (set.contains(Ability.IGNITE_STRIKE) && roll(d("ignite-chance", 8))) {
            target.setFireTicks(Math.max(target.getFireTicks(), ticks("ignite-seconds", 4)));
        }

        // 24) Гнев небес (молния без урона от самой молнии + доп. урон)
        if (set.contains(Ability.SKY_WRATH) && roll(d("sky-wrath-chance", 2))) {
            add += d("sky-wrath-damage", 6);
            target.getWorld().strikeLightningEffect(target.getLocation());
        }

        // ===== ЛЕГЕНДАРНЫЕ =====

        // Гнев Громовержца
        if (set.contains(Ability.THUNDER_GOD) && every(att, Ability.THUNDER_GOD, "thunder-every", 12)) {
            double dmg = d("thunder-damage", 8);
            add += dmg;
            target.getWorld().strikeLightningEffect(target.getLocation());
            int maxTargets = (int) d("thunder-targets", 3);
            int n = 0;
            busy = true;
            try {
                for (LivingEntity le : nearby(target, d("thunder-radius", 6), att)) {
                    if (n >= maxTargets) break;
                    le.getWorld().strikeLightningEffect(le.getLocation());
                    le.damage(dmg, att);
                    n++;
                }
            } finally {
                busy = false;
            }
        }

        // Остановка времени
        if (set.contains(Ability.TIME_STOP) && every(att, Ability.TIME_STOP, "time-stop-every", 20)) {
            int stopTicks = ticks("time-stop-seconds", 2);
            freeze(target, stopTicks);
            for (LivingEntity le : nearby(target, d("time-stop-radius", 5), att)) {
                freeze(le, stopTicks);
            }
        }

        // Дыхание бури
        if (set.contains(Ability.STORM_BREATH) && roll(d("storm-chance", 4))) {
            int stormTicks = ticks("storm-seconds", 4);
            List<LivingEntity> victims = nearby(target, d("storm-radius", 5), att);
            victims.add(target);
            for (LivingEntity le : victims) {
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stormTicks, 2));
                le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, stormTicks, 0));
                le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION,
                        ticks("storm-levitation-seconds", 2), 0));
                le.getWorld().spawnParticle(Particle.SNOWFLAKE, le.getLocation().add(0, 1, 0),
                        25, 0.4, 0.6, 0.4, 0.03);
            }
        }

        // Кровавая ярость
        if (set.contains(Ability.BLOOD_RAGE)) {
            AttributeInstance hpAttr = att.getAttribute(Attribute.MAX_HEALTH);
            double fullHp = hpAttr == null ? 20.0 : hpAttr.getValue();
            double lostHearts = Math.max(0, fullHp - att.getHealth()) / 2.0;
            add += Math.min(d("blood-rage-max", 6), lostHearts * d("blood-rage-per-heart", 0.5));
        }

        // Метеорит
        if (set.contains(Ability.METEOR_STRIKE) && every(att, Ability.METEOR_STRIKE, "meteor-every", 25)) {
            double meteorDmg = d("meteor-damage", 10);
            int igniteTicks = ticks("meteor-ignite-seconds", 3);
            add += meteorDmg;
            Location loc = target.getLocation();
            target.getWorld().spawnParticle(Particle.FLAME, loc.clone().add(0, 1, 0), 120, 1.2, 0.6, 1.2, 0.1);
            target.getWorld().playSound(loc, "entity.generic.explosion", 1.2f, 0.8f);
            target.setFireTicks(Math.max(target.getFireTicks(), igniteTicks));
            busy = true;
            try {
                for (LivingEntity le : nearby(target, d("meteor-radius", 4), att)) {
                    le.damage(meteorDmg, att);
                    le.setFireTicks(Math.max(le.getFireTicks(), igniteTicks));
                    le.setVelocity(le.getVelocity().add(new Vector(0, 0.7, 0)));
                }
            } finally {
                busy = false;
            }
        }

        // Метка смерти
        if (set.contains(Ability.DEATH_MARK)) {
            String markKey = att.getUniqueId() + ":" + target.getUniqueId();
            final long nowMs = System.currentTimeMillis();
            Long markUntil = marks.get(markKey);
            if (markUntil != null && nowMs < markUntil) {
                mult *= 1.0 + d("death-mark-bonus", 30) / 100.0;
            } else if (roll(d("death-mark-chance", 12))) {
                marks.put(markKey, nowMs + (long) (d("death-mark-seconds", 8) * 1000));
                target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                        ticks("death-mark-seconds", 8), 0));
                if (marks.size() > 500) marks.values().removeIf(t -> t < nowMs);
            }
        }

        // Королевская казнь
        if (set.contains(Ability.ROYAL_EXECUTION)) {
            AttributeInstance tHp = target.getAttribute(Attribute.MAX_HEALTH);
            double tMax = tHp == null ? 20.0 : tHp.getValue();
            long nowExec = System.currentTimeMillis();
            Long execUntil = executionCooldown.get(att.getUniqueId());
            if (target.getHealth() <= tMax * d("execution-threshold", 20) / 100.0
                    && (execUntil == null || nowExec >= execUntil)) {
                executionCooldown.put(att.getUniqueId(),
                        nowExec + (long) (d("execution-cooldown", 20) * 1000));
                add += target.getHealth() * 6.0 + 10.0;
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1, 0),
                        40, 0.3, 0.6, 0.3, 0.05);
            }
        }

        // 4) +2 урона в воде
        if (set.contains(Ability.WATER_DAMAGE) && inWater) {
            add += d("water-damage", 2);
        }

        // 6) шанс тройного урона в воде
        if (set.contains(Ability.WATER_TRIPLE) && inWater && roll(d("water-triple-chance", 3))) {
            mult *= 3;
        }

        // 19) палач
        if (set.contains(Ability.EXECUTIONER)
                && target.getHealth() < d("executioner-hearts", 5) * 2.0) {
            add += d("executioner-damage", 2);
        }

        // 10) золотые сердца при ударе
        if (set.contains(Ability.GOLDEN_STRIKE) && roll(d("golden-strike-chance", 3))) {
            giveAbsorption(att, d("golden-strike-hearts", 4) * 2.0);
        }

        // 12) слабость
        if (set.contains(Ability.WEAKENING_STRIKE) && roll(d("weakening-chance", 5))) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                    ticks("weakening-seconds", 2), 0));
        }

        // 14) каждый 15-й удар — левитация
        if (set.contains(Ability.LIFT_STRIKE) && every(att, Ability.LIFT_STRIKE, "lift-every", 15)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION,
                    ticks("lift-seconds", 2), 0));
        }

        // 18) каждый 20-й удар — тошнота и слабость
        if (set.contains(Ability.NAUSEA_STRIKE) && every(att, Ability.NAUSEA_STRIKE, "nausea-every", 20)) {
            int t = ticks("nausea-seconds", 3);
            target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, t, 0));
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, t, 0));
        }

        // 15) блокировка элитр
        if (set.contains(Ability.ELYTRA_JAM) && target instanceof Player tp
                && roll(d("elytra-jam-chance", 5))) {
            double sec = d("elytra-jam-seconds", 4);
            elytraBlocked.put(tp.getUniqueId(), System.currentTimeMillis() + (long) (sec * 1000));
            if (tp.isGliding()) tp.setGliding(false);
            String msg = plugin.getConfig().getString("messages.elytra-blocked",
                    "&cВаши элитры заблокированы на %seconds% сек!");
            tp.sendActionBar(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(msg.replace("%seconds%", String.valueOf((int) sec))));
        }

        if (mult != 1 || add != 0) {
            e.setDamage(e.getDamage() * mult + add);
        }

        // 17) поломка брони
        if (set.contains(Ability.ARMOR_BREAKER) && roll(d("armor-break-chance", 10))) {
            int vanilla = Math.max(1, (int) (e.getDamage() / 4.0));
            int extra = Math.max(1, (int) Math.round(vanilla * (d("armor-break-multiplier", 2.0) - 1.0)));
            wearArmor(target, extra);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.isGliding() && isElytraBlocked(p)) e.setCancelled(true);
    }

    // 25) Мягкое приземление: меньше урона от падения
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        Set<Ability> set = abilities.getAbilities(p.getInventory().getItemInOffHand());
        if (!set.contains(Ability.SOFT_LANDING)) return;
        double red = Math.min(100.0, d("soft-landing-reduction", 50));
        e.setDamage(e.getDamage() * (1.0 - red / 100.0));
    }

    // Защитные способности: Призрачный шаг, Землетряс, Дух ветра, Кожа титана, Сердце вулкана
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDefense(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Set<Ability> set = abilities.getAbilities(p.getInventory().getItemInOffHand());
        if (set.isEmpty()) return;

        // Призрачный шаг: уворот от удара
        if (set.contains(Ability.SHADOW_DODGE) && e instanceof EntityDamageByEntityEvent
                && roll(d("shadow-dodge-chance", 10))) {
            e.setCancelled(true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                    ticks("shadow-invis-seconds", 2), 0, true, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    ticks("shadow-speed-seconds", 2), 1, true, false, true));
            p.getWorld().playSound(p.getLocation(), "entity.enderman.teleport", 0.8f, 1.4f);
            return;
        }

        // Падение: Землетряс и Дух ветра
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (set.contains(Ability.EARTHSHAKER)) {
                if (e.getDamage() >= d("earthshaker-min-damage", 3)) {
                    double waveDmg = Math.min(d("earthshaker-max", 20),
                            e.getDamage() * d("earthshaker-multiplier", 1.5));
                    busy = true;
                    try {
                        for (LivingEntity le : nearby(p, d("earthshaker-radius", 5), p)) {
                            le.damage(waveDmg, p);
                            le.setVelocity(le.getVelocity().add(new Vector(0, 0.6, 0)));
                        }
                    } finally {
                        busy = false;
                    }
                    p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.2, 0),
                            60, 1.5, 0.1, 1.5, 0.05);
                    p.getWorld().playSound(p.getLocation(), "entity.generic.explosion", 1f, 0.7f);
                }
                e.setCancelled(true);
                return;
            }
            if (set.contains(Ability.WIND_SPIRIT)) {
                e.setCancelled(true);
                return;
            }
        }

        if (set.contains(Ability.TITAN_SKIN)) {
            double red = Math.min(100.0, d("titan-reduction", 10));
            e.setDamage(e.getDamage() * (1.0 - red / 100.0));
        }
        if (set.contains(Ability.VOLCANO_HEART) && e instanceof EntityDamageByEntityEvent ee) {
            LivingEntity atk = resolveAttacker(ee.getDamager());
            if (atk != null && atk != p) {
                atk.setFireTicks(Math.max(atk.getFireTicks(), ticks("volcano-ignite-seconds", 3)));
            }
        }
    }

    // Возрождение Феникса: спасает от смертельного удара
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.VOID || c == EntityDamageEvent.DamageCause.KILL
                || c == EntityDamageEvent.DamageCause.SUICIDE) return;
        Set<Ability> set = abilities.getAbilities(p.getInventory().getItemInOffHand());
        if (!set.contains(Ability.PHOENIX_REBIRTH)) return;
        if (p.getHealth() - e.getFinalDamage() > 0) return;

        long now = System.currentTimeMillis();
        Long until = phoenixCooldown.get(p.getUniqueId());
        if (until != null && now < until) return;
        phoenixCooldown.put(p.getUniqueId(), now + (long) (d("phoenix-cooldown", 300) * 1000));

        e.setCancelled(true);
        AttributeInstance hpAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double fullHp = hpAttr == null ? 20.0 : hpAttr.getValue();
        p.setHealth(Math.max(1.0, Math.min(fullHp, fullHp * d("phoenix-heal-percent", 50) / 100.0)));
        p.setFireTicks(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks("phoenix-regen-seconds", 5), 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, ticks("phoenix-fire-seconds", 8), 0));

        for (LivingEntity le : nearby(p, d("phoenix-radius", 4), p)) {
            le.setFireTicks(Math.max(le.getFireTicks(), ticks("phoenix-ignite-seconds", 4)));
        }
        p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 80, 0.6, 0.8, 0.6, 0.08);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.4);
        p.getWorld().playSound(p.getLocation(), "item.totem.use", 1f, 1f);
        String msg = plugin.getConfig().getString("messages.phoenix", "&6&lВозрождение Феникса!");
        p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }

    // Жнец душ и Охотник за сокровищами: награда за убийство
    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || e.getEntity() == k) return;
        Set<Ability> set = abilities.getAbilities(k.getInventory().getItemInOffHand());
        if (set.isEmpty()) return;

        if (set.contains(Ability.SOUL_HARVEST)) {
            AttributeInstance hpAttr = k.getAttribute(Attribute.MAX_HEALTH);
            double fullHp = hpAttr == null ? 20.0 : hpAttr.getValue();
            k.setHealth(Math.min(fullHp, k.getHealth() + d("soul-heal", 6)));
            giveAbsorption(k, d("soul-absorb", 4));
            k.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks("soul-speed-seconds", 5), 1));
        }

        if (set.contains(Ability.TREASURE_HUNTER) && !(e.getEntity() instanceof Player)) {
            e.setDroppedExp((int) Math.round(e.getDroppedExp() * d("treasure-xp-multiplier", 2)));
            if (roll(d("treasure-double-chance", 25))) {
                List<ItemStack> copy = new ArrayList<>();
                for (ItemStack it : e.getDrops()) copy.add(it.clone());
                e.getDrops().addAll(copy);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        elytraBlocked.remove(id);
        counters.keySet().removeIf(k -> k.startsWith(id.toString()));
    }

    /* ---------- вспомогательное ---------- */

    private List<LivingEntity> nearby(LivingEntity center, double r, Player exclude) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity en : center.getNearbyEntities(r, r, r)) {
            if (!(en instanceof LivingEntity)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le == exclude || le == center || le instanceof ArmorStand) continue;
            if (le instanceof Tameable && ((Tameable) le).isTamed()) continue;
            out.add(le);
        }
        return out;
    }

    private void freeze(LivingEntity le, int ticks) {
        // Замедление VII = полная неподвижность
        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6));
        le.getWorld().spawnParticle(Particle.SNOWFLAKE, le.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.01);
    }

    private LivingEntity resolveAttacker(Entity damager) {
        if (damager instanceof Projectile) {
            Object shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof LivingEntity) return (LivingEntity) shooter;
        }
        if (damager instanceof LivingEntity) return (LivingEntity) damager;
        return null;
    }

    private void wearArmor(LivingEntity target, int extra) {
        EntityEquipment eq = target.getEquipment();
        if (eq == null) return;
        ItemStack[] arr = eq.getArmorContents();
        boolean changed = false;
        for (int i = 0; i < arr.length; i++) {
            ItemStack it = arr[i];
            if (it == null || it.getType().isAir()) continue;
            int max = it.getType().getMaxDurability();
            if (max <= 0) continue;
            ItemMeta meta = it.getItemMeta();
            if (meta == null || meta.isUnbreakable()) continue;
            if (!(meta instanceof Damageable)) continue;
            Damageable dm = (Damageable) meta;
            int nd = dm.getDamage() + extra;
            if (nd >= max) {
                arr[i] = null;
                target.getWorld().playSound(target.getLocation(), "entity.item.break", 1f, 1f);
            } else {
                dm.setDamage(nd);
                it.setItemMeta(meta);
            }
            changed = true;
        }
        if (changed) eq.setArmorContents(arr);
    }

    /** Золотые сердца: поднимаем лимит MAX_ABSORPTION временным модификатором. */
    private void giveAbsorption(Player p, double hp) {
        double max = plugin.getConfig().getDouble("golden-heart.max-absorption", 20.0);
        AttributeInstance inst = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (inst == null) return;
        if (inst.getModifier(capKey) == null) {
            inst.addTransientModifier(new AttributeModifier(capKey, max, AttributeModifier.Operation.ADD_NUMBER));
        }
        p.setAbsorptionAmount(Math.min(p.getAbsorptionAmount() + hp, max));
    }
}
