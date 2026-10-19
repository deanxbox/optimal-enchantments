package dev.optimalenchantments.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Pure anvil model: disjoint book enchantments, book multipliers, and repair penalties only. */
public final class EnchantmentSolver {
    public enum Objective { LEVELS, XP, PRIOR_WORK }

    public record Ingredient(String id, Map<String, Integer> enchantments, int priorWork, boolean target) {
        public Ingredient {
            Objects.requireNonNull(id);
            enchantments = Map.copyOf(enchantments);
            if (priorWork < 0 || enchantments.values().stream().anyMatch(level -> level <= 0)) {
                throw new IllegalArgumentException("Prior work must be nonnegative and levels positive");
            }
        }
    }

    public record Request(List<Ingredient> ingredients, Map<String, Integer> bookCosts,
                          Objective objective, boolean enforceLevelCap) {
        public Request {
            ingredients = List.copyOf(ingredients);
            bookCosts = Map.copyOf(bookCosts);
            Objects.requireNonNull(objective);
            if (bookCosts.values().stream().anyMatch(cost -> cost < 0)) {
                throw new IllegalArgumentException("Book costs must be nonnegative");
            }
        }
    }

    public record Node(Ingredient ingredient, Node left, Node right, int cost) {}

    /** XP is the sum of XP from level zero to each operation's cost, not a player's actual XP spend. */
    public record Result(Node root, long levels, long xp, int priorWork, boolean exact, String message) {}

    private static final int EXACT_BOOK_LIMIT = 10;

    private EnchantmentSolver() {}

    /** Book keys must be disjoint; overlap with the target uses the maximum level, never an equal-level upgrade. */
    public static Result solve(Request request) {
        Objects.requireNonNull(request);
        if (request.ingredients().isEmpty()) {
            return new Result(null, 0, 0, 0, true, "No ingredients selected.");
        }
        List<Plan> leaves = new ArrayList<>();
        Set<String> bookEnchantments = new HashSet<>();
        int targets = 0;
        for (Ingredient ingredient : request.ingredients()) {
            if (Thread.currentThread().isInterrupted()) return cancelled();
            long weight = 0;
            if (ingredient.target()) {
                targets++;
            } else {
                for (var enchantment : ingredient.enchantments().entrySet()) {
                    if (Thread.currentThread().isInterrupted()) return cancelled();
                    if (!bookEnchantments.add(enchantment.getKey())) {
                        throw new IllegalArgumentException("Duplicate book enchantment: " + enchantment.getKey());
                    }
                    Integer multiplier = request.bookCosts().get(enchantment.getKey());
                    if (multiplier == null) {
                        throw new IllegalArgumentException("Missing book cost: " + enchantment.getKey());
                    }
                    weight = add(weight, (long) multiplier * enchantment.getValue());
                }
            }
            leaves.add(new Plan(ingredient, null, null, 0, ingredient.priorWork(),
                    ingredient.target(), weight, 0, 0));
        }
        if (targets != 1) throw new IllegalArgumentException("Exactly one target is required");
        if (leaves.size() == 1) return result(leaves.getFirst(), true, "No anvil operations needed.");

        Search search = new Search(request);
        if (leaves.size() <= EXACT_BOOK_LIMIT + 1) {
            try {
                Plan optimal = search.exact(leaves);
                return result(optimal, !search.overflow,
                        search.overflow ? "Approximate: numeric limits exceeded (step cost or total XP)."
                                : optimal == null ? "No order satisfies the level cap." : "Optimal order (exact search).");
            } catch (BudgetExceeded ignored) {
                if (Thread.currentThread().isInterrupted()) return cancelled();
            }
        }
        search.resetBudget(750_000_000L, 250_000);
        Plan best = null;
        try {
            // shortcut: bounded greedy candidates can miss feasible/optimal orders; use exact search for proof.
            for (int strategy = 0; strategy < 3; strategy++) {
                Plan candidate = search.greedy(leaves, strategy);
                if (candidate != null && (best == null || search.compare(candidate, best) < 0)) best = candidate;
            }
        } catch (BudgetExceeded ignored) {
            if (Thread.currentThread().isInterrupted()) return cancelled();
        }
        return result(best, false, best == null
                ? "Approximate search found no feasible representable order within its budget; impossibility is not proven."
                : "Approximate order: selection size or search budget exceeded the exact-search limit.");
    }

    private static Result cancelled() {
        return new Result(null, 0, 0, 0, false, "Search cancelled.");
    }

    private record Plan(Ingredient leaf, Plan left, Plan right, int cost, int prior,
                        boolean target, long weight, long levels, long xp) {}

    private static final class BudgetExceeded extends RuntimeException {
        private BudgetExceeded() { super(null, null, false, false); }
    }

    private static final class Search {
        private final Request request;
        private long deadline;
        private int remaining;
        private boolean overflow;

        private Search(Request request) {
            this.request = request;
            resetBudget(2_000_000_000L, 8_000_000);
        }

        private void resetBudget(long nanos, int operations) {
            deadline = System.nanoTime() + nanos;
            remaining = operations;
        }

        private void checkBudget() {
            if (--remaining < 0 || Thread.currentThread().isInterrupted()
                    || (remaining % 256 == 0 && System.nanoTime() - deadline >= 0)) {
                throw new BudgetExceeded();
            }
        }

        private Plan merge(Plan left, Plan right) {
            checkBudget();
            if (right.target) return null;
            long cost = add(right.weight, (long) left.prior + right.prior);
            if (request.enforceLevelCap() && cost >= 40) return null;
            if (cost > Integer.MAX_VALUE) {
                overflow = true;
                return null;
            }
            int prior = (int) Math.min(Integer.MAX_VALUE, 2L * Math.max(left.prior, right.prior) + 1);
            long xp = add(add(left.xp, right.xp), experience((int) cost));
            if (xp == Long.MAX_VALUE) overflow = true;
            return new Plan(null, left, right, (int) cost, prior, left.target,
                    add(left.weight, right.weight), add(add(left.levels, right.levels), cost), xp);
        }

