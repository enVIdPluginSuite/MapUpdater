package dev.etran.mapupdater

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.map.MapPalette
import java.lang.reflect.Method

/**
 * Block -> map "base colour" id (0 = transparent / invisible to maps, 1..61 = vanilla palette).
 * A pixel's final byte is `base * 4 + shade`, exactly like vanilla.
 *
 * Modern servers can tell us a block's map colour directly; everything else (and any block the
 * server does not answer for) is resolved with a name-based table that mirrors vanilla closely.
 */
object MapColors {
    const val NONE = 0
    const val GRASS = 1
    const val SAND = 2
    const val WOOL = 3
    const val FIRE = 4
    const val ICE = 5
    const val METAL = 6
    const val PLANT = 7
    const val SNOW = 8
    const val CLAY = 9
    const val DIRT = 10
    const val STONE = 11
    const val WATER = 12
    const val WOOD = 13
    const val QUARTZ = 14
    const val ORANGE = 15
    const val YELLOW = 18
    const val BROWN = 26
    const val GREEN = 27
    const val RED = 28
    const val BLACK = 29
    const val GOLD = 30
    const val DIAMOND = 31
    const val LAPIS = 32
    const val EMERALD = 33
    const val PODZOL = 34
    const val NETHER = 35
    const val TERRACOTTA_WHITE = 36
    const val CRIMSON_NYLIUM = 52
    const val CRIMSON_STEM = 53
    const val WARPED_NYLIUM = 55
    const val WARPED_STEM = 56
    const val WARPED_WART = 58
    const val DEEPSLATE = 59
    const val RAW_IRON = 60

    private const val UNSET = -1
    private var table = IntArray(0)
    private var useServerColors = true

    // BlockData#getMapColor exists on newer servers only.
    private val getMapColor: Method? = try { BlockData::class.java.getMethod("getMapColor") } catch (_: Throwable) { null }
    @Volatile private var serverColorsBroken = false

    /** Dye colour -> base colour of wool / concrete / carpet. Order matters (LIGHT_* before the plain ones). */
    private val dyes = listOf(
        "WHITE" to SNOW, "ORANGE" to 15, "MAGENTA" to 16, "LIGHT_BLUE" to 17, "YELLOW" to 18, "LIME" to 19,
        "PINK" to 20, "GRAY" to 21, "LIGHT_GRAY" to 22, "CYAN" to 23, "PURPLE" to 24, "BLUE" to 25,
        "BROWN" to 26, "GREEN" to 27, "RED" to 28, "BLACK" to 29
    )

    /** Wood type -> base colour of its planks/logs. */
    private val woods = listOf(
        "DARK_OAK" to 26, "OAK" to WOOD, "SPRUCE" to PODZOL, "BIRCH" to SAND, "JUNGLE" to DIRT,
        "ACACIA" to 15, "MANGROVE" to RED, "CHERRY" to TERRACOTTA_WHITE, "PALE_OAK" to QUARTZ,
        "BAMBOO" to YELLOW, "CRIMSON" to CRIMSON_STEM, "WARPED" to WARPED_STEM
    )

    @Synchronized
    fun configure(useServerMapColors: Boolean) {
        useServerColors = useServerMapColors
        table = IntArray(Material.values().size) { UNSET }
    }

    fun base(m: Material): Int {
        val t = table
        val o = m.ordinal
        if (o >= t.size) return resolve(m)
        var v = t[o]
        if (v == UNSET) { v = resolve(m); t[o] = v }
        return v
    }

    fun isWater(m: Material): Boolean {
        val n = m.name
        return n == "WATER" || n == "BUBBLE_COLUMN" || n == "KELP" || n == "KELP_PLANT" ||
            n == "SEAGRASS" || n == "TALL_SEAGRASS" || n == "STATIONARY_WATER"
    }

    private fun resolve(m: Material): Int {
        val n = m.name
        if (n.startsWith("LEGACY_")) return NONE
        if (isWater(m)) return WATER
        if (useServerColors && !serverColorsBroken && getMapColor != null && m.isBlock) {
            fromServer(m)?.let { return it }
        }
        return heuristic(n)
    }

    private fun fromServer(m: Material): Int? {
        return try {
            if (m.name.endsWith("AIR")) return NONE
            val color = getMapColor!!.invoke(m.createBlockData()) as? Color ?: return null
            if (color.red == 0 && color.green == 0 && color.blue == 0) return NONE
            @Suppress("DEPRECATION")
            val packed = MapPalette.matchColor(color.red, color.green, color.blue).toInt() and 0xFF
            val base = packed / 4
            if (base in 1..80) base else null
        } catch (_: Throwable) {
            serverColorsBroken = true // method exists but misbehaves; use the table from now on
            null
        }
    }

