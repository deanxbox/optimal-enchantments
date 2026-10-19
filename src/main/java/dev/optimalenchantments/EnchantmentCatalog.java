package dev.optimalenchantments;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.*;

public final class EnchantmentCatalog {
    private EnchantmentCatalog() {}

    private static Object cachedFor;
    private static List<Holder.Reference<Enchantment>> cached = List.of();
    private static Map<String, String> labels = Map.of();

    public static List<Holder.Reference<Enchantment>> enchantments() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return List.of();
        var access = connection.registryAccess();
        if (cachedFor != access) {
            cached = access.lookupOrThrow(Registries.ENCHANTMENT).listElements()
                    .sorted(Comparator.comparing(e -> e.value().description().getString())).toList();
            Map<String, String> map = new HashMap<>();
            cached.forEach(e -> map.put(id(e), e.value().description().getString()));
            labels = map;
            cachedFor = access;
        }
        return cached;
    }

    public static String id(Holder<Enchantment> enchantment) {
        return enchantment.unwrapKey().orElseThrow().identifier().toString();
    }

    public static ItemStack item(String id) {
        if (Minecraft.getInstance().getConnection() == null) return ItemStack.EMPTY;
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return ItemStack.EMPTY;
        return new ItemStack(BuiltInRegistries.ITEM.getValue(key));
    }

    /** A real stack carrying the given enchantments (enchanted book when item is null), so vanilla tooltips list them. */
    public static ItemStack stack(ItemStack base, Map<String, Integer> levels) {
        var connection = Minecraft.getInstance().getConnection();
        boolean book = base == null;
        ItemStack stack = book ? new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK) : base.copy();
        if (connection == null || stack.isEmpty()) return stack;
        var lookup = connection.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var mutable = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        levels.forEach((id, level) -> {
            Identifier key = Identifier.tryParse(id);
            if (key != null) lookup.get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, key)).ifPresent(h -> mutable.set(h, level));
        });
        var value = mutable.toImmutable();
        if (book) stack.set(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS, value);
        else EnchantmentHelper.setEnchantments(stack, value);
        return stack;
    }

    public static ItemStack stack(String itemId, boolean target, Map<String, Integer> levels) {
        return target ? stack(item(itemId), levels) : stack((ItemStack) null, levels);
    }
    public static List<Item> items() {
        var enchantments = enchantments();
        return BuiltInRegistries.ITEM.stream().filter(item -> {
            ItemStack stack = new ItemStack(item);
            return EnchantmentHelper.canStoreEnchantments(stack)
                    && enchantments.stream().anyMatch(e -> e.value().canEnchant(stack));
        }).sorted(Comparator.comparing(i -> new ItemStack(i).getHoverName().getString())).toList();
    }

    public static Map<String, Integer> levels(ItemStack stack) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet())
            levels.put(id(entry.getKey()), entry.getIntValue());
        return levels;
    }

    public static String label(String id) {
        enchantments();
        return labels.getOrDefault(id, id);
    }

    public static String validate(PlannerConfig config) {
        if (enchantments().isEmpty()) return "Join a world or server to read its enchantment registry.";
        ItemStack stack = item(config.item);
        if (stack.isEmpty()) return "Choose an available item from the equipment selector.";
        if (!config.moreThanTen && config.enchantments.size() > 10) return "Enable 'Allow more than 10 enchantments' below the list.";
        var available = enchantments();
        List<Holder<Enchantment>> selected = new ArrayList<>();
        for (var entry : config.enchantments.entrySet()) {
            var enchantment = available.stream().filter(e -> id(e).equals(entry.getKey())).findFirst();
            if (enchantment.isEmpty()) return "Unavailable enchantment: " + entry.getKey() + ". Clear the selection to remove it.";
            var holder = enchantment.get();
            if (entry.getValue() > holder.value().getMaxLevel()) return "Level exceeds the registry limit: " + entry.getKey();
            if (!holder.value().canEnchant(stack)) return "This item does not support " + entry.getKey() + ". Clear the selection to remove it.";
            if (!config.allowIncompatible && selected.stream().anyMatch(other -> !Enchantment.areCompatible(holder, other)))
                return "Incompatible selection. Change it or enable 'Allow incompatible enchantments'.";
            selected.add(holder);
        }
        return "";
    }
}
