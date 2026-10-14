package ru.example.customattributes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Хранит и обрабатывает способности предметов для левой руки.
 * Состояния предмета:
 *  - "пул" (pool): способности и шансы, заданные админом, ещё не определены;
 *  - "определён" (rolled): у предмета есть итоговый список способностей.
 */
public class AbilityManager {

    private static final String MARK = "customattr-managed";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final CustomAttributesPlugin plugin;
    private final NamespacedKey poolKey;
    private final NamespacedKey creatorKey;
    private final NamespacedKey abilitiesKey;
    private final NamespacedKey rolledKey;
    private final NamespacedKey loreSigKey;
    /** подпись оформления: меняется, когда в конфиге меняются названия, описания или порядок цветов */
    private int loreSignature;

    public AbilityManager(CustomAttributesPlugin plugin) {
        this.plugin = plugin;
        this.poolKey = new NamespacedKey(plugin, "ability_pool");
        this.creatorKey = new NamespacedKey(plugin, "ability_creator");
        this.abilitiesKey = new NamespacedKey(plugin, "ability_list");
        this.rolledKey = new NamespacedKey(plugin, "ability_rolled");
        this.loreSigKey = new NamespacedKey(plugin, "ability_lore_sig");
        recomputeSignature();
    }

    private boolean valid(ItemStack item) {
        return item != null && !item.getType().isAir() && item.hasItemMeta();
    }

    /* ---------- тексты из конфига ---------- */

    public String name(Ability a) {
        return plugin.getConfig().getString("abilities." + a.id + ".name", a.defaultName);
    }

    public Component nameComponent(Ability a) {
        return LEGACY.deserialize(name(a));
    }

    public List<String> loreLines(Ability a) {
        List<String> l = plugin.getConfig().getStringList("abilities." + a.id + ".lore");
        if (l.isEmpty()) l = List.of(a.lore1, a.lore2);
        return l;
    }

    private Component line(String s) {
        return LEGACY.deserialize(s).insertion(MARK).decoration(TextDecoration.ITALIC, false);
    }

    private boolean isManaged(Component c) {
        return MARK.equals(c.insertion());
    }

    /* ---------- пул (до определения) ---------- */

    public Map<Ability, Double> getPool(ItemStack item) {
        if (!valid(item)) return new LinkedHashMap<>();
        return readPool(item.getItemMeta().getPersistentDataContainer());
    }

