package dev.optimalenchantments.solver;

import dev.optimalenchantments.solver.EnchantmentSolver.Ingredient;
import dev.optimalenchantments.solver.EnchantmentSolver.Node;
import dev.optimalenchantments.solver.EnchantmentSolver.Objective;
import dev.optimalenchantments.solver.EnchantmentSolver.Request;
import dev.optimalenchantments.solver.EnchantmentSolver.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EnchantmentSolverTest {
    @Test
    void matchesIndependentBruteForceOracleForSmallSelections() {
        Random random = new Random(719);
        for (int sample = 0; sample < 15; sample++) {
            int count = 1 + sample % 4;
            int[] costs = new int[count];
            int[] priors = new int[count + 1];
            for (int i = 0; i < count; i++) costs[i] = 1 + random.nextInt(18);
            for (int i = 0; i < priors.length; i++) priors[i] = random.nextInt(4);
            for (boolean cap : List.of(false, true)) {
                for (Objective objective : Objective.values()) {
                    Request request = request(objective, cap, costs, priors);
                    Result actual = EnchantmentSolver.solve(request);
                    Oracle expected = oracle(request);
                    assertTrue(actual.exact(), actual.message());
                    if (expected == null) {
                        assertNull(actual.root());
                    } else {
                        assertNotNull(actual.root());
                        assertEquals(expected.levels, actual.levels());
                        if (objective == Objective.XP) assertEquals(expected.xp, actual.xp());
                        assertEquals(expected.prior, actual.priorWork());
                        verifyTree(request, actual);
                    }
                }
            }
        }
    }

    @Test
    void xpAndLevelsCanChooseDifferentOrders() {
        Result levels = EnchantmentSolver.solve(request(Objective.LEVELS, false, new int[]{3, 15, 15, 15}));
        Result xp = EnchantmentSolver.solve(request(Objective.XP, false, new int[]{3, 15, 15, 15}));
        assertEquals(56, levels.levels());
        assertEquals(7, levels.priorWork());
        assertEquals(59, xp.levels());
        assertEquals(1268, xp.xp());
        assertTrue(xp.xp() < levels.xp());
    }

    @Test
    void priorWorkObjectiveCanSpendMoreLevelsForLowerFinalPenalty() {
        Result levels = EnchantmentSolver.solve(request(Objective.LEVELS, false, new int[]{7, 7, 7, 7}));
        Result prior = EnchantmentSolver.solve(request(Objective.PRIOR_WORK, false, new int[]{7, 7, 7, 7}));
        assertEquals(39, levels.levels());
        assertEquals(15, levels.priorWork());
        assertEquals(40, prior.levels());
        assertEquals(7, prior.priorWork());
    }

    @Test
    void existingTargetEnchantmentsAreNotChargedAndInitialPriorWorkIsCharged() {
        Ingredient target = new Ingredient("sword", Map.of("existing", 5), 3, true);
        Ingredient book = new Ingredient("book", Map.of("custom:glow", 4, "custom:speed", 2), 7, false);
        Request request = new Request(List.of(target, book), Map.of("custom:glow", 2, "custom:speed", 3),
                Objective.LEVELS, true);
        Result result = EnchantmentSolver.solve(request);
        assertTrue(result.exact());
        assertEquals(24, result.levels());
        assertEquals(15, result.priorWork());
        assertEquals(3, result.root().ingredient().enchantments().size());
        verifyTree(request, result);
    }

    @Test
    void targetCannotBeOnTheRightEvenWhenItsEnchantmentsAreCheaper() {
        Request request = request(Objective.LEVELS, false, new int[]{20});
        Result result = EnchantmentSolver.solve(request);
        assertEquals(20, result.root().cost());
        assertTrue(result.root().left().ingredient().target());
        assertFalse(result.root().right().ingredient().target());
    }

    @Test
    void targetUpgradeUsesMaximumLevelAndRetainsUnselectedEnchantments() {
        for (int existing : new int[]{3, 7}) {
            Ingredient target = new Ingredient("sword", Map.of("sharpness", existing, "unbreaking", 3), 0, true);
            Ingredient book = new Ingredient("upgrade", Map.of("sharpness", 5), 0, false);
            Request request = new Request(List.of(target, book), Map.of("sharpness", 1), Objective.LEVELS, true);
            Result result = EnchantmentSolver.solve(request);
            assertTrue(result.exact());
            assertEquals(5, result.levels());
            assertEquals(Map.of("sharpness", Math.max(existing, 5), "unbreaking", 3),
                    result.root().ingredient().enchantments());
            verifyTree(request, result);
        }
    }

    @Test
    void survivalCapRejectsFortyButAllowsThirtyNineAndCanBeDisabled() {
        Result allowed = EnchantmentSolver.solve(request(Objective.LEVELS, true, new int[]{39}));
        Result denied = EnchantmentSolver.solve(request(Objective.LEVELS, true, new int[]{40}));
        Result uncapped = EnchantmentSolver.solve(request(Objective.LEVELS, false, new int[]{40}));
        assertNotNull(allowed.root());
        assertNull(denied.root());
        assertTrue(denied.exact());
        assertEquals(40, uncapped.levels());
    }

    @Test
    void elevenBooksUseBoundedApproximateFallbackAndReturnAValidPlan() {
        int[] costs = new int[11];
        java.util.Arrays.fill(costs, 1);
        Request request = request(Objective.LEVELS, true, costs);
        Result result = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> EnchantmentSolver.solve(request));
        assertFalse(result.exact());
        assertTrue(result.message().startsWith("Approximate"));
        assertNotNull(result.root());
        verifyTree(request, result);
    }

    @Test
    void tenBooksRemainExactForAPracticalSelection() {
        int[] costs = new int[10];
        java.util.Arrays.fill(costs, 1);
        Request request = request(Objective.LEVELS, true, costs);
        Result result = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> EnchantmentSolver.solve(request));
        assertTrue(result.exact(), result.message());
        assertNotNull(result.root());
        verifyTree(request, result);
    }

    @Test
    void bookMergesConsiderBothDirectionsAndTargetMayBeInAnyInputPosition() {
        Ingredient target = new Ingredient("target", Map.of(), 7, true);
        Ingredient cheap = new Ingredient("cheap", Map.of("cheap", 1), 0, false);
        Ingredient expensive = new Ingredient("expensive", Map.of("expensive", 1), 0, false);
        Request request = new Request(List.of(cheap, target, expensive), Map.of("cheap", 1, "expensive", 10),
                Objective.LEVELS, true);
        Result result = EnchantmentSolver.solve(request);
        assertTrue(result.exact());
        assertEquals(20, result.levels());
        assertEquals("expensive", result.root().right().left().ingredient().id());
        assertEquals("cheap", result.root().right().right().ingredient().id());
        verifyTree(request, result);
    }

    @Test
    void largerUncappedSelectionRemainsBounded() {
        int[] costs = new int[100];
        java.util.Arrays.fill(costs, 1);
        Result result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> EnchantmentSolver.solve(request(Objective.XP, false, costs)));
        assertFalse(result.exact());
        assertNotNull(result.message());
        assertNotNull(result.root());
        verifyTree(request(Objective.XP, false, costs), result);
    }

    @Test
    void emptySelectionAndTargetWithoutBooksNeedNoOperations() {
        Result empty = EnchantmentSolver.solve(new Request(List.of(), Map.of(), Objective.LEVELS, true));
        assertNull(empty.root());
        assertTrue(empty.exact());
        Ingredient target = new Ingredient("target", Map.of("existing", 1), 7, true);
        Result targetOnly = EnchantmentSolver.solve(new Request(List.of(target), Map.of(), Objective.XP, true));
        assertEquals(target, targetOnly.root().ingredient());
        assertNull(targetOnly.root().left());
        assertNull(targetOnly.root().right());
        assertEquals(0, targetOnly.levels());
        assertEquals(0, targetOnly.xp());
        assertEquals(7, targetOnly.priorWork());
        assertTrue(targetOnly.exact());
    }

    @Test
    void cancellationPreservesInterruptAndNeverClaimsExactness() {
        Thread.currentThread().interrupt();
        try {
            Result result = EnchantmentSolver.solve(request(Objective.LEVELS, true, new int[]{1, 2, 3}));
            assertNull(result.root());
            assertFalse(result.exact());
            assertTrue(result.message().contains("cancelled"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void runningWorkerTerminatesPromptlyWhenInterrupted() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            int[] costs = {2, 7, 4, 9, 1, 8, 3, 6, 5, 2};
            Request request = request(Objective.XP, false, costs);
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Result> answer = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                started.countDown();
                try {
                    answer.set(EnchantmentSolver.solve(request));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            worker.setDaemon(true);
            worker.start();
            try {
                started.await();
                worker.interrupt();
                worker.join(2000);
                assertFalse(worker.isAlive());
                assertNull(failure.get());
                assertNotNull(answer.get());
                assertFalse(answer.get().exact());
                assertTrue(answer.get().message().contains("cancelled"));
            } finally {
                worker.interrupt();
            }
        });
    }

    @Test
    void repairPenaltySaturatesWithoutWrapping() {
        Result result = EnchantmentSolver.solve(request(Objective.LEVELS, false,
                new int[]{0}, Integer.MAX_VALUE, 0));
        assertNotNull(result.root());
        assertEquals(Integer.MAX_VALUE, result.priorWork());
        assertEquals(Integer.MAX_VALUE, result.levels());
        assertEquals(Long.MAX_VALUE, result.xp());
        assertFalse(result.exact(), "Saturated XP cannot be advertised as an exact objective value");
    }

    @Test
    void unrepresentableStepCostAndHugeBookWeightDoNotWrap() {
        Result result = EnchantmentSolver.solve(request(Objective.LEVELS, false,
                new int[]{1}, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertNull(result.root());
        assertFalse(result.exact());
        Ingredient book = new Ingredient("huge", Map.of("a", Integer.MAX_VALUE, "b", Integer.MAX_VALUE,
                "c", Integer.MAX_VALUE), 0, false);
        Request request = new Request(List.of(new Ingredient("target", Map.of(), 0, true), book),
                Map.of("a", Integer.MAX_VALUE, "b", Integer.MAX_VALUE, "c", Integer.MAX_VALUE),
                Objective.XP, false);
        Result huge = EnchantmentSolver.solve(request);
        assertNull(huge.root());
        assertFalse(huge.exact());
    }

    @Test
    void xpUsesAllThreeLevelZeroFormulaRanges() {
        int[] levels = {0, 1, 16, 17, 31, 32, 33, 36, 37, 39, 40, 100};
        long[] xp = {0, 7, 352, 394, 1507, 1628, 1758, 2202, 2368, 2727, 2920, 30970};
        for (int i = 0; i < levels.length; i++) {
            Result result = EnchantmentSolver.solve(request(Objective.XP, false, new int[]{levels[i]}));
            assertEquals(xp[i], result.xp(), "level " + levels[i]);
        }
    }

    @Test
    void invalidInputsAreRejectedWithoutInventingRegistryCosts() {
        assertThrows(IllegalArgumentException.class, () -> new Ingredient("bad", Map.of("a", 0), 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Ingredient("bad", Map.of(), -1, false));
        assertThrows(IllegalArgumentException.class, () -> new Request(List.of(), Map.of("a", -1), Objective.XP, true));
        assertThrows(IllegalArgumentException.class, () -> EnchantmentSolver.solve(new Request(
                List.of(new Ingredient("book", Map.of("a", 1), 0, false)), Map.of("a", 1), Objective.XP, true)));
        assertThrows(IllegalArgumentException.class, () -> EnchantmentSolver.solve(new Request(
                List.of(new Ingredient("target", Map.of(), 0, true), new Ingredient("book", Map.of("a", 1), 0, false)),
                Map.of(), Objective.XP, true)));
        Ingredient target = new Ingredient("target", Map.of(), 0, true);
        Ingredient a = new Ingredient("a", Map.of("duplicate", 1), 0, false);
        Ingredient b = new Ingredient("b", Map.of("duplicate", 2), 0, false);
        assertThrows(IllegalArgumentException.class, () -> EnchantmentSolver.solve(new Request(
                List.of(target, a, b), Map.of("duplicate", 1), Objective.LEVELS, false)));
    }

    @Test
    void requestAndIngredientSnapshotMutableCollections() {
        Map<String, Integer> enchantments = new HashMap<>(Map.of("a", 1));
        Ingredient book = new Ingredient("book", enchantments, 0, false);
        List<Ingredient> ingredients = new ArrayList<>(List.of(new Ingredient("target", Map.of(), 0, true), book));
        Map<String, Integer> costs = new HashMap<>(Map.of("a", 3));
        Request request = new Request(ingredients, costs, Objective.LEVELS, true);
        ingredients.clear();
        enchantments.clear();
        costs.clear();
        assertEquals(3, EnchantmentSolver.solve(request).levels());
    }

    private static Request request(Objective objective, boolean cap, int[] costs, int... priors) {
        List<Ingredient> ingredients = new ArrayList<>();
        Map<String, Integer> multipliers = new HashMap<>();
        ingredients.add(new Ingredient("target", Map.of(), priors.length == 0 ? 0 : priors[0], true));
        for (int i = 0; i < costs.length; i++) {
            String id = "custom:enchantment_" + i;
            ingredients.add(new Ingredient("book_" + i, Map.of(id, 1), priors.length == 0 ? 0 : priors[i + 1], false));
            multipliers.put(id, costs[i]);
        }
        return new Request(ingredients, multipliers, objective, cap);
    }

    private record Oracle(long weight, int prior, boolean target, long levels, long xp) {}

    // Exhaustively merge every legal ordered pair in a forest: independent of subset DP and its pruning.
    private static Oracle oracle(Request request) {
        List<Oracle> forest = new ArrayList<>();
        for (Ingredient ingredient : request.ingredients()) {
            long weight = ingredient.target() ? 0 : ingredient.enchantments().entrySet().stream()
                    .mapToLong(entry -> (long) entry.getValue() * request.bookCosts().get(entry.getKey())).sum();
            forest.add(new Oracle(weight, ingredient.priorWork(), ingredient.target(), 0, 0));
        }
        return brute(forest, request);
    }

    private static Oracle brute(List<Oracle> forest, Request request) {
        if (forest.size() == 1) return forest.getFirst();
        Oracle best = null;
        Comparator<Oracle> comparator = switch (request.objective()) {
            case LEVELS -> Comparator.comparingLong(Oracle::levels).thenComparingInt(Oracle::prior);
            case XP -> Comparator.comparingLong(Oracle::xp).thenComparingLong(Oracle::levels).thenComparingInt(Oracle::prior);
            case PRIOR_WORK -> Comparator.comparingInt(Oracle::prior).thenComparingLong(Oracle::levels);
        };
        for (int i = 0; i < forest.size(); i++) {
            for (int j = 0; j < forest.size(); j++) {
                if (i == j || forest.get(j).target) continue;
                Oracle a = forest.get(i);
                Oracle b = forest.get(j);
                int cost = (int) (b.weight + a.prior + b.prior);
                if (request.enforceLevelCap() && cost >= 40) continue;
                List<Oracle> next = new ArrayList<>(forest);
                next.remove(Math.max(i, j));
                next.remove(Math.min(i, j));
                next.add(new Oracle(a.weight + b.weight, Math.max(a.prior, b.prior) * 2 + 1, a.target,
                        a.levels + b.levels + cost, a.xp + b.xp + xpFromZero(cost)));
                Oracle candidate = brute(next, request);
                if (candidate != null && (best == null || comparator.compare(candidate, best) < 0)) best = candidate;
            }
        }
        return best;
    }

    private static long xpFromZero(int level) {
        long xp = 0;
        for (int current = 0; current < level; current++) {
            xp += current < 16 ? 2L * current + 7 : current < 31 ? 5L * current - 38 : 9L * current - 158;
        }
        return xp;
    }

    private static void verifyTree(Request request, Result result) {
        Set<Ingredient> leaves = new HashSet<>();
        long[] totals = verifyNode(request, result.root(), leaves);
        assertEquals(new HashSet<>(request.ingredients()), leaves);
        assertTrue(result.root().ingredient().target());
        assertEquals(totals[0], result.levels());
        assertEquals(totals[1], result.xp());
        assertEquals(result.root().ingredient().priorWork(), result.priorWork());
    }

    private static long[] verifyNode(Request request, Node node, Set<Ingredient> leaves) {
        if (node.left() == null) {
            assertNull(node.right());
            assertEquals(0, node.cost());
            assertTrue(leaves.add(node.ingredient()), "Each ingredient must be used exactly once");
            return new long[]{0, 0};
        }
        assertNotNull(node.right());
        assertFalse(node.right().ingredient().target());
        long[] left = verifyNode(request, node.left(), leaves);
        long[] right = verifyNode(request, node.right(), leaves);
        long weight = node.right().ingredient().enchantments().entrySet().stream()
                .mapToLong(entry -> (long) entry.getValue() * request.bookCosts().get(entry.getKey())).sum();
        assertEquals(weight + node.left().ingredient().priorWork() + node.right().ingredient().priorWork(), node.cost());
        if (request.enforceLevelCap()) assertTrue(node.cost() < 40);
        assertEquals(Math.min(Integer.MAX_VALUE, 2L * Math.max(node.left().ingredient().priorWork(),
                node.right().ingredient().priorWork()) + 1), node.ingredient().priorWork());
        Map<String, Integer> merged = new HashMap<>(node.left().ingredient().enchantments());
        node.right().ingredient().enchantments().forEach((id, level) -> merged.merge(id, level, Math::max));
        assertEquals(merged, node.ingredient().enchantments());
        return new long[]{left[0] + right[0] + node.cost(), left[1] + right[1] + xpFromZero(node.cost())};
    }
}