        private int compare(Plan first, Plan second) {
            int comparison = switch (request.objective()) {
                case LEVELS -> Long.compare(first.levels, second.levels);
                case XP -> Long.compare(first.xp, second.xp);
                case PRIOR_WORK -> Integer.compare(first.prior, second.prior);
            };
            if (comparison == 0) comparison = Long.compare(first.levels, second.levels);
            if (comparison == 0) comparison = Integer.compare(first.prior, second.prior);
            return comparison;
        }

        private Plan exact(List<Plan> leaves) {
            int size = 1 << leaves.size();
            List<Map<Integer, Plan>> states = new ArrayList<>(size);
            for (int i = 0; i < size; i++) states.add(new HashMap<>());
            for (int i = 0; i < leaves.size(); i++) states.get(1 << i).put(leaves.get(i).prior, leaves.get(i));
            for (int mask = 1; mask < size; mask++) {
                if (Integer.bitCount(mask) == 1) continue;
                Map<Integer, Plan> output = states.get(mask);
                for (int left = (mask - 1) & mask; left != 0; left = (left - 1) & mask) {
                    int right = mask ^ left;
                    for (Plan a : states.get(left).values()) {
                        for (Plan b : states.get(right).values()) {
                            Plan candidate = merge(a, b);
                            if (candidate == null) continue;
                            Plan old = output.get(candidate.prior);
                            // A subset's future costs depend on its repair penalty, not its internal tree.
                            if (old == null || compare(candidate, old) < 0) output.put(candidate.prior, candidate);
                        }
                    }
                }
            }
            return states.getLast().values().stream().min(this::compare).orElse(null);
        }

        private Plan greedy(List<Plan> leaves, int strategy) {
            List<Plan> pool = new ArrayList<>(leaves);
            if (strategy == 0) {
                Plan target = pool.stream().filter(plan -> plan.target).findFirst().orElseThrow();
                pool.remove(target);
                pool.sort(Comparator.comparingLong(plan -> -plan.weight));
                for (Plan book : pool) {
                    target = merge(target, book);
                    if (target == null) return null;
                }
                return target;
            }
            if (strategy == 1) {
                PriorityQueue<Plan> queue = new PriorityQueue<>(Comparator.comparingInt((Plan plan) -> plan.prior)
                        .thenComparingLong(plan -> -plan.weight));
                queue.addAll(pool);
                while (queue.size() > 1) {
                    Plan a = queue.remove();
                    Plan b = queue.remove();
                    Plan ab = merge(a, b);
                    Plan ba = merge(b, a);
                    Plan best = ab == null ? ba : ba == null || compare(ab, ba) <= 0 ? ab : ba;
                    if (best == null) return null;
                    queue.add(best);
                }
                return queue.remove();
            }
            while (pool.size() > 1) {
                Plan best = null;
                int bestLeft = -1;
                int bestRight = -1;
                for (int a = 0; a < pool.size(); a++) {
                    for (int b = 0; b < pool.size(); b++) {
                        if (a == b) continue;
                        Plan candidate = merge(pool.get(a), pool.get(b));
                        if (candidate != null && (best == null || compare(candidate, best) < 0)) {
                            best = candidate;
                            bestLeft = a;
                            bestRight = b;
                        }
                    }
                }
                if (best == null) return null;
                pool.remove(Math.max(bestLeft, bestRight));
                pool.remove(Math.min(bestLeft, bestRight));
                pool.add(best);
            }
            return pool.getFirst();
        }
    }

    private static long experience(int level) {
        long n = level;
        if (n <= 16) return n * n + 6 * n;
        if (n <= 31) return (5 * n * n - 81 * n + 720) / 2;
        if (n <= 36) return (9 * n * n - 325 * n + 4440) / 2;
        long a = n;
        long b = 9 * n - 325;
        if (a % 2 == 0) a /= 2; else b /= 2;
        return a > (Long.MAX_VALUE - 2220) / b ? Long.MAX_VALUE : a * b + 2220;
    }

    private static long add(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static Result result(Plan plan, boolean exact, String message) {
        if (plan == null) return new Result(null, 0, 0, 0, exact, message);
        // Iterative reconstruction also handles deep fallback trees without using the Java call stack.
        Map<Plan, Node> nodes = new IdentityHashMap<>();
        ArrayDeque<Plan> stack = new ArrayDeque<>();
        stack.push(plan);
        while (!stack.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) return cancelled();
            Plan current = stack.peek();
            if (current.leaf != null) {
                nodes.put(current, new Node(current.leaf, null, null, 0));
            } else if (!nodes.containsKey(current.left)) {
                stack.push(current.left);
                continue;
            } else if (!nodes.containsKey(current.right)) {
                stack.push(current.right);
                continue;
            } else {
                Node left = nodes.get(current.left);
                Node right = nodes.get(current.right);
                Map<String, Integer> enchantments = new HashMap<>(left.ingredient().enchantments());
                right.ingredient().enchantments().forEach((id, level) -> enchantments.merge(id, level, Math::max));
                Ingredient ingredient = new Ingredient(left.ingredient().id(), enchantments, current.prior, current.target);
                nodes.put(current, new Node(ingredient, left, right, current.cost));
            }
            stack.pop();
        }
        return new Result(nodes.get(plan), plan.levels, plan.xp, plan.prior, exact, message);
    }
}
