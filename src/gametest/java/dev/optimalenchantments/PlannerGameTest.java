package dev.optimalenchantments;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class PlannerGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        var savedOrder = new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>();
        context.setScreen(() -> new ModMenuIntegration().getModConfigScreenFactory().create(null));
        context.waitForScreen(PlannerScreen.class);
        context.runOnClient(client -> {
            if (!((PlannerScreen) client.gui.screen()).resultLines().equals(java.util.List.of("Select levels, then calculate an order.")))
                throw new AssertionError("New session unexpectedly restored an order");
        });
        context.takeScreenshot("planner-offline");
        context.clickScreenButton("Done");
        try (var world = context.worldBuilder().create()) {
            PlanService customPlan = new PlanService();
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.giveExperienceLevels(30);
                BlockPos pos = player.blockPosition();
                player.level().setBlockAndUpdate(pos, Blocks.ANVIL.defaultBlockState());
                player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new AnvilMenu(id, inventory,
                        ContainerLevelAccess.create(player.level(), pos)), Component.literal("Anvil")));
                AnvilMenu menu = (AnvilMenu) player.containerMenu;
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                menu.getSlot(3).set(sword);
                ItemStack closer = sword.copy();
                var registry = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                var current = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                current.set(registry.getOrThrow(Enchantments.SHARPNESS), 5);
                EnchantmentHelper.setEnchantments(closer, current.toImmutable());
                menu.getSlot(4).set(closer);
                ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                enchantments.set(registry.getOrThrow(Enchantments.UNBREAKING), 3);
                book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
                menu.getSlot(5).set(book);
                menu.broadcastChanges();
            });
            context.waitForScreen(AnvilScreen.class);
            context.waitFor(client -> ((AnvilScreen) client.gui.screen()).getMenu().getSlot(5).hasItem());
            context.runOnClient(client -> {
                if (EnchantmentCatalog.enchantments().isEmpty()) throw new AssertionError("Empty connected registry");
                var custom = EnchantmentCatalog.enchantments().stream().filter(e -> EnchantmentCatalog.id(e).equals("optimalenchantments_test:test_edge")).findFirst().orElseThrow();
                if (custom.value().getMaxLevel() != 2 || custom.value().getAnvilCost() != 14) throw new AssertionError("Custom registry data lost");
                PlannerConfig customConfig = new PlannerConfig();
                customConfig.enchantments.put("optimalenchantments_test:test_edge", 2);
                customPlan.update(customConfig, null, true);
                customConfig.enchantments.put("missing:unknown", 1);
                if (EnchantmentCatalog.validate(customConfig).isEmpty()) throw new AssertionError("Unknown entry accepted");
                PlannerConfig large = new PlannerConfig();
                large.allowIncompatible = true;
                EnchantmentCatalog.enchantments().stream().filter(e -> e.value().canEnchant(EnchantmentCatalog.item(large.item)))
                        .forEach(e -> large.enchantments.put(EnchantmentCatalog.id(e), e.value().getMaxLevel()));
                if (EnchantmentCatalog.validate(large).isEmpty()) throw new AssertionError("Ten-enchantment limit ignored");
                large.moreThanTen = true;
                if (!EnchantmentCatalog.validate(large).isEmpty()) throw new AssertionError("Large hypothetical selection rejected");
                PlannerConfig incompatible = new PlannerConfig();
                incompatible.enchantments.put("minecraft:sharpness", 5);
                incompatible.enchantments.put("minecraft:smite", 5);
                if (EnchantmentCatalog.validate(incompatible).isEmpty()) throw new AssertionError("Conflict not detected");
                incompatible.allowIncompatible = true;
                if (!EnchantmentCatalog.validate(incompatible).isEmpty()) throw new AssertionError("Hypothetical conflict rejected");
                incompatible.enchantments.put("minecraft:respiration", 1);
                if (EnchantmentCatalog.validate(incompatible).isEmpty()) throw new AssertionError("Unsupported item enchantment accepted");
                OptimalEnchantments.config = new PlannerConfig();
                OptimalEnchantments.config.enchantments.put("minecraft:sharpness", 5);
                OptimalEnchantments.config.enchantments.put("minecraft:unbreaking", 3);
                var service = new PlanService();
                var menu = ((AnvilScreen) client.gui.screen()).getMenu();
                service.update(OptimalEnchantments.config, menu, true);
                if (service.targetSlot() != 4) throw new AssertionError("Did not choose closest weapon");
                service.cancel();
            });
            context.waitForScreen(AnvilScreen.class);
            context.waitFor(client -> customPlan.result() != null);
            context.runOnClient(client -> {
                if (customPlan.result().levels() != 14) throw new AssertionError("Custom anvil multiplier ignored");
                var step = PlanService.steps(customPlan.result().root()).getFirst();
                String book = EnchantmentCatalog.label("optimalenchantments_test:test_edge") + " II book";
                String item = EnchantmentCatalog.item(OptimalEnchantments.config.item).getHoverName().getString();
                if (!OptimalEnchantments.missingInputs(step, -1, -1).contains(book)
                        || !OptimalEnchantments.missingInputs(step, -1, -1).contains(item))
                    throw new AssertionError("Missing inputs did not identify both equipment and custom book");
                int left = step.left().ingredient().target() ? 4 : -1;
                int right = step.right().ingredient().target() ? 4 : -1;
                if (!OptimalEnchantments.missingInputs(step, left, right).equals("Missing: " + book))
                    throw new AssertionError("Available equipment incorrectly reported missing");
            });
            context.waitTicks(30);
            context.takeScreenshot("anvil-highlights");
            context.clickScreenButton("Optimal Enchantments");
            context.waitForScreen(PlannerScreen.class);
            context.runOnClient(client -> {
                var buttons = Screens.getWidgets(client.gui.screen());
                if (buttons.stream().anyMatch(button -> java.util.Set.of("Equipment", "Enchantments", "Order", "Rules").contains(button.getMessage().getString())))
                    throw new AssertionError("Old tab navigation still present");
                if (buttons.stream().noneMatch(button -> button.getMessage().getString().equals("5")))
                    throw new AssertionError("Numbered enchantment levels missing");
            });
            context.waitTicks(2);
            context.takeScreenshot("planner-enchantments");
            context.clickScreenButton("Calculate order"); // forces a fresh calculation
            context.waitFor(client -> {
                var lines = ((PlannerScreen) client.gui.screen()).resultLines();
                return !lines.equals(java.util.List.of("Calculating…"));
            });
            context.runOnClient(client -> {
                var lines = ((PlannerScreen) client.gui.screen()).resultLines();
                if (lines.stream().noneMatch(line -> line.startsWith("1. "))) throw new AssertionError("No calculated order");
                savedOrder.set(lines);
            });
            context.takeScreenshot("planner-order");
            context.clickScreenButton("Done");
            context.waitForScreen(AnvilScreen.class);
            context.runOnClient(client -> {
                AnvilScreen anvil = (AnvilScreen) client.gui.screen();
                if (anvil.getMenu().getSlot(4).getItem().isEmpty()) throw new AssertionError("Planner removed item");
                if (PlannerConfig.load().enchantments.size() != 2) throw new AssertionError("Config failed roundtrip");
            });
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                AnvilMenu menu = (AnvilMenu) player.containerMenu;
                menu.getSlot(0).set(menu.getSlot(4).remove(1));
                menu.getSlot(1).set(menu.getSlot(5).remove(1));
                menu.createResult();
                if (menu.getCost() != 3) throw new AssertionError("Vanilla anvil cost changed");
                menu.broadcastChanges();
            });
            context.waitFor(client -> ((AnvilScreen) client.gui.screen()).getMenu().getSlot(0).hasItem());
            context.clickScreenButton("Optimal Enchantments");
            context.waitForScreen(PlannerScreen.class);
            context.runOnClient(client -> {
                if (!((PlannerScreen) client.gui.screen()).resultLines().equals(savedOrder.get()))
                    throw new AssertionError("Closing and reopening lost the calculated order");
            });
            context.clickScreenButton("Done");
            context.waitForScreen(AnvilScreen.class);
            context.runOnClient(client -> {
                AnvilMenu menu = ((AnvilScreen) client.gui.screen()).getMenu();
                if (!menu.getSlot(0).hasItem() || !menu.getSlot(1).hasItem()) throw new AssertionError("Planner cleared anvil inputs");
            });
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                AnvilMenu menu = (AnvilMenu) player.containerMenu;
                menu.clicked(2, 0, ContainerInput.QUICK_MOVE, player);
                if (player.experienceLevel != 27) throw new AssertionError("Actual anvil did not charge three levels");
                menu.broadcastChanges();
            });
            context.waitFor(client -> {
                var menu = ((AnvilScreen) client.gui.screen()).getMenu();
                return !menu.getSlot(0).hasItem() && !menu.getSlot(1).hasItem();
            });
            context.waitTicks(25);
            // Finished item: the selection auto-clears and guidance stops.
            context.waitFor(client -> OptimalEnchantments.config.enchantments.isEmpty());
            context.takeScreenshot("anvil-complete");
            context.clickScreenButton("Optimal Enchantments");
            context.waitForScreen(PlannerScreen.class);
            context.clickScreenButton("Clear");
            context.clickScreenButton("Done");
            context.waitForScreen(AnvilScreen.class);
            context.clickScreenButton("Optimal Enchantments");
            context.waitForScreen(PlannerScreen.class);
            context.runOnClient(client -> {
                if (!((PlannerScreen) client.gui.screen()).resultLines().equals(java.util.List.of("Select levels, then calculate an order.")))
                    throw new AssertionError("Cleared selection restored a stale order");
            });
            context.clickScreenButton("Done");
        }
    }
}
