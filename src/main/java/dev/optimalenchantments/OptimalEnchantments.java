package dev.optimalenchantments;

import dev.optimalenchantments.solver.EnchantmentSolver.Node;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;

public final class OptimalEnchantments implements ClientModInitializer {
    public static PlannerConfig config;
    public static boolean openingPlanner;
    /** One shared plan for the anvil guidance and the planner screen, so they cannot disagree. */
    public static final PlanService plan = new PlanService();
    /** Computed once per tick; drawing and the hammer reuse it. */
    private static Guide current;
    private static boolean hadSteps;
    private static Object hadConnection;
    /** Built once per tick (not per frame): wrapped text and operand stacks. */
    private record Display(java.util.List<net.minecraft.util.FormattedCharSequence> lines, int color, ItemStack leftStack, ItemStack rightStack) {}
    private static Display display;
    private static Node displayStep;
    private static ItemStack cachedLeft = ItemStack.EMPTY, cachedRight = ItemStack.EMPTY;
    private static String hadStepsFor = "";

    /** The next anvil combination, and where its operands are (-1 = absent). Loose slots are non-exact suggestions. */
    private record Guide(Node step, int left, int right, int looseLeft, int looseRight) {
        boolean ready() { return left >= 0 && right >= 0; }
        boolean placed() { return left == 0 && right == 1; }
    }

