package dev.optimalenchantments;

import dev.optimalenchantments.solver.EnchantmentSolver.Objective;
import dev.optimalenchantments.solver.EnchantmentSolver.Node;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.io.IOException;
import java.util.*;

public final class PlannerScreen extends Screen {
    private final Screen parent;
    // Shared with the anvil guidance so both always show the same plan; the selection itself persists in the config.
    private final PlanService plan = OptimalEnchantments.plan;
    private final PlannerConfig config = OptimalEnchantments.config;
    private final List<Label> labels = new ArrayList<>();
    private final List<Integer> bands = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private final Map<String, Integer> levelPages = new HashMap<>();
    private List<Item> items = List.of();
    private int left, top, bottom, panelWidth, listWidth, bodyTop, bodyBottom, scroll, contentHeight, resultTop;
    private int itemPage;
    private String itemSearch = "", notice = "", lastResult = "";
    private boolean choosingItem, rebuild;
    private EditBox itemSearchBox;
    private record Label(String text, int x, int y, int width, int color) {}
    private record Icon(ItemStack stack, int x, int y, String name) {}

    public PlannerScreen(Screen parent) {
        super(Component.literal("Optimal Enchantments"));
        this.parent = parent;
    }

    private void refresh(boolean force) {
        if (minecraft != null && minecraft.getConnection() != null && !config.enchantments.isEmpty())
            plan.update(config, anvilMenu(), force);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(620, width * 4 / 5);
        left = (width - panelWidth) / 2;
        int panelHeight = Math.min(320, height * 4 / 5);
        top = (height - panelHeight) / 2;
        bottom = top + panelHeight;
        bodyTop = top + 58;
        bodyBottom = bottom - 46;
        boolean sideBySide = panelWidth >= 600;
        listWidth = sideBySide ? (panelWidth - 28) / 2 : panelWidth - 12;
        clearWidgets();
        refresh(false);
        labels.clear();
        bands.clear();
        icons.clear();
        itemSearchBox = null;

        ItemStack item = EnchantmentCatalog.item(config.item);
        button(left + 26, top + 28, Math.min(250, panelWidth - 26), 20,
                (item.isEmpty() ? "Choose equipment" : item.getHoverName().getString()) + "  ▾", () -> {
                    choosingItem = !choosingItem; itemPage = 0; itemSearch = ""; rebuild = true;
                });
        if (choosingItem) itemDropdown();
        else {
            var enchantments = EnchantmentCatalog.enchantments().stream()
                    .filter(e -> e.value().canEnchant(item)).toList();
            int settingsTop = enchantments.size() * 26 + 12;
            resultTop = sideBySide ? 0 : settingsTop + 154;
            List<String> results = resultLines();
            contentHeight = Math.max(settingsTop + 146, resultTop + 20 + results.size() * 22);
            scroll = Math.clamp(scroll, 0, maxScroll());
            enchantmentRows(enchantments);
            settings(settingsTop);
            int resultX = sideBySide ? left + listWidth + 20 : left;
            int resultWidth = sideBySide ? panelWidth - listWidth - 20 : panelWidth - 12;
            label("Enchantment order", resultX, resultTop, resultWidth, 0xFFFFFFFF);
            var steps = shownSteps();
            var mode = config.orderDisplay;
            for (int i = 0; i < results.size(); i++) {
                int y = resultTop + 20 + i * 22;
                int row = i - 2; // two summary lines precede the steps
                if (mode != PlannerConfig.Display.TEXT && row >= 0 && row < steps.size())
                    stepRow(steps.get(row), row + 1, resultX, y, resultWidth, mode);
                else label(results.get(i), resultX, y, resultWidth,
                        row >= 0 && row < steps.size() ? costColor(steps.get(row).cost()) : lineColor(results.get(i)));
            }
            if (enchantments.isEmpty()) label("Join a world/server to load available enchantments.", left, 0, listWidth, 0xFFE8CE96);
        }

        button(left, bottom - 26, 84, 20, "Calculate order", () -> {
            choosingItem = false;
            save();
            refresh(true);
            if (!sideBySide) scroll = resultTop;
            rebuild = true;
        });
        button(left + 88, bottom - 26, 44, 20, "Clear", () -> {
            config.enchantments.clear(); changed();
        });
        if (!choosingItem && maxScroll() > 0) {
            button(left + panelWidth - 92, bottom - 26, 18, 20, "↑", () -> moveScroll(-52));
            button(left + panelWidth - 70, bottom - 26, 18, 20, "↓", () -> moveScroll(52));
        }
        button(left + panelWidth - 48, bottom - 26, 48, 20, "Done", this::onClose);
    }

