package dev.optimalenchantments;

import dev.optimalenchantments.solver.EnchantmentSolver;
import dev.optimalenchantments.solver.EnchantmentSolver.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.concurrent.*;

public final class PlanService {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Optimal enchantment planner");
        thread.setDaemon(true);
        return thread;
    });
    private Future<Result> calculation;
    private Result result;
    private String error = "";
    private int targetSlot = -1;
    private String signature = "";

    public Result result() {
        if (calculation != null && calculation.isDone()) {
            try { result = calculation.get(); }
            catch (CancellationException ignored) { }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (ExecutionException e) { error = "Calculation failed: " + e.getCause().getMessage(); }
            calculation = null;
        }
        return result;
    }

    public boolean busy() { result(); return calculation != null; }
    public String error() { return error; }
    public int targetSlot() { return targetSlot; }

    public void cancel() {
        if (calculation != null) calculation.cancel(true);
        calculation = null;
    }

    public void update(PlannerConfig config, AnvilMenu menu, boolean force) {
        // Connection identity: costs/compatibility come from the connected registry, so a new server must recompute.
        String next = System.identityHashCode(Minecraft.getInstance().getConnection()) + config.item + config.enchantments + config.objective + config.allowIncompatible
                + config.moreThanTen + config.enforceLevelCap;
        if (menu != null) for (Slot slot : menu.slots) {
            if (slot.index == 2) continue;
            ItemStack stack = slot.getItem();
            next += ";" + slot.index + ":" + stack.getItem() + ":" + EnchantmentCatalog.levels(stack)
                    + ":" + stack.getOrDefault(DataComponents.REPAIR_COST, 0) + ":" + stack.getCount();
        }
        if (!force && next.equals(signature)) return;
        signature = next;
        cancel();
        result = null;
        targetSlot = -1;
        if (config.enchantments.isEmpty()) { error = ""; return; }
        error = EnchantmentCatalog.validate(config);
        if (!error.isEmpty()) return;

        ItemStack target = EnchantmentCatalog.item(config.item);
        var registry = EnchantmentCatalog.enchantments();
        if (menu != null) {
            int best = Integer.MIN_VALUE;
            for (Slot slot : menu.slots) {
                if (slot.index == 2 || !slot.getItem().is(target.getItem())) continue;
                var levels = EnchantmentCatalog.levels(slot.getItem());
                if (!config.allowIncompatible && registry.stream().anyMatch(held -> levels.containsKey(EnchantmentCatalog.id(held))
                        && registry.stream().anyMatch(wanted -> config.enchantments.containsKey(EnchantmentCatalog.id(wanted))
                        && !held.equals(wanted) && !net.minecraft.world.item.enchantment.Enchantment.areCompatible(held, wanted)))) continue;
                int matching = 0;
                for (var entry : config.enchantments.entrySet())
                    matching += Math.min(levels.getOrDefault(entry.getKey(), 0), entry.getValue());
                int score = matching * 10000 - Math.min(9999, slot.getItem().getOrDefault(DataComponents.REPAIR_COST, 0));
                if (score > best) { best = score; targetSlot = slot.index; target = slot.getItem(); }
            }
        }
        Map<String, Integer> existing = EnchantmentCatalog.levels(target);
        Map<String, Integer> missing = new LinkedHashMap<>();
        config.enchantments.forEach((id, level) -> {
            if (existing.getOrDefault(id, 0) < level) missing.put(id, level);
        });
        Map<String, Integer> costs = new HashMap<>();
        registry.forEach(e -> costs.put(EnchantmentCatalog.id(e), Math.max(1, e.value().getAnvilCost() / 2)));
        if (!config.allowIncompatible) {
            var holders = EnchantmentCatalog.enchantments();
            for (var e : holders) if (existing.containsKey(EnchantmentCatalog.id(e))) {
                for (var desired : holders) if (missing.containsKey(EnchantmentCatalog.id(desired))
                        && !e.equals(desired) && !net.minecraft.world.item.enchantment.Enchantment.areCompatible(e, desired)) {
                    error = "Closest item has a conflicting enchantment; choose another item or hypothetical mode.";
                    return;
                }
            }
        }
        List<Ingredient> ingredients = new ArrayList<>();
        ingredients.add(new Ingredient("target", existing, target.getOrDefault(DataComponents.REPAIR_COST, 0), true));
        if (menu != null) {
            List<Slot> books = menu.slots.stream().filter(s -> s.index != 2 && s.getItem().has(DataComponents.STORED_ENCHANTMENTS))
                    .sorted(Comparator.<Slot>comparingInt(s -> EnchantmentCatalog.levels(s.getItem()).size()).reversed()
                            .thenComparingInt(s -> s.getItem().getOrDefault(DataComponents.REPAIR_COST, 0))).toList();
            for (Slot slot : books) {
                Map<String, Integer> levels = EnchantmentCatalog.levels(slot.getItem());
                // A book that is already too expensive to apply under the cap would hide usable single books.
                long weight = 0;
                for (var e : levels.entrySet()) weight += (long) costs.getOrDefault(e.getKey(), 1) * e.getValue();
                if (config.enforceLevelCap && weight + slot.getItem().getOrDefault(DataComponents.REPAIR_COST, 0) >= 40) continue;
                if (levels.isEmpty() || !levels.entrySet().stream().allMatch(e -> Objects.equals(missing.get(e.getKey()), e.getValue()))) continue;
                ingredients.add(new Ingredient("slot:" + slot.index, levels, slot.getItem().getOrDefault(DataComponents.REPAIR_COST, 0), false));
                levels.keySet().forEach(missing::remove);
            }
        }
        missing.forEach((id, level) -> ingredients.add(new Ingredient(id, Map.of(id, level), 0, false)));
        // Equal-cost orders tie; a canonical ingredient order keeps the plan from reshuffling when you pick up a book.
        ingredients.sort(Comparator.comparing((Ingredient i) -> !i.target())
                .thenComparing(i -> new TreeMap<>(i.enchantments()).toString()));
        Request request = new Request(ingredients, costs, config.objective, config.enforceLevelCap);
        calculation = EXECUTOR.submit(() -> EnchantmentSolver.solve(request));
    }

    public static List<Node> steps(Node root) {
        List<Node> steps = new ArrayList<>();
        collect(root, steps);
        return steps;
    }

    private static void collect(Node node, List<Node> steps) {
        if (node == null || node.left() == null) return;
        collect(node.left(), steps);
        collect(node.right(), steps);
        steps.add(node);
    }

    public static String describe(Node node) {
        return describe(node, false);
    }

    public static String describe(Node node, boolean roman) {
        if (node.ingredient().target()) {
            var item = EnchantmentCatalog.item(OptimalEnchantments.config.item);
            return item.isEmpty() ? "Equipment" : item.getHoverName().getString();
        }
        return node.ingredient().enchantments().entrySet().stream()
                .map(e -> EnchantmentCatalog.label(e.getKey()) + " " + (roman ? roman(e.getValue()) : e.getValue()))
                .reduce((a, b) -> a + ", " + b).orElse("Book");
    }

    public static String roman(int value) {
        if (value < 1 || value > 3999) return Integer.toString(value);
        int[] numbers = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < numbers.length; i++) for (; value >= numbers[i]; value -= numbers[i]) result.append(symbols[i]);
        return result.toString();
    }

    /** Fallback highlight: a book with a wanted enchantment at a sufficient level, else a plain (unenchanted) book. */
    public static int findLoose(AnvilMenu menu, Node node, int excluded) {
        if (node.ingredient().target()) return -1;
        var wanted = node.ingredient().enchantments();
        int plain = -1;
        for (Slot slot : menu.slots) {
            if (slot.index == 2 || slot.index == excluded) continue;
            ItemStack stack = slot.getItem();
            if (stack.has(DataComponents.STORED_ENCHANTMENTS)) {
                var held = EnchantmentCatalog.levels(stack);
                if (wanted.entrySet().stream().anyMatch(e -> held.getOrDefault(e.getKey(), 0) >= e.getValue())) return slot.index;
            } else if (plain < 0 && stack.is(net.minecraft.world.item.Items.BOOK)) plain = slot.index;
        }
        return plain;
    }

    public static int findOperand(AnvilMenu menu, Node node, int targetSlot, int excluded) {
        if (node.ingredient().target()) {
            if (targetSlot < 0 || targetSlot == excluded) return -1;
            ItemStack held = menu.getSlot(targetSlot).getItem();
            // An intermediate target only counts once its earlier steps have actually been applied.
            return !held.isEmpty() && held.is(EnchantmentCatalog.item(OptimalEnchantments.config.item).getItem()) && EnchantmentCatalog.levels(held).equals(node.ingredient().enchantments())
                    && held.getOrDefault(DataComponents.REPAIR_COST, 0) == node.ingredient().priorWork() ? targetSlot : -1;
        }

        for (Slot slot : menu.slots) {
            if (slot.index == 2 || slot.index == excluded || !slot.getItem().has(DataComponents.STORED_ENCHANTMENTS)) continue;
            if (EnchantmentCatalog.levels(slot.getItem()).equals(node.ingredient().enchantments())
                    && slot.getItem().getOrDefault(DataComponents.REPAIR_COST, 0) == node.ingredient().priorWork()) return slot.index;
        }
        return -1;
    }

    public record Next(Node step, int left, int right) {}

    /** The first plan step whose operands you already hold; otherwise the first step (so its missing inputs are shown). */
    public static Next next(AnvilMenu menu, int targetSlot, List<Node> steps) {
        Next first = null;
        for (Node step : steps) {
            int left = findOperand(menu, step.left(), targetSlot, -1);
            int right = findOperand(menu, step.right(), targetSlot, left);
            Next candidate = new Next(step, left, right);
            if (left >= 0 && right >= 0) return candidate;
            if (first == null) first = candidate;
        }
        return first;
    }
}