    private fun heuristic(n: String): Int {
        // ---- things maps do not see through to
        if (n.endsWith("AIR") || n == "BARRIER" || n == "LIGHT" || n == "STRUCTURE_VOID" || n.contains("GLASS") ||
            n.contains("TORCH") || n.contains("RAIL") || n.contains("BUTTON") || n == "LEVER" || n.startsWith("TRIPWIRE") ||
            n == "REDSTONE_WIRE" || n == "REPEATER" || n == "COMPARATOR" || n == "LADDER" || n == "END_ROD" ||
            n == "SKELETON_SKULL" || n.endsWith("_HEAD") || n.endsWith("_SKULL")
        ) return NONE

        // ---- dyed families
        for ((idx, d) in dyes.withIndex()) {
            val prefix = d.first + "_"
            if (!n.startsWith(prefix)) continue
            val rest = n.substring(prefix.length)
            when {
                rest.startsWith("GLAZED_TERRACOTTA") || rest == "WOOL" || rest.startsWith("CARPET") ||
                    rest.startsWith("CONCRETE") || rest.endsWith("SHULKER_BOX") || rest == "BED" ||
                    rest.endsWith("BANNER") -> return d.second
                rest == "TERRACOTTA" -> return TERRACOTTA_WHITE + idx
            }
        }
        if (n == "TERRACOTTA") return 37
        if (n == "SHULKER_BOX") return 24

        // ---- plants
        if (n.contains("LEAVES") || n.contains("SAPLING") || n.contains("VINE") || n == "SUGAR_CANE" || n == "CACTUS" ||
            n == "LILY_PAD" || n == "BAMBOO" || n == "BAMBOO_SAPLING" || n.contains("FERN") || n.contains("TULIP") ||
            n.contains("FLOWER") || n.contains("DANDELION") || n == "POPPY" || n.contains("ORCHID") || n == "ALLIUM" ||
            n == "AZURE_BLUET" || n == "OXEYE_DAISY" || n == "LILY_OF_THE_VALLEY" || n == "WITHER_ROSE" || n == "PEONY" ||
            n == "LILAC" || n == "ROSE_BUSH" || n == "SUNFLOWER" || n == "WHEAT" || n == "CARROTS" || n == "POTATOES" ||
            n == "BEETROOTS" || n.contains("STEM") && n.startsWith("MELON") || n.startsWith("PUMPKIN_STEM") ||
            n.contains("DEAD_BUSH") || n.contains("SWEET_BERRY") || n.contains("AZALEA") && !n.contains("LEAVES") ||
            n == "SPORE_BLOSSOM" || n.contains("ROOTS") || n.contains("FUNGUS") || n.contains("SPROUTS") ||
            (n.contains("GRASS") && n != "GRASS_BLOCK" && n != "GRASS_PATH")
        ) return when {
            n.contains("CRIMSON") && (n.contains("ROOTS") || n.contains("FUNGUS")) -> CRIMSON_NYLIUM
            n.contains("WARPED") && (n.contains("ROOTS") || n.contains("FUNGUS") || n.contains("SPROUTS")) -> WARPED_NYLIUM
            else -> PLANT
        }

        // ---- natural ground
        when (n) {
            "GRASS_BLOCK", "SLIME_BLOCK", "MOSS_BLOCK", "MOSS_CARPET" -> return if (n.startsWith("MOSS")) GREEN else GRASS
            "MYCELIUM" -> return 24
            "PODZOL" -> return PODZOL
            "DIRT", "COARSE_DIRT", "FARMLAND", "DIRT_PATH", "GRASS_PATH", "ROOTED_DIRT", "GRANITE", "POLISHED_GRANITE",
            "JUNGLE_LOG", "BROWN_MUSHROOM_BLOCK", "BROWN_MUSHROOM", "PACKED_MUD" -> return DIRT
            "MUD" -> return 45
            "SOUL_SAND", "SOUL_SOIL" -> return BROWN
            "RED_SAND", "RED_SANDSTONE", "SMOOTH_RED_SANDSTONE", "CUT_RED_SANDSTONE", "CHISELED_RED_SANDSTONE" -> return ORANGE
            "CLAY" -> return CLAY
            "GRAVEL", "BEDROCK", "COBBLESTONE", "MOSSY_COBBLESTONE", "COBBLED_DEEPSLATE" -> return if (n.contains("DEEPSLATE")) DEEPSLATE else STONE
            "OBSIDIAN", "CRYING_OBSIDIAN", "BLACKSTONE", "BASALT", "SMOOTH_BASALT", "POLISHED_BASALT", "COAL_BLOCK",
            "POLISHED_BLACKSTONE", "GILDED_BLACKSTONE" -> return BLACK
            "SNOW", "SNOW_BLOCK", "POWDER_SNOW" -> return SNOW
            "ICE", "PACKED_ICE", "BLUE_ICE", "FROSTED_ICE" -> return ICE
            "LAVA", "MAGMA_BLOCK" -> return if (n == "LAVA") FIRE else NETHER
            "FIRE", "SOUL_FIRE", "TNT", "REDSTONE_BLOCK" -> return FIRE
            "COBWEB", "CAKE", "MUSHROOM_STEM" -> return WOOL
            "SPONGE", "WET_SPONGE", "HAY_BLOCK" -> return YELLOW
            "GLOWSTONE", "END_STONE", "END_STONE_BRICKS", "BONE_BLOCK", "SANDSTONE", "SMOOTH_SANDSTONE", "CUT_SANDSTONE",
            "CHISELED_SANDSTONE", "SAND" -> return SAND
            "PUMPKIN", "CARVED_PUMPKIN", "JACK_O_LANTERN", "HONEY_BLOCK", "SHROOMLIGHT" -> return ORANGE
            "MELON" -> return 19
            "RED_MUSHROOM_BLOCK", "BRICKS", "NETHER_WART_BLOCK", "RED_MUSHROOM" -> return RED
            "DIORITE", "POLISHED_DIORITE", "SEA_LANTERN" -> return QUARTZ
            "TUFF" -> return 43
            "CALCITE" -> return TERRACOTTA_WHITE
            "DRIPSTONE_BLOCK", "POINTED_DRIPSTONE" -> return 48
            "WARPED_WART_BLOCK" -> return WARPED_WART
            "PURPUR_BLOCK", "PURPUR_PILLAR", "PURPUR_STAIRS", "PURPUR_SLAB" -> return 16
            "RAW_IRON_BLOCK" -> return RAW_IRON
            "IRON_BLOCK", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "IRON_BARS", "IRON_DOOR", "IRON_TRAPDOOR", "LANTERN",
            "SOUL_LANTERN", "CHAIN", "BREWING_STAND" -> return METAL
            "GOLD_BLOCK", "RAW_GOLD_BLOCK", "LIGHT_WEIGHTED_PRESSURE_PLATE" -> return GOLD
            "DIAMOND_BLOCK", "BEACON", "PRISMARINE_BRICKS", "DARK_PRISMARINE" -> return DIAMOND
            "LAPIS_BLOCK" -> return LAPIS
            "EMERALD_BLOCK" -> return EMERALD
            "PRISMARINE" -> return 23
            "NETHERRACK", "NETHER_BRICKS", "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE", "NETHER_BRICK_FENCE",
            "NETHER_BRICK_STAIRS", "NETHER_BRICK_SLAB", "NETHER_BRICK_WALL", "CHISELED_NETHER_BRICKS", "CRACKED_NETHER_BRICKS" -> return NETHER
            "RED_NETHER_BRICKS" -> return RED
            "CRIMSON_NYLIUM" -> return CRIMSON_NYLIUM
            "WARPED_NYLIUM" -> return WARPED_NYLIUM
            "CHEST", "TRAPPED_CHEST", "CRAFTING_TABLE", "BOOKSHELF", "CHISELED_BOOKSHELF", "BARREL", "NOTE_BLOCK", "JUKEBOX",
            "LECTERN", "LOOM", "COMPOSTER", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "SMITHING_TABLE", "BEEHIVE", "BEE_NEST" -> return WOOD
        }
        if (n.contains("QUARTZ")) return QUARTZ
        if (n.contains("DEEPSLATE")) return DEEPSLATE
        if (n.contains("COPPER")) return when {
            n.contains("OXIDIZED") -> WARPED_NYLIUM
            n.contains("WEATHERED") -> WARPED_STEM
            n.contains("EXPOSED") -> 44
            else -> ORANGE
        }
        if (n.contains("BASALT") || n.contains("BLACKSTONE") || n.contains("OBSIDIAN")) return BLACK
        if (n.contains("NETHER_BRICK")) return NETHER
        if (n.contains("SANDSTONE") || n.endsWith("_SAND")) return SAND

        // ---- wood families (planks, logs, stairs, slabs, fences, doors, signs, ...)
        for ((w, color) in woods) {
            if (n.startsWith(w + "_")) {
                // crimson/warped stems and hyphae are "stem" coloured, their planks are not
                return if ((w == "CRIMSON" || w == "WARPED") && n.contains("PLANKS")) color + 1 else color
            }
        }

        // ---- everything else solid is stone-coloured, like vanilla's default
        return STONE
    }
}
