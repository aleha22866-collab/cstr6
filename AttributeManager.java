package ru.example.customattributes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class AttributeManager {

    private static final String MARKER = "» ";
    private final CustomAttributesPlugin plugin;
    private final Map<Attr, NamespacedKey> keys = new EnumMap<>(Attr.class);

    public AttributeManager(CustomAttributesPlugin plugin) {
        this.plugin = plugin;
        for (Attr a : Attr.values()) {
            keys.put(a, new NamespacedKey(plugin, a.id));
        }
    }

    /* ---------- работа с предметом ---------- */

    public int getLevel(ItemStack item, Attr attr) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer()
                .get(keys.get(attr), PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public void setLevel(ItemStack item, Attr attr, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (level <= 0) {
            meta.getPersistentDataContainer().remove(keys.get(attr));
        } else {
            meta.getPersistentDataContainer().set(keys.get(attr), PersistentDataType.INTEGER, level);
        }
        refreshLore(meta);
        item.setItemMeta(meta);
    }

    public void clear(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        for (Attr a : Attr.values()) meta.getPersistentDataContainer().remove(keys.get(a));
        refreshLore(meta);
        item.setItemMeta(meta);
    }

    private void refreshLore(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) {
            for (Component line : meta.lore()) {
                String plain = PlainTextComponentSerializer.plainText().serialize(line);
                if (!plain.startsWith(MARKER)) lore.add(line);
            }
        }
        for (Attr a : Attr.values()) {
            Integer lvl = meta.getPersistentDataContainer().get(keys.get(a), PersistentDataType.INTEGER);
            if (lvl != null && lvl > 0) {
                lore.add(Component.text(MARKER + describe(a, lvl), a.color)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lore.isEmpty() ? null : lore);
    }

    public String describe(Attr a, int level) {
        double v = plugin.getConfig().getDouble("values." + a.id, a.defValue) * level;
        String n = fmt(v);
        return switch (a) {
            case ABSORB -> "+" + n + "% шанс поглотить урон от удара";
            case WATER_RESIST -> "+" + n + "% сопротивление урону в воде";
            case FIRE_REDUCE -> "-" + n + "% длительность горения";
            case GOLDEN_HEART -> "+" + n + "% шанс золотого сердца и иссушения атакующему";
            case LEVITATION -> "+" + n + "% шанс левитации атакующему и регенерации";
            case REFLECT -> "+" + n + "% шанс вернуть урон атакующему";
            case FALL_REDUCE -> "-" + n + "% урон от падения";
            case BLAST_RESIST -> "+" + n + "% сопротивление взрывам";
            case ARROW_RESIST -> "+" + n + "% сопротивление стрелам и снарядам";
            case FROST_GUARD -> "+" + n + "% шанс заморозить атакующего";
            case ADRENALINE -> "+" + n + "% шанс ускорения при получении удара";
        };
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /* ---------- суммирование по игроку ---------- */

    /** Суммарное значение в % (с учётом cap) */
    public double total(Player p, Attr attr) {
        int levels = 0;
        EntityEquipment eq = p.getEquipment();
        if (eq == null) return 0;
        for (ItemStack armor : eq.getArmorContents()) {
            levels += getLevel(armor, attr);
        }
        if (plugin.getConfig().getBoolean("include-main-hand", true)) {
            levels += getLevel(eq.getItemInMainHand(), attr);
        }
        if (levels <= 0) return 0;
        double value = levels * plugin.getConfig().getDouble("values." + attr.id, attr.defValue);
        double cap = plugin.getConfig().getDouble("caps." + attr.id, attr.defCap);
        return Math.min(value, cap);
    }
}
