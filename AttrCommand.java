package ru.example.customattributes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AttrCommand implements TabExecutor {

    private final CustomAttributesPlugin plugin;
    private final AttributeManager mgr;
    private final AbilityManager abilities;

    public AttrCommand(CustomAttributesPlugin plugin, AttributeManager mgr, AbilityManager abilities) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.abilities = abilities;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        if (args.length == 0) {
            usage(p);
            return true;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            plugin.reloadConfig();
            abilities.recomputeSignature();
            msg(p, "Конфиг перезагружен.", NamedTextColor.GREEN);
            return true;
        }
        if (sub.equals("abilities")) {
            for (Ability a : Ability.values()) {
                p.sendMessage(Component.text(a.id + " — ", NamedTextColor.GRAY).append(abilities.nameComponent(a)));
            }
            return true;
        }

        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            msg(p, "Возьмите предмет в основную руку.", NamedTextColor.RED);
            return true;
        }

        switch (sub) {
            case "add" -> {
                if (args.length < 2) { usage(p); return true; }
                Attr a = Attr.fromId(args[1]);
                if (a == null) { msg(p, "Неизвестный атрибут.", NamedTextColor.RED); return true; }
                int level = 1;
                if (args.length >= 3) {
                    try { level = Integer.parseInt(args[2]); }
                    catch (NumberFormatException e) { msg(p, "Уровень должен быть числом.", NamedTextColor.RED); return true; }
                }
                if (level < 1) { msg(p, "Уровень должен быть >= 1.", NamedTextColor.RED); return true; }
                mgr.setLevel(item, a, level);
                p.getInventory().setItemInMainHand(item);
                msg(p, "Установлен атрибут: " + a.display + " (ур. " + level + ")", NamedTextColor.GREEN);
            }
            case "remove" -> {
                if (args.length < 2) { usage(p); return true; }
                Attr a = Attr.fromId(args[1]);
                if (a == null) { msg(p, "Неизвестный атрибут.", NamedTextColor.RED); return true; }
                mgr.setLevel(item, a, 0);
                p.getInventory().setItemInMainHand(item);
                msg(p, "Атрибут убран: " + a.display, NamedTextColor.YELLOW);
            }
            case "clear" -> {
                mgr.clear(item);
                p.getInventory().setItemInMainHand(item);
                msg(p, "Все атрибуты убраны.", NamedTextColor.YELLOW);
            }
            case "list" -> {
                boolean any = false;
                for (Attr a : Attr.values()) {
                    int l = mgr.getLevel(item, a);
                    if (l > 0) {
                        any = true;
                        msg(p, a.id + " (ур. " + l + "): " + mgr.describe(a, l), a.color);
                    }
                }
                if (!any) msg(p, "На предмете нет атрибутов.", NamedTextColor.GRAY);
            }
            case "refresh" -> {
                abilities.forceRefresh(item);
                p.getInventory().setItemInMainHand(item);
                msg(p, "Описание способностей предмета обновлено.", NamedTextColor.GREEN);
            }
            case "pool" -> handlePool(p, item, args);
            default -> usage(p);
        }
        return true;
    }

    private void handlePool(Player p, ItemStack item, String[] args) {
        if (args.length < 2) { usage(p); return; }
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length < 4) { usage(p); return; }
                Ability a = Ability.fromId(args[2]);
                if (a == null) { msg(p, "Неизвестная способность. Список: /ca abilities", NamedTextColor.RED); return; }
                double chance;
                try {
                    chance = Double.parseDouble(args[3].replace(',', '.'));
                } catch (NumberFormatException e) {
                    msg(p, "Шанс должен быть числом от 0.01 до 100.", NamedTextColor.RED);
                    return;
                }
                if (chance < 0.01 || chance > 100) {
                    msg(p, "Шанс должен быть от 0.01 до 100.", NamedTextColor.RED);
                    return;
                }
                abilities.setPoolEntry(item, a, chance, p.getUniqueId());
                p.getInventory().setItemInMainHand(item);
                p.sendMessage(Component.text("Добавлено: ", NamedTextColor.GREEN)
                        .append(abilities.nameComponent(a))
                        .append(Component.text(" — шанс " + chance + "%", NamedTextColor.GREEN)));
                msg(p, "Способности определятся, когда предмет возьмёт другой игрок.", NamedTextColor.GRAY);
            }
            case "remove" -> {
                if (args.length < 3) { usage(p); return; }
                Ability a = Ability.fromId(args[2]);
                if (a == null) { msg(p, "Неизвестная способность.", NamedTextColor.RED); return; }
                boolean ok = abilities.removePoolEntry(item, a);
                p.getInventory().setItemInMainHand(item);
                msg(p, ok ? "Убрано из пула." : "Этой способности нет в пуле.", ok ? NamedTextColor.YELLOW : NamedTextColor.GRAY);
            }
            case "clear" -> {
                abilities.clearAll(item);
                p.getInventory().setItemInMainHand(item);
                msg(p, "Пул и способности предмета очищены.", NamedTextColor.YELLOW);
            }
            case "list" -> {
                Map<Ability, Double> pool = abilities.getPool(item);
                if (!pool.isEmpty()) {
                    msg(p, "Пул (ещё не определён):", NamedTextColor.GRAY);
                    for (Map.Entry<Ability, Double> en : pool.entrySet()) {
                        p.sendMessage(Component.text(" • ", NamedTextColor.GRAY)
                                .append(abilities.nameComponent(en.getKey()))
                                .append(Component.text(" — " + en.getValue() + "%", NamedTextColor.GRAY)));
                    }
                    return;
                }
                if (abilities.getAbilities(item).isEmpty()) {
                    msg(p, "На предмете нет пула и активных способностей.", NamedTextColor.GRAY);
                    return;
                }
                msg(p, "Активные способности:", NamedTextColor.GRAY);
                for (Ability a : abilities.getAbilities(item)) {
                    p.sendMessage(Component.text(" • ", NamedTextColor.GRAY).append(abilities.nameComponent(a)));
                }
            }
            case "roll" -> {
                if (abilities.getPool(item).isEmpty()) {
                    msg(p, "У предмета нет пула.", NamedTextColor.RED);
                    return;
                }
                List<Ability> result = abilities.roll(item);
                p.getInventory().setItemInMainHand(item);
                if (result.isEmpty()) {
                    msg(p, "Тест: ни одна способность не выпала.", NamedTextColor.GRAY);
                } else {
                    for (Ability a : result) {
                        p.sendMessage(Component.text("Тест, выпало: ", NamedTextColor.GREEN).append(abilities.nameComponent(a)));
                    }
                }
            }
            default -> usage(p);
        }
    }

    private void usage(Player p) {
        msg(p, "Атрибуты брони/предмета: /ca add <атрибут> [уровень] | remove <атрибут> | clear | list", NamedTextColor.GRAY);
        StringBuilder sb = new StringBuilder("Атрибуты: ");
        for (Attr a : Attr.values()) sb.append(a.id).append(' ');
        msg(p, sb.toString().trim(), NamedTextColor.GRAY);
        msg(p, "Способности левой руки: /ca pool add <способность> <шанс 0.01-100> | remove <способность> | clear | list | roll", NamedTextColor.GRAY);
        msg(p, "Список способностей: /ca abilities   Перезагрузка конфига: /ca reload   Обновить описание предмета в руке: /ca refresh", NamedTextColor.GRAY);
    }

    private void msg(Player p, String text, TextColor color) {
        p.sendMessage(Component.text(text, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("add", "remove", "clear", "list", "pool", "abilities", "reload", "refresh"))
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            for (Attr a : Attr.values())
                if (a.id.startsWith(args[1].toLowerCase())) out.add(a.id);
        } else if (args[0].equalsIgnoreCase("pool")) {
            if (args.length == 2) {
                for (String s : List.of("add", "remove", "clear", "list", "roll"))
                    if (s.startsWith(args[1].toLowerCase())) out.add(s);
            } else if (args.length == 3 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
                for (Ability a : Ability.values())
                    if (a.id.startsWith(args[2].toLowerCase())) out.add(a.id);
            }
        }
        return out;
    }
}