    private void enchantmentRows(List<Holder.Reference<Enchantment>> enchantments) {
        for (int i = 0; i < enchantments.size(); i++) {
            var enchantment = enchantments.get(i);
            String id = EnchantmentCatalog.id(enchantment);
            String name = enchantment.value().description().getString();
            int selected = config.enchantments.getOrDefault(id, 0);
            int maximum = enchantment.value().getMaxLevel();
            int nameWidth = Math.min(160, listWidth / 2);
            int available = listWidth - nameWidth - 8;
            int count = Math.max(1, available / 24);
            boolean paged = maximum > count;
            if (paged) count = Math.max(1, (available - 40) / 24);
            int start = Math.clamp(levelPages.getOrDefault(id, 0), 0, (maximum - 1) / count) * count + 1;
            int y = i * 26;
            bands.add(y);
            boolean conflict = !config.allowIncompatible && enchantments.stream().anyMatch(other ->
                    config.enchantments.containsKey(EnchantmentCatalog.id(other)) && !other.equals(enchantment)
                            && !Enchantment.areCompatible(enchantment, other));
            label(name, left + 6, y + 8, nameWidth - 10, selected > 0 ? 0xFF55FF55
                    : conflict ? 0xFF777777 : id.endsWith("_curse") ? 0xFFFF7777 : 0xFFDDDDDD);
            int x = left + nameWidth;
            if (paged) {
                bodyButton(x, y + 2, 18, 20, "‹", () -> {
                    levelPages.put(id, Math.max(0, levelPages.getOrDefault(id, 0) - 1)); rebuild = true;
                });
                x += 20;
            }
            for (int level = start; level <= maximum && level < start + count; level++) {
                int value = level;
                var button = bodyButton(x, y + 2, 22, 20, Integer.toString(level), () -> selectLevel(id, selected == value ? 0 : value));
                button.setSelected(selected == level);
                button.setSelectedColor(0xFF3F8F3F);
                button.setNarration(name + ", level " + level + (selected == level ? ", selected" : ""));
                button.active = !conflict || selected == level;
                button.setTooltip(Tooltip.create(Component.literal(name + " " + level + (selected == level ? " (selected; click to remove)" : "")
                        + (conflict ? " — conflicts with another selection" : ""))));
                x += 24;
            }
            if (paged) {
                int lastPage = (maximum - 1) / count;
                bodyButton(x, y + 2, 18, 20, "›", () -> {
                    levelPages.put(id, Math.min(lastPage, levelPages.getOrDefault(id, 0) + 1)); rebuild = true;
                });
            }
        }
    }

    private void settings(int y) {
        label("Optimize for:", left, y, listWidth, 0xFFFFFFFF);
        Objective[] objectives = Objective.values();
        String[] names = {"Levels", "XP points", "Prior work"};
        int optionWidth = (listWidth - 8) / 3;
        for (int i = 0; i < objectives.length; i++) {
            Objective objective = objectives[i];
            var button = bodyButton(left + i * (optionWidth + 3), y + 14, optionWidth, 20, names[i], () -> {
                config.objective = objective; changed();
            });
            button.setSelected(config.objective == objective);
            button.setTooltip(Tooltip.create(Component.literal(switch (objective) {
                case LEVELS -> "Minimize total levels charged.";
                case XP -> "Minimize XP points, assuming each operation starts at level zero.";
                case PRIOR_WORK -> "Minimize final prior-work penalty, then levels.";
            })));
        }
        checkbox(y + 42, "Allow incompatible enchantments", config.allowIncompatible, () -> config.allowIncompatible = !config.allowIncompatible);
        checkbox(y + 66, "Allow more than 10 enchantments", config.moreThanTen, () -> config.moreThanTen = !config.moreThanTen);
        checkbox(y + 90, "Enforce survival limit (<40 levels)", config.enforceLevelCap, () -> config.enforceLevelCap = !config.enforceLevelCap);
        var display = bodyButton(left, y + 116, listWidth - 8, 20, "Order display: " + switch (config.orderDisplay) {
            case TEXT -> "Text";
            case ITEMS -> "Items";
            case BOTH -> "Items + text";
        }, () -> {
            config.orderDisplay = PlannerConfig.Display.values()[(config.orderDisplay.ordinal() + 1) % 3];
            save(); rebuild = true;
        });
        display.setTooltip(Tooltip.create(Component.literal("Show each order step as text, item/book icons (hover for names), or both.")));
    }

