package dev.etran.mapupdater

import org.bukkit.configuration.file.FileConfiguration

/** Immutable snapshot of config.yml, validated and clamped. Rebuilt on every reload / config change. */
class Settings(c: FileConfiguration) {
    val enabled = c.getBoolean("enabled", true)
    val refreshIntervalMinutes = c.getInt("refresh-interval-minutes", 30).coerceIn(1, 10_080)
    val fillUnexplored = c.getBoolean("fill-unexplored", false)
    val skipLockedMaps = c.getBoolean("skip-locked-maps", true)
    val skipCustomRendererMaps = c.getBoolean("skip-custom-renderer-maps", true)
    val skipCeilingWorlds = c.getBoolean("skip-ceiling-worlds", true)
    val disabledWorlds: Set<String> = c.getStringList("disabled-worlds").map { it.trim().lowercase() }.toSet()

    val maxMsPerTick = c.getDouble("performance.max-ms-per-tick", 2.0).coerceIn(0.1, 40.0)
    val maxChunksPerTick = c.getInt("performance.max-chunks-per-tick", 3).coerceIn(1, 256)
    val loadGeneratedChunks = c.getBoolean("performance.load-generated-chunks", true)
    val pauseAboveAvgTickMs = c.getDouble("performance.pause-above-avg-tick-ms", 55.0).coerceIn(0.0, 1000.0)
    val startDelaySeconds = c.getInt("performance.start-delay-seconds", 60).coerceIn(0, 3600)

    val scanPlayerInventories = c.getBoolean("discovery.scan-player-inventories", true)
    val scanItemFrames = c.getBoolean("discovery.scan-item-frames", true)
    val inventoryScanSeconds = c.getInt("discovery.inventory-scan-interval-seconds", 60).coerceIn(5, 3600)

    val useServerMapColors = c.getBoolean("colors.use-server-map-colors", true)
    val debug = c.getBoolean("debug", false)

    fun isWorldDisabled(name: String) = name.lowercase() in disabledWorlds
}

/** Describes every key that may be edited with /mapupdater config, for validation and tab completion. */
object ConfigSchema {
    enum class Type { BOOL, INT, DOUBLE, LIST }

    class Key(val path: String, val type: Type, val min: Double = 0.0, val max: Double = 0.0, val help: String)

    val keys: List<Key> = listOf(
        Key("enabled", Type.BOOL, help = "Master switch"),
        Key("refresh-interval-minutes", Type.INT, 1.0, 10080.0, "Minutes between refresh cycles"),
        Key("fill-unexplored", Type.BOOL, help = "Also fill blank map pixels"),
        Key("skip-locked-maps", Type.BOOL, help = "Leave locked maps alone"),
        Key("skip-custom-renderer-maps", Type.BOOL, help = "Leave maps used by other plugins alone"),
        Key("skip-ceiling-worlds", Type.BOOL, help = "Skip the Nether"),
        Key("disabled-worlds", Type.LIST, help = "Comma separated world names, or 'none'"),
        Key("performance.max-ms-per-tick", Type.DOUBLE, 0.1, 40.0, "Time budget per tick (ms)"),
        Key("performance.max-chunks-per-tick", Type.INT, 1.0, 256.0, "Chunks sampled per tick"),
        Key("performance.load-generated-chunks", Type.BOOL, help = "Load generated-but-unloaded chunks"),
        Key("performance.pause-above-avg-tick-ms", Type.DOUBLE, 0.0, 1000.0, "Lag guard threshold (0 = off)"),
        Key("performance.start-delay-seconds", Type.INT, 0.0, 3600.0, "Delay before the first cycle"),
        Key("discovery.scan-player-inventories", Type.BOOL, help = "Find maps in player inventories"),
        Key("discovery.scan-item-frames", Type.BOOL, help = "Find maps in item frames"),
        Key("discovery.inventory-scan-interval-seconds", Type.INT, 5.0, 3600.0, "Inventory re-scan period"),
        Key("colors.use-server-map-colors", Type.BOOL, help = "Prefer the server's block map colors"),
        Key("debug", Type.BOOL, help = "Extra console logging")
    )

    fun find(path: String): Key? = keys.firstOrNull { it.path.equals(path, ignoreCase = true) }

    /** Parses [raw] for [key]. Returns the value to store, or throws IllegalArgumentException with a user-facing message. */
    fun parse(key: Key, raw: String): Any = when (key.type) {
        Type.BOOL -> when (raw.lowercase()) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> throw IllegalArgumentException("Expected true or false.")
        }
        Type.INT -> {
            val v = raw.toIntOrNull() ?: throw IllegalArgumentException("Expected a whole number.")
            if (v < key.min || v > key.max) throw IllegalArgumentException("Must be between ${key.min.toInt()} and ${key.max.toInt()}.")
            v
        }
        Type.DOUBLE -> {
            val v = raw.toDoubleOrNull() ?: throw IllegalArgumentException("Expected a number.")
            if (v < key.min || v > key.max) throw IllegalArgumentException("Must be between ${key.min} and ${key.max}.")
            v
        }
        Type.LIST ->
            if (raw.equals("none", true) || raw.equals("clear", true)) emptyList<String>()
            else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
