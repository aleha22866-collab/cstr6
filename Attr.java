package ru.example.customattributes;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public enum Attr {
    ABSORB("absorb", "Поглощение удара", NamedTextColor.AQUA, 3, 75),
    WATER_RESIST("water_resist", "Водная стойкость", NamedTextColor.BLUE, 15, 80),
    FIRE_REDUCE("fire_reduce", "Огнеупорность", NamedTextColor.GOLD, 25, 100),
    GOLDEN_HEART("golden_heart", "Золотое сердце", NamedTextColor.YELLOW, 4, 100),
    LEVITATION("levitation", "Воздушный контрудар", NamedTextColor.LIGHT_PURPLE, 3, 100),
    REFLECT("reflect", "Отражение", NamedTextColor.RED, 4, 100),
    FALL_REDUCE("fall_reduce", "Амортизация", NamedTextColor.GREEN, 15, 90),
    BLAST_RESIST("blast_resist", "Взрывоустойчивость", NamedTextColor.DARK_RED, 10, 80),
    ARROW_RESIST("arrow_resist", "Стрелоупорность", NamedTextColor.GRAY, 10, 70),
    FROST_GUARD("frost_guard", "Морозная защита", NamedTextColor.DARK_AQUA, 5, 100),
    ADRENALINE("adrenaline", "Адреналин", NamedTextColor.WHITE, 4, 100);

    public final String id;
    public final String display;
    public final TextColor color;
    /** значение в % за уровень по умолчанию (если нет в config.yml) */
    public final double defValue;
    /** потолок суммы в % по умолчанию */
    public final double defCap;

    Attr(String id, String display, TextColor color, double defValue, double defCap) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.defValue = defValue;
        this.defCap = defCap;
    }

    public static Attr fromId(String s) {
        for (Attr a : values()) {
            if (a.id.equalsIgnoreCase(s)) return a;
        }
        return null;
    }
}
