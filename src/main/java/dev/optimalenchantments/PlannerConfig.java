package dev.optimalenchantments;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.optimalenchantments.solver.EnchantmentSolver.Objective;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlannerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("optimal-enchantments.json");
    public String item = "minecraft:diamond_sword";
    public Map<String, Integer> enchantments = new LinkedHashMap<>();
    public Objective objective = Objective.LEVELS;
    public boolean allowIncompatible;
    public boolean moreThanTen;
    public boolean enforceLevelCap = true;
    public Display orderDisplay = Display.BOTH;

    public enum Display { TEXT, ITEMS, BOTH }

    public static PlannerConfig load() {
        if (!Files.exists(FILE)) return new PlannerConfig();
        try (var reader = Files.newBufferedReader(FILE)) {
            PlannerConfig config = GSON.fromJson(reader, PlannerConfig.class);
            if (config == null || config.item == null || config.enchantments == null || config.objective == null)
                throw new IOException("Incomplete planner settings");
            config.enchantments.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0 || e.getValue() > 255);
            if (config.orderDisplay == null) config.orderDisplay = Display.BOTH;
            return config;
        } catch (Exception e) {
            LoggerFactory.getLogger("optimalenchantments").warn("Cannot read planner config; original file retained", e);
            return new PlannerConfig();
        }
    }

    public void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(this));
        try {
            Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
