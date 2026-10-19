package dev.optimalenchantments;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class PlannerButton extends Button {
    private final ItemStack icon;
    private static final Identifier ANVIL = Identifier.withDefaultNamespace("textures/gui/container/anvil.png");
    private boolean selected, anvilIcon;
    private int selectedColor = 0xFF909090;
    private String narration;

    public PlannerButton(int x, int y, int width, int height, String label, OnPress action) {
        this(x, y, width, height, label, action, ItemStack.EMPTY);
    }

    public PlannerButton(int x, int y, int width, int height, String label, OnPress action, ItemStack icon) {
        super(x, y, width, height, Component.literal(label), action, DEFAULT_NARRATION);
        this.icon = icon;
    }

    /** A button drawn as the vanilla anvil GUI's hammer icon, scaled down. */
    public static PlannerButton anvil(int x, int y, int w, int h, String label, OnPress action) {
        var button = new PlannerButton(x, y, w, h, label, action);
        button.anvilIcon = true;
        return button;
    }

    public void setSelectedColor(int color) {
        this.selectedColor = color;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setNarration(String narration) {
        this.narration = narration;
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return narration == null ? super.createNarrationMessage() : Component.literal(narration);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (anvilIcon) {
            int tint = !active ? 0xFF555555 : isHovered() || isFocused() ? 0xFF8C8C8C : 0xFFFFFFFF;
            graphics.blit(RenderPipelines.GUI_TEXTURED, ANVIL, getX() + 1, getY() + 1, 17f, 7f, 10, 10, 30, 30, 256, 256, tint);
            if (isFocused()) graphics.outline(getX(), getY(), getWidth(), getHeight(), 0xFFFFFFFF);
            return;
        }
        if (!icon.isEmpty()) {
            graphics.item(icon, getX() + 3, getY() + (getHeight() - 16) / 2);
            if (isHovered()) graphics.fill(getX() + 3, getY() + (getHeight() - 16) / 2, getX() + 19, getY() + (getHeight() - 16) / 2 + 16, 0x99000000);
            if (isFocused()) graphics.outline(getX(), getY(), getWidth(), getHeight(), 0xFFFFFFFF);
            return;
        }
        extractDefaultSprite(graphics);
        if (selected) {
            graphics.fill(getX() + 2, getY() + 2, getRight() - 2, getBottom() - 2, selectedColor);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), 0xFFFFFFFF);
        }
        extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
}