    private void checkbox(int y, String text, boolean selected, Runnable action) {
        var checkbox = Checkbox.builder(Component.literal(text), font).pos(left, bodyTop + y - scroll)
                .maxWidth(listWidth).selected(selected).onValueChange((box, value) -> { action.run(); changed(); }).build();
        checkbox.visible = checkbox.getY() >= bodyTop && checkbox.getBottom() <= bodyBottom;
        addRenderableWidget(checkbox);
    }

    private void itemDropdown() {
        if (items.isEmpty() && !EnchantmentCatalog.enchantments().isEmpty()) items = EnchantmentCatalog.items();
        itemSearchBox = new EditBox(font, left + 26, bodyTop, Math.min(250, panelWidth - 26), 20, Component.literal("Search equipment"));
        itemSearchBox.setHint(Component.literal("Search equipment…"));
        itemSearchBox.setValue(itemSearch);
        itemSearchBox.setResponder(value -> { itemSearch = value; itemPage = 0; rebuild = true; });
        addRenderableWidget(itemSearchBox);
        var filtered = items.stream().filter(item -> (new ItemStack(item).getHoverName().getString() + " " + BuiltInRegistries.ITEM.getKey(item))
                .toLowerCase(Locale.ROOT).contains(itemSearch.toLowerCase(Locale.ROOT))).toList();
        int rows = Math.max(1, (bodyBottom - bodyTop - 50) / 22);
        int pages = Math.max(1, (filtered.size() + rows - 1) / rows);
        itemPage = Math.clamp(itemPage, 0, pages - 1);
        for (int i = 0; i < rows && itemPage * rows + i < filtered.size(); i++) {
            Item item = filtered.get(itemPage * rows + i);
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            var option = button(left + 26, bodyTop + 24 + i * 22, Math.min(250, panelWidth - 26), 20,
                    new ItemStack(item).getHoverName().getString(), () -> {
                        if (!config.item.equals(id)) {
                            config.item = id; config.enchantments.clear(); levelPages.clear(); scroll = 0; changed();
                        }
                        choosingItem = false; rebuild = true;
                    });
            option.setSelected(config.item.equals(id));
        }
        button(left + 26, bodyBottom - 20, 24, 20, "‹", () -> { itemPage = Math.max(0, itemPage - 1); rebuild = true; });
        button(left + 54, bodyBottom - 20, 24, 20, "›", () -> { itemPage = Math.min(pages - 1, itemPage + 1); rebuild = true; });
        if (filtered.isEmpty()) notice = items.isEmpty() ? "Join a world to load its equipment registry." : "No matching equipment.";
    }

    private void selectLevel(String id, int level) {
        if (level > 0 && !config.enchantments.containsKey(id) && !config.moreThanTen && config.enchantments.size() >= 10) {
            notice = "Enable 'Allow more than 10 enchantments' below the list.";
            return;
        }
        if (level == 0) config.enchantments.remove(id); else config.enchantments.put(id, level);
        changed();
    }

