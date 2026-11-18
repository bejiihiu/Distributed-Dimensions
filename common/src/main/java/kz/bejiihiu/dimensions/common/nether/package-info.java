/**
 * Platform-free vanilla Nether-portal mathematics: coordinate conversion with 8:1 nether scaling
 * and border clamping, portal search over portal blocks with grouping and selection (full world
 * height, square horizontal radius), portal creation planning (spiral site search, forced
 * placement, 4x5 frame plans), entity exit placement (relative inner-rect mapping, 90-degree
 * rotation when portals differ by 90 degrees), and chunk-ticket offset math.
 *
 * <p>Everything here is pure functions over ints, doubles and small records — no Bukkit, no
 * Paper, no world mutation. The platform side supplies block candidates and world queries
 * through the interfaces in this package and executes the produced plans.
 */
package kz.bejiihiu.dimensions.common.nether;
