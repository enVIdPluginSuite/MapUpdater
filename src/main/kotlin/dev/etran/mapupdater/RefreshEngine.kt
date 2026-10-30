package dev.etran.mapupdater

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.map.MapView
import org.bukkit.scheduler.BukkitTask

/**
 * Lazy map refresher.
 *
 * Once per cycle every tracked map is queued. Each server tick the engine then does a *tiny*
 * slice of work (a time budget and a chunk cap, both configurable): it takes the current map,
 * walks the chunks that map covers one at a time, and re-draws the map's pixels from the terrain.
 * Chunks with nothing to refresh are skipped without being touched, chunks that were never
 * generated are never generated, and the whole thing stops while the server is struggling.
 */
class RefreshEngine(private val plugin: MapUpdaterPlugin) {
    private enum class Step { WORK, FREE, DONE }

    private val queue = ArrayDeque<Int>()
    private var job: Job? = null
    private var task: BukkitTask? = null

    // ---- state shown by /mapupdater status
    @Volatile var cycleActive = false; private set
    @Volatile var nextCycleAtMs = 0L; private set
    @Volatile var avgTickMs = 50.0; private set
    var lagPaused = false; private set
    var cycleTotal = 0; private set
    var cycleMapsDone = 0; private set
    var cycleMapsSkipped = 0; private set
    var cycleChunks = 0; private set
    var cyclePixels = 0; private set
    var lastSummary = "no cycle has run yet"; private set
    var lastSkipReasons: Map<String, Int> = emptyMap(); private set
    private var skipReasons = HashMap<String, Int>()
    private var cycleStartMs = 0L
    private var lastTickNs = 0L