    private Map<Ability, Double> readPool(PersistentDataContainer pdc) {
        Map<Ability, Double> map = new LinkedHashMap<>();
        String raw = pdc.get(poolKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return map;
        for (String part : raw.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            Ability a = Ability.fromId(kv[0]);
            if (a == null) continue;
            try {
                map.put(a, Double.parseDouble(kv[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    private void writePool(PersistentDataContainer pdc, Map<Ability, Double> pool) {
        if (pool.isEmpty()) {
            pdc.remove(poolKey);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Ability, Double> en : pool.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(en.getKey().id).append('=').append(en.getValue());
        }
        pdc.set(poolKey, PersistentDataType.STRING, sb.toString());
    }

    public void setPoolEntry(ItemStack item, Ability a, double chance, UUID creator) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Map<Ability, Double> pool = readPool(pdc);
        pool.put(a, chance);
        writePool(pdc, pool);
        pdc.remove(abilitiesKey);
        pdc.remove(rolledKey);
        pdc.set(creatorKey, PersistentDataType.STRING, creator.toString());
        refreshLore(meta);
        item.setItemMeta(meta);
    }

    public boolean removePoolEntry(ItemStack item, Ability a) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Map<Ability, Double> pool = readPool(pdc);
        boolean existed = pool.remove(a) != null;
        writePool(pdc, pool);
        if (pool.isEmpty()) pdc.remove(creatorKey);
        refreshLore(meta);
        item.setItemMeta(meta);
        return existed;
    }

    /** Полностью убирает пул и определённые способности. */
    public void clearAll(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(poolKey);
        pdc.remove(creatorKey);
        pdc.remove(abilitiesKey);
        pdc.remove(rolledKey);
        refreshLore(meta);
        item.setItemMeta(meta);
    }

    /* ---------- обновление оформления у уже готовых предметов ---------- */

    /** Пересчитать подпись оформления (вызывать после перезагрузки конфига). */
    public final void recomputeSignature() {
        StringBuilder sb = new StringBuilder("format-v3|");
        sb.append(plugin.getConfig().getStringList("color-order"));
        sb.append(plugin.getConfig().getStringList("messages.pending-lore"));
        sb.append(plugin.getConfig().getStringList("messages.none-lore"));
        for (Ability a : Ability.values()) {
            sb.append(name(a)).append(loreLines(a));
        }
        loreSignature = sb.toString().hashCode();
    }

    /**
     * Если предмет оформлен по старым правилам (до обновления плагина или конфига),
     * перестраивает его описание: заново сортирует способности по цвету и т.д.
     * Возвращает true, если предмет изменён.
     */
    public boolean migrateLore(ItemStack item) {
        if (!valid(item)) return false;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(rolledKey, PersistentDataType.BYTE) && readPool(pdc).isEmpty()) return false;
        Integer sig = pdc.get(loreSigKey, PersistentDataType.INTEGER);
        if (sig != null && sig == loreSignature) return false;
        refreshLore(meta);
        item.setItemMeta(meta);
        return true;
    }

    /** Принудительно пересобрать описание способностей предмета (команда /ca refresh). */
    public void forceRefresh(ItemStack item) {
        if (!valid(item)) return;
        ItemMeta meta = item.getItemMeta();
        refreshLore(meta);
        item.setItemMeta(meta);
    }

    /* ---------- сортировка по цвету ---------- */

    /** Первый цветовой код (0-9, a-f) в названии, например "d" для "&d&l✦ Название". */
    private String firstColor(String name) {
        for (int i = 0; i + 1 < name.length(); i++) {
            if (name.charAt(i) == '&') {
                char c = Character.toLowerCase(name.charAt(i + 1));
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) return String.valueOf(c);
            }
        }
        return "";
    }

    /** Чем меньше число, тем выше способность в описании. Цвета не из списка идут в конец. */
    private int colorRank(Ability a) {
        String color = firstColor(name(a));
        List<String> order = plugin.getConfig().getStringList("color-order");
        if (order.isEmpty()) order = List.of("&d", "&6", "&c", "&9", "&8");
        for (int i = 0; i < order.size(); i++) {
            String o = order.get(i).replace("&", "").trim().toLowerCase();
            if (o.equals(color)) return i;
        }
        return Integer.MAX_VALUE;
    }

    /** Стабильная сортировка: внутри одного цвета порядок сохраняется. */
    public void sortByColor(List<Ability> list) {
        list.sort(Comparator.comparingInt(this::colorRank));
    }

    /* ---------- определение способностей ---------- */

    public boolean needsRoll(ItemStack item, UUID player) {
        if (!valid(item)) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(poolKey, PersistentDataType.STRING)) return false;
        if (readPool(pdc).isEmpty()) return false;
        if (plugin.getConfig().getBoolean("roll-for-creator", false)) return true;
        String creator = pdc.get(creatorKey, PersistentDataType.STRING);
        return creator == null || !creator.equals(player.toString());
    }

    /** Бросает шанс на каждую способность из пула и записывает результат в предмет. */
    public List<Ability> roll(ItemStack item) {
        List<Ability> result = new ArrayList<>();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return result;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Map<Ability, Double> pool = readPool(pdc);
        for (Map.Entry<Ability, Double> en : pool.entrySet()) {
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < en.getValue()) {
                result.add(en.getKey());
            }
        }
        sortByColor(result);
        pdc.remove(poolKey);
        pdc.remove(creatorKey);
        StringBuilder sb = new StringBuilder();
        for (Ability a : result) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a.id);
        }
        pdc.set(abilitiesKey, PersistentDataType.STRING, sb.toString());
        pdc.set(rolledKey, PersistentDataType.BYTE, (byte) 1);
        refreshLore(meta);
        item.setItemMeta(meta);
        return result;
    }

    /** Активные (определённые) способности предмета. */
    public Set<Ability> getAbilities(ItemStack item) {
        if (!valid(item)) return Collections.emptySet();
        return readAbilities(item.getItemMeta().getPersistentDataContainer());
    }

    private Set<Ability> readAbilities(PersistentDataContainer pdc) {
        String raw = pdc.get(abilitiesKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        Set<Ability> set = new LinkedHashSet<>();
        for (String id : raw.split(",")) {
            Ability a = Ability.fromId(id);
            if (a != null) set.add(a);
        }
        return set;
    }

    /* ---------- lore ---------- */

    private void refreshLore(ItemMeta meta) {
        // строки, добавленные не нами (описание предмета, атрибуты брони и т.д.)
        List<Component> others = new ArrayList<>();
        List<Component> old = meta.lore();
        if (old != null) {
            for (Component c : old) {
                if (!isManaged(c)) others.add(c);
            }
        }

        // наш блок со способностями
        List<Component> managed = new ArrayList<>();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Map<Ability, Double> pool = readPool(pdc);
        if (!pool.isEmpty()) {
            List<String> lines = plugin.getConfig().getStringList("messages.pending-lore");
            if (lines.isEmpty()) lines = List.of("&5Скрытые способности", "&8Проявятся у нового владельца");
            for (String s : lines) managed.add(line(s));
        } else if (pdc.has(rolledKey, PersistentDataType.BYTE)) {
            List<Ability> active = new ArrayList<>(readAbilities(pdc));
            if (active.isEmpty()) {
                List<String> lines = plugin.getConfig().getStringList("messages.none-lore");
                if (lines.isEmpty()) lines = List.of("&8Способности не проявились");
                for (String s : lines) managed.add(line(s));
            } else {
                sortByColor(active);
                for (Ability a : active) {
                    managed.add(line(name(a)));
                    for (String s : loreLines(a)) managed.add(line(s));
                }
            }
        }

        List<Component> lore = new ArrayList<>();
        if (!managed.isEmpty()) {
            lore.add(line(" "));   // пустая строка сразу после названия предмета
            lore.addAll(managed);
        }
        lore.addAll(others);
        meta.lore(lore.isEmpty() ? null : lore);

        if (managed.isEmpty()) {
            pdc.remove(loreSigKey);
        } else {
            pdc.set(loreSigKey, PersistentDataType.INTEGER, loreSignature);
        }
    }
}
