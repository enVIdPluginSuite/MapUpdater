package dev.etran.mapupdater

import org.bukkit.ChunkSnapshot
import org.bukkit.World

/** One sampled world column: its map base colour, surface height and (for water) depth. */
class Column {
    @JvmField var base = 0
    @JvmField var height = 0
    @JvmField var waterDepth = 0
}

object TerrainSampler {
    const val NO_HEIGHT = Int.MIN_VALUE
    private const val MAX_WATER_DEPTH = 128

    /**
     * Returns a snapshot of chunk ([cx],[cz]) or null when there is no terrain to read.
     *
     * Never generates a chunk. A generated-but-unloaded chunk is loaded only if [allowLoad],
     * and released again immediately afterwards (unless a player or plugin is using it).
     */
    fun snapshot(world: World, cx: Int, cz: Int, allowLoad: Boolean): ChunkSnapshot? {
        var loadedByUs = false
        if (!world.isChunkLoaded(cx, cz)) {
            if (!allowLoad) return null
            // generate=false: returns false for chunks that were never generated
            if (!world.loadChunk(cx, cz, false)) return null
            loadedByUs = true
        }
        val snap = try {
            world.getChunkAt(cx, cz).getChunkSnapshot(true, false, false)
        } catch (_: Throwable) {
            null
        }
        if (loadedByUs) {
            try { world.unloadChunkRequest(cx, cz) } catch (_: Throwable) { /* server will unload it on its own */ }
        }
        return snap
    }

    /** Samples the top visible block of column ([lx],[lz]) (0..15) of [snap] into [out]. */
    fun sampleColumn(snap: ChunkSnapshot, lx: Int, lz: Int, minY: Int, out: Column) {
        var y = snap.getHighestBlockYAt(lx, lz)
        out.base = MapColors.NONE
        out.height = minY
        out.waterDepth = 0
        while (y >= minY) {
            val m = snap.getBlockType(lx, y, lz)
            if (MapColors.isWater(m)) {
                var depth = 1
                var yy = y - 1
                while (yy >= minY && depth < MAX_WATER_DEPTH && MapColors.isWater(snap.getBlockType(lx, yy, lz))) {
                    depth++; yy--
                }
                out.base = MapColors.WATER
                out.height = y
                out.waterDepth = depth
                return
            }
            val base = MapColors.base(m)
            if (base != MapColors.NONE) {
                out.base = base
                out.height = y
                return
            }
            y--
        }
    }

    /**
     * Vanilla's brightness rule. Returns 0..2 (0 = darkest, 1 = normal, 2 = brightest).
     * [northHeight] is the surface height of the pixel above this one, or NO_HEIGHT if unknown.
     */
    fun shade(col: Column, northHeight: Int, px: Int, pz: Int, blocksPerPixel: Int): Int {
        val checker = (px + pz) and 1
        if (col.base == MapColors.WATER) {
            val d = col.waterDepth * 0.1 + checker * 0.2
            return if (d < 0.5) 2 else if (d > 0.9) 0 else 1
        }
        val north = if (northHeight == NO_HEIGHT) col.height else northHeight
        val d = (col.height - north) * 4.0 / (blocksPerPixel + 4) + (checker - 0.5) * 0.4
        return if (d > 0.6) 2 else if (d < -0.6) 0 else 1
    }
}