    fun start() {
        stop()
        nextCycleAtMs = System.currentTimeMillis() + plugin.settings.startDelaySeconds * 1000L
        lastTickNs = 0L
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    fun stop() {
        task?.cancel()
        task = null
        queue.clear()
        job = null
        cycleActive = false
    }

    /** Called after a config change/reload so a shorter interval takes effect without waiting. */
    fun settingsChanged() {
        if (!cycleActive) {
            val soonest = System.currentTimeMillis() + plugin.settings.refreshIntervalMinutes * 60_000L
            if (nextCycleAtMs > soonest) nextCycleAtMs = soonest
        }
    }

    /** Starts a cycle right now for [ids] (or every tracked map). Returns how many maps were queued. */
    fun requestNow(ids: Collection<Int>? = null): Int {
        beginCycle(ids ?: plugin.tracker.snapshot())
        return queue.size
    }

    val queued: Int get() = queue.size + (if (job != null) 1 else 0)
    val currentMapId: Int? get() = job?.id

    private fun beginCycle(ids: Collection<Int>) {
        queue.clear()
        job = null
        queue.addAll(ids)
        cycleTotal = queue.size
        cycleMapsDone = 0; cycleMapsSkipped = 0; cycleChunks = 0; cyclePixels = 0
        skipReasons = HashMap()
        cycleStartMs = System.currentTimeMillis()
        cycleActive = queue.isNotEmpty()
        if (!cycleActive) scheduleNext()
    }

    private fun scheduleNext() {
        nextCycleAtMs = System.currentTimeMillis() + plugin.settings.refreshIntervalMinutes * 60_000L
    }

    private fun finishCycle() {
        cycleActive = false
        lastSkipReasons = skipReasons
        val secs = (System.currentTimeMillis() - cycleStartMs) / 1000
        lastSummary = "$cycleMapsDone refreshed, $cycleMapsSkipped skipped, $cycleChunks chunks sampled, " +
            "$cyclePixels pixels changed, took ${secs}s"
        if (cyclePixels > 0 || plugin.settings.debug) plugin.logger.info("Refresh cycle finished: $lastSummary")
        scheduleNext()
        plugin.tracker.saveIfDirty()
    }

    // ---- per tick ----------------------------------------------------------------------------------------------

    private fun tick() {
        val nowNs = System.nanoTime()
        if (lastTickNs != 0L) {
            val delta = (nowNs - lastTickNs) / 1_000_000.0
            avgTickMs = avgTickMs * 0.97 + delta * 0.03
        }
        lastTickNs = nowNs

        val s = plugin.settings
        if (!s.enabled) return

        if (!cycleActive) {
            if (System.currentTimeMillis() < nextCycleAtMs) return
            beginCycle(plugin.tracker.snapshot())
            if (!cycleActive) return
        }

        lagPaused = s.pauseAboveAvgTickMs > 0.0 && avgTickMs > s.pauseAboveAvgTickMs
        if (lagPaused) return

        val deadline = nowNs + (s.maxMsPerTick * 1_000_000.0).toLong()
        var budget = s.maxChunksPerTick
        try {
            while (budget > 0 && System.nanoTime() < deadline) {
                val j = job ?: nextJob()
                if (j == null) { finishCycle(); return }
                job = j
                when (j.step()) {
                    Step.WORK -> budget--
                    Step.FREE -> {}
                    Step.DONE -> { finishJob(j); job = null }
                }
            }
        } catch (t: Throwable) {
            // One misbehaving map must never take the whole refresher down.
            plugin.logger.warning("Map #${job?.id} failed and was skipped: $t")
            cycleMapsSkipped++
            job = null
        }
    }

    private fun nextJob(): Job? {
        while (true) {
            val id = queue.removeFirstOrNull() ?: return null
            val view = plugin.tracker.view(id)
            if (view == null) { skip("map no longer exists"); continue }
            val reason = skipReason(view)
            if (reason != null) { skip(reason); continue }
            return Job(id, view, view.world!!)
        }
    }

    private fun skip(reason: String) {
        cycleMapsSkipped++
        skipReasons[reason] = (skipReasons[reason] ?: 0) + 1
    }

    private fun finishJob(j: Job) {
        cycleMapsDone++
        cyclePixels += j.changed
        cycleChunks += j.chunksSampled
        if (!MapAccess.hasDirtyMethod && j.changed > 0) {
            // No way to flag the data dirty, so push it to anyone looking at it directly.
            for (p in Bukkit.getOnlinePlayers()) {
                try { p.sendMap(j.view) } catch (_: Throwable) { /* ignore */ }
            }
        }
        if (plugin.settings.debug) plugin.logger.info("Map #${j.id}: ${j.changed} pixels changed, ${j.chunksSampled} chunks sampled")
    }

    /** Why a map must not be touched, or null if it is fair game. Also used by /mapupdater info. */
    fun skipReason(view: MapView): String? {
        val s = plugin.settings
        MapAccess.ensureChecked(view)
        if (!MapAccess.supported) return "server internals not supported (${MapAccess.failure})"
        if (Compat.isVirtual(view)) return "virtual map"
        val world = view.world ?: return "no world / terrain to read"
        if (s.isWorldDisabled(world.name)) return "world disabled in config"
        if (s.skipCeilingWorlds && Compat.hasCeiling(world)) return "world has a ceiling (Nether)"
        if (s.skipLockedMaps && Compat.isLocked(view)) return "map is locked"
        if (s.skipCustomRendererMaps) {
            val renderers = view.renderers
            if (renderers.isEmpty()) return "no vanilla renderer (used by another plugin)"
            val foreign = renderers.firstOrNull { !it.javaClass.name.startsWith("org.bukkit.craftbukkit") }
            if (foreign != null) return "custom renderer from another plugin (${foreign.javaClass.name})"
        }
        if (MapAccess.colors(view) == null) return "map data unavailable"
        return null
    }

    // ---- one map ------------------------------------------------------------------------------------------------

    private inner class Job(val id: Int, val view: MapView, val world: World) {
        private val scale = view.scale.value.toInt().coerceIn(0, 4)
        private val bpp = 1 shl scale                        // blocks per pixel
        private val sx0 = view.centerX - 64 * bpp + bpp / 2  // block x sampled by pixel column 0
        private val sz0 = view.centerZ - 64 * bpp + bpp / 2
        private val cxMin = Math.floorDiv(sx0, 16)
        private val czMin = Math.floorDiv(sz0, 16)
        private val cxCount = Math.floorDiv(sx0 + 127 * bpp, 16) - cxMin + 1
        private val czCount = Math.floorDiv(sz0 + 127 * bpp, 16) - czMin + 1
        private val total = cxCount.toLong() * czCount
        private val minY = Compat.minHeight(world)
        private val fill = plugin.settings.fillUnexplored
        private val load = plugin.settings.loadGeneratedChunks
        private val heights = IntArray(128 * 128) { TerrainSampler.NO_HEIGHT }
        private val col = Column()
        private var index = 0L

        var changed = 0; private set
        var chunksSampled = 0; private set

        fun step(): Step {
            if (index >= total) return Step.DONE
            val cx = cxMin + (index % cxCount).toInt()
            val cz = czMin + (index / cxCount).toInt()
            index++

            // Which pixels sample a block inside this chunk?
            val pxLo = maxOf(0, Math.floorDiv(cx * 16 - sx0 + bpp - 1, bpp))
            val pxHi = minOf(127, Math.floorDiv(cx * 16 + 15 - sx0, bpp))
            val pzLo = maxOf(0, Math.floorDiv(cz * 16 - sz0 + bpp - 1, bpp))
            val pzHi = minOf(127, Math.floorDiv(cz * 16 + 15 - sz0, bpp))
            if (pxLo > pxHi || pzLo > pzHi) return Step.FREE

            // Re-read the pixel array every time: the server may swap it if the map was reloaded.
            val colors = MapAccess.colors(view) ?: return Step.DONE

            if (!fill) {
                var any = false
                loop@ for (pz in pzLo..pzHi) for (px in pxLo..pxHi) {
                    if (colors[pz * 128 + px].toInt() != 0) { any = true; break@loop }
                }
                if (!any) return Step.FREE // nothing explored here: do not even load the chunk
            }

            val snap = TerrainSampler.snapshot(world, cx, cz, load) ?: return Step.WORK // no terrain: skipped
            chunksSampled++

            var minX = 128; var minZ = 128; var maxX = -1; var maxZ = -1
            for (pz in pzLo..pzHi) {
                val lz = (sz0 + pz * bpp) and 15
                for (px in pxLo..pxHi) {
                    val idx = pz * 128 + px
                    val existing = colors[idx]
                    if (!fill && existing.toInt() == 0) continue
                    val lx = (sx0 + px * bpp) and 15
                    TerrainSampler.sampleColumn(snap, lx, lz, minY, col)
                    heights[idx] = col.height
                    if (col.base == MapColors.NONE) continue // void: leave the old pixel
                    val north = if (pz > 0) heights[idx - 128] else TerrainSampler.NO_HEIGHT
                    val shade = TerrainSampler.shade(col, north, px, pz, bpp)
                    val color = (col.base * 4 + shade).toByte()
                    if (color != existing) {
                        colors[idx] = color
                        changed++
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (pz < minZ) minZ = pz
                        if (pz > maxZ) maxZ = pz
                    }
                }
            }
            if (maxX >= 0) {
                MapAccess.markDirty(view, minX, minZ)
                MapAccess.markDirty(view, maxX, maxZ)
            }
            return Step.WORK
        }
    }
}