    List<String> resultLines() {
        List<String> lines = new ArrayList<>();
        var result = plan.result();
        if (config.enchantments.isEmpty()) return List.of("Select levels, then calculate an order.");
        if (minecraft == null || minecraft.getConnection() == null) return List.of("Join a world or server to calculate an order.");
        if (plan.busy()) return List.of("Calculating…");
        if (!plan.error().isEmpty()) return List.of(plan.error());
        if (result == null) return List.of("Calculating…");
        if (result.root() == null) return List.of(result.message());
        lines.add((result.exact() ? "Exact" : "Approximate") + " · " + result.levels() + " levels · " + result.xp() + " XP");
        lines.add("Final prior-work penalty: " + result.priorWork());
        var steps = PlanService.steps(result.root());
        if (steps.isEmpty()) lines.add("Complete — no additional enchantments needed.");
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            lines.add((i + 1) + ". " + PlanService.describe(step.left()) + " + " + PlanService.describe(step.right()) + " → " + step.cost() + " levels");
        }
        lines.add(result.message());
        return lines;
    }

    private void changed() {
        notice = "";
        save(); refresh(false); rebuild = true;
    }

    private static int costColor(int cost) { return cost <= 15 ? 0xFF55FF55 : cost <= 30 ? 0xFFFFFF55 : 0xFFFF5555; }

    private static int lineColor(String line) {
        if (line.startsWith("Exact") || line.startsWith("Complete")) return 0xFF55FF55;
        if (line.startsWith("Approximate")) return 0xFFFFAA00;
        if (line.startsWith("Final prior-work")) return 0xFF55FFFF;
        if (line.startsWith("Calculating")) return 0xFFFFFF55;
        return 0xFFDDDDDD;
    }

    private PlannerButton button(int x, int y, int w, int h, String text, Runnable action) {
        return addRenderableWidget(new PlannerButton(x, y, w, h, text, ignored -> action.run()));
    }

    private PlannerButton bodyButton(int x, int y, int w, int h, String text, Runnable action) {
        var button = button(x, bodyTop + y - scroll, w, h, text, action);
        button.visible = button.getY() >= bodyTop && button.getBottom() <= bodyBottom;
        return button;
    }

    private void label(String text, int x, int y, int width, int color) { labels.add(new Label(text, x, y, width, color)); }
    private int maxScroll() { return Math.max(0, contentHeight - (bodyBottom - bodyTop)); }
    private void moveScroll(int amount) { scroll = Math.clamp(scroll + amount, 0, maxScroll()); rebuild = true; }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (choosingItem) { itemPage = Math.max(0, itemPage - (int) Math.signum(scrollY)); rebuild = true; }
        else moveScroll(-(int) (scrollY * 26));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (choosingItem && event.isEscape()) { choosingItem = false; rebuild = true; return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (choosingItem && (event.x() < left + 26 || event.x() > left + 26 + Math.min(250, panelWidth - 26)) && event.y() < bodyBottom) {
            choosingItem = false; rebuild = true; return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void tick() {
        refresh(false);
        var result = plan.result();
        String state = plan.busy() ? "busy" : result == null ? plan.error() : result.message() + result.levels() + result.xp() + result.priorWork();
        if (!state.equals(lastResult)) { lastResult = state; rebuild = true; }
        if (rebuild) {
            boolean typing = itemSearchBox != null && itemSearchBox.isFocused();
            int cursor = itemSearchBox == null ? 0 : itemSearchBox.getCursorPosition();
            rebuild = false; init();
            if (typing && itemSearchBox != null) { setFocused(itemSearchBox); itemSearchBox.setCursorPosition(cursor); }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.fill(left - 10, top, left + panelWidth + 10, bottom, 0xFF000000);
        graphics.fill(left - 9, top + 1, left + panelWidth + 9, bottom - 1, 0xFF2B2B2B);
        graphics.fill(left - 8, top + 2, left + panelWidth + 8, top + 4, 0xFF4A4A4A);
        graphics.fill(left - 8, top + 2, left - 6, bottom - 3, 0xFF4A4A4A);
        graphics.fill(left - 8, bottom - 3, left + panelWidth + 8, bottom - 1, 0xFF161616);
        graphics.fill(left + panelWidth + 6, top + 4, left + panelWidth + 8, bottom - 1, 0xFF161616);
        graphics.fill(left - 1, bodyTop - 1, left + panelWidth + 1, bodyBottom + 1, 0xFF0E0E0E);
        graphics.fill(left, bodyTop, left + panelWidth, bodyBottom, 0xFF1C1C1C);
        graphics.text(font, title, left, top + 10, 0xFFFFFFFF);
        graphics.item(EnchantmentCatalog.item(config.item), left + 3, top + 30);
        graphics.enableScissor(left, bodyTop, left + panelWidth, bodyBottom);
        if (!choosingItem) {
            for (int i = 0; i < bands.size(); i++) {
                int y = bodyTop + bands.get(i) - scroll;
                graphics.fill(left, y, left + listWidth, y + 26, i % 2 == 0 ? 0xFF272727 : 0xFF222222);
            }
            for (Label label : labels) {
                int y = bodyTop + label.y() - scroll;
                if (y + 9 <= bodyTop || y >= bodyBottom) continue;
                graphics.text(font, font.plainSubstrByWidth(label.text(), label.width()), label.x(), y, label.color());
                if (mouseX >= label.x() && mouseX < label.x() + label.width() && mouseY >= y && mouseY < y + 12)
                    graphics.setTooltipForNextFrame(Component.literal(label.text()), mouseX, mouseY);
            }
            for (Icon icon : icons) {
                int y = bodyTop + icon.y() - scroll;
                if (y + 16 <= bodyTop || y >= bodyBottom) continue;
                graphics.item(icon.stack(), icon.x(), y);
                if (mouseX >= icon.x() && mouseX < icon.x() + 16 && mouseY >= Math.max(y, bodyTop) && mouseY < Math.min(y + 16, bodyBottom))
                    graphics.setTooltipForNextFrame(font, icon.stack(), mouseX, mouseY);
            }
        }
        graphics.disableScissor();
        if (!choosingItem && maxScroll() > 0) {
            int track = bodyBottom - bodyTop;
            int thumb = Math.max(12, track * track / contentHeight);
            int y = bodyTop + scroll * (track - thumb) / maxScroll();
            graphics.fill(left + panelWidth - 3, bodyTop, left + panelWidth, bodyBottom, 0xFF202020);
            graphics.fill(left + panelWidth - 3, y, left + panelWidth, y + thumb, 0xFFAAAAAA);
        }
        graphics.text(font, font.plainSubstrByWidth(notice, panelWidth), left, bottom - 40, 0xFFE8CE96);
        if (!notice.isEmpty() && mouseY >= bottom - 42 && mouseY <= bottom - 28)
            graphics.setTooltipForNextFrame(Component.literal(notice), mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private net.minecraft.world.inventory.AnvilMenu anvilMenu() {
        if (parent instanceof AnvilScreen anvil && minecraft.player != null && minecraft.player.containerMenu == anvil.getMenu()) return anvil.getMenu();
        return null;
    }

    private void save() {
        try { config.save(); }
        catch (IOException e) { notice = "Could not save settings: " + e.getMessage(); }
    }

    @Override
    public void onClose() {
        save();
        if (parent instanceof AnvilScreen && anvilMenu() == null) minecraft.gui.setScreen(null);
        else minecraft.gui.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
    private List<Node> shownSteps() {
        var result = plan.result();
        if (plan.busy() || config.enchantments.isEmpty() || result == null || result.root() == null || !plan.error().isEmpty()) return List.of();
        return PlanService.steps(result.root());
    }

    private void stepRow(Node step, int number, int x, int y, int width, PlannerConfig.Display mode) {
        label(number + ".", x, y, 20, 0xFFDDDDDD);
        icon(step.left(), x + 20, y - 4);
        label("+", x + 40, y, 8, 0xFFDDDDDD);
        icon(step.right(), x + 50, y - 4);
        int textX = x + 72;
        String text = (mode == PlannerConfig.Display.BOTH ? PlanService.describe(step.left()) + " + " + PlanService.describe(step.right()) + " " : "")
                + "→ " + step.cost() + " levels";
        label(text, textX, y, Math.max(20, x + width - textX), 0xFFDDDDDD);
    }

    private void icon(Node node, int x, int y) {
        icons.add(new Icon(EnchantmentCatalog.stack(config.item, node.ingredient().target(), node.ingredient().enchantments()), x, y, ""));
    }

}