    @Override
    public void onInitializeClient() {
        config = PlannerConfig.load();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AnvilScreen anvil)) return;
            int x = (width - 176) / 2;
            int y = (height - 166) / 2;
            var button = new PlannerButton(x + 3, y + 3, 22, 22, "Optimal Enchantments", ignored -> {
                openingPlanner = true;
                try { client.gui.setScreen(new PlannerScreen(anvil)); }
                finally { openingPlanner = false; }
            }, new ItemStack(Items.ENCHANTED_BOOK));
            button.setTooltip(Tooltip.create(Component.literal("Plan your enchantment order")));
            Screens.getWidgets(screen).add(button);
            // Vanilla anvil-GUI hammer, scaled down, above the arrow.
            var hammer = PlannerButton.anvil(x + 107, y + 36, 12, 12, "Place next ingredients", ignored -> place(client, anvil, plan));
            hammer.setTooltip(Tooltip.create(Component.literal("Place the next ingredients in the anvil")));
            hammer.visible = false;
            Screens.getWidgets(screen).add(hammer);
            current = null;
            ScreenEvents.afterTick(screen).register(s -> {
                plan.update(config, anvil.getMenu(), false);
                if (finished(anvil.getMenu())) plan.update(config, anvil.getMenu(), true);
                var guide = current = guide(anvil.getMenu(), plan);
                display = guide == null ? null : buildDisplay(guide, width);
                hammer.visible = guide != null && guide.ready() && !guide.placed();
                hammer.active = anvil.getMenu().getCarried().isEmpty();
            });
            // After the slots (the background would hide an earlier draw); tooltips are deferred, so they stay on top.
            ScreenEvents.afterForeground(screen).register((s, graphics, mx, my, delta) -> drawGuidance(anvil, plan, graphics, x, y, width, mx, my));
        });
    }

    /** True (after clearing the selection) once a plan that had pending steps has been fully applied to a real item. */
    private static boolean finished(AnvilMenu menu) {
        var result = plan.result();
        if (config.enchantments.isEmpty() || result == null || result.root() == null || plan.targetSlot() < 0) return false;
        var connection = Minecraft.getInstance().getConnection();
        if (connection != hadConnection) { hadConnection = connection; hadSteps = false; hadStepsFor = ""; }
        String key = config.item + config.enchantments;
        if (!key.equals(hadStepsFor)) { hadStepsFor = key; hadSteps = false; }
        if (!PlanService.steps(result.root()).isEmpty()) { hadSteps = true; return false; }
        if (!hadSteps) return false;
        hadSteps = false;
        config.enchantments.clear();
        try { config.save(); } catch (java.io.IOException ignored) { }
        return true;
    }

    private static Guide guide(AnvilMenu menu, PlanService service) {
        var result = service.result();
        if (result == null || result.root() == null) return null;
        var steps = PlanService.steps(result.root());
        var next = PlanService.next(menu, service.targetSlot(), steps);
        if (next == null) return null;
        var step = next.step();
        int left = next.left(), right = next.right();
        int looseLeft = left >= 0 ? left : PlanService.findLoose(menu, step.left(), -1);
        int looseRight = right >= 0 ? right : PlanService.findLoose(menu, step.right(), looseLeft);
        return new Guide(step, left, right, looseLeft, looseRight);
    }

    private static void drawGuidance(AnvilScreen screen, PlanService service, GuiGraphicsExtractor graphics, int x, int y, int width, int mx, int my) {
        AnvilMenu menu = screen.getMenu();
        var result = service.result();
        if (result == null || result.root() == null) return;
        var guide = current;
        var shown = display;
        if (guide == null || shown == null) return;
        highlight(menu, service.targetSlot(), graphics, x, y, 0xFF70D8CD);
        var step = guide.step();
        highlight(menu, guide.left(), graphics, x, y, 0xFF70D8CD);
        highlight(menu, guide.right(), graphics, x, y, 0xFFF2C879);
        // Not an exact match: still point at a partly suitable book, or at a plain book that could be enchanted (yellow).
        if (guide.left() < 0) highlight(menu, guide.looseLeft(), graphics, x, y, looseColor(menu, guide.looseLeft(), 0xFF70D8CD));
        if (guide.right() < 0) highlight(menu, guide.looseRight(), graphics, x, y, looseColor(menu, guide.looseRight(), 0xFFF2C879));
        var font = Minecraft.getInstance().font;
        var lines = shown.lines();
        int color = shown.color();
        int ty = Math.max(4, y - 4 - lines.size() * 10);
        int widest = lines.stream().mapToInt(font::width).max().orElse(0);
        int startX = Math.max(4, (width - widest - 44) / 2);
        // Icons for the next combination: left operand + right operand (real item / enchanted book).
        int iy = Math.max(2, ty + (lines.size() * 10 - 16) / 2);
        var leftStack = shown.leftStack();
        var rightStack = shown.rightStack();
        graphics.item(leftStack, startX, iy);
        graphics.item(rightStack, startX + 20, iy);
        if (my >= iy && my < iy + 16) {
            if (mx >= startX && mx < startX + 16) graphics.setTooltipForNextFrame(font, leftStack, mx, my);
            else if (mx >= startX + 20 && mx < startX + 36) graphics.setTooltipForNextFrame(font, rightStack, mx, my);
        }
        for (var line : lines) {
            graphics.text(font, line, startX + 44, ty, color);
            ty += 10;
        }
    }

    private static Display buildDisplay(Guide guide, int width) {
        var step = guide.step();
        if (step != displayStep) { // stacks only change when the plan step does
            displayStep = step;
            cachedLeft = operandStack(step.left());
            cachedRight = operandStack(step.right());
        }
        String text = guide.ready()
                ? "Combine: " + inputName(step.left()) + " + " + inputName(step.right()) + " (" + step.cost() + " levels)"
                : missingInputs(step, guide.left(), guide.right());
        var lines = Minecraft.getInstance().font.split(Component.literal(text), width - 8 - 44);
        return new Display(lines, guide.ready() ? 0xFF55FF55 : 0xFFF2C879, cachedLeft, cachedRight);
    }

    private static ItemStack operandStack(Node node) {
        return EnchantmentCatalog.stack(config.item, node.ingredient().target(), node.ingredient().enchantments());
    }

    /** Moves the next operands into the anvil inputs (left in slot 0, right in slot 1) using ordinary container clicks. */
    private static void place(Minecraft client, AnvilScreen screen, PlanService service) {
        AnvilMenu menu = screen.getMenu();
        var guide = guide(menu, service); // fresh: slots may have changed since the last tick
        if (guide == null || !guide.ready() || guide.placed() || !menu.getCarried().isEmpty() || client.gameMode == null || client.player == null) return;
        int left = guide.left(), right = guide.right();
        if (left != 0) {
            click(client, menu, left);
            click(client, menu, 0);
            if (!menu.getCarried().isEmpty()) click(client, menu, left); // put the displaced stack where the operand was
            if (right == 0) right = left;
        }
        if (right != 1) {
            click(client, menu, right);
            click(client, menu, 1);
            if (!menu.getCarried().isEmpty()) click(client, menu, right);
        }
    }

    private static void click(Minecraft client, AnvilMenu menu, int slot) {
        client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, client.player);
    }

    static String missingInputs(Node step, int left, int right) {
        var missing = new ArrayList<String>();
        if (left < 0) missing.add(inputName(step.left()));
        if (right < 0) missing.add(inputName(step.right()));
        return "Missing: " + String.join(" + ", missing);
    }

    private static int looseColor(AnvilMenu menu, int index, int partial) {
        return index >= 0 && menu.getSlot(index).getItem().is(Items.BOOK) ? 0xFFFFFF55 : partial;
    }

    private static String inputName(Node node) {
        if (node.ingredient().target()) return EnchantmentCatalog.item(config.item).getHoverName().getString();
        return PlanService.describe(node, true) + " book" + (node.ingredient().priorWork() == 0 ? ""
                : " (prior-work penalty " + node.ingredient().priorWork() + ")");
    }

    private static void highlight(AnvilMenu menu, int index, GuiGraphicsExtractor graphics, int x, int y, int color) {
        if (index < 0 || index >= menu.slots.size()) return;
        var slot = menu.slots.get(index);
        graphics.outline(x + slot.x - 1, y + slot.y - 1, 18, 18, color);
    }
}
