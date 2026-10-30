package dev.etran.mapupdater

import org.bukkit.Bukkit
import org.bukkit.entity.ItemFrame
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class MapUpdaterPlugin : JavaPlugin() {
    lateinit var settings: Settings; private set
    lateinit var tracker: MapTracker; private set
    lateinit var engine: RefreshEngine; private set

    private var scanTask: BukkitTask? = null
    private var saveTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        settings = Settings(config)
        MapColors.configure(settings.useServerMapColors)

        tracker = MapTracker(this)
        tracker.load()
        engine = RefreshEngine(this)

        MapListener(this).register()

        val cmd = MapUpdaterCommand(this)
        getCommand("mapupdater")?.let {
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        // Pick up maps that are already out in the world.
        Bukkit.getScheduler().runTask(this, Runnable { initialDiscovery() })

        engine.start()
        startBackgroundTasks()
        logger.info("MapUpdater enabled: tracking ${tracker.size} known maps.")
    }

    override fun onDisable() {
        scanTask?.cancel(); saveTask?.cancel()
        scanTask = null; saveTask = null
        engine.stop()
        tracker.saveIfDirty()
    }

    /** Re-reads config.yml and applies it live. */
    fun reloadSettings() {
        reloadConfig()
        applyConfig()
    }

    /** Rebuilds [settings] from the in-memory config (after reload or an in-game edit). */
    fun applyConfig() {
        settings = Settings(config)
        MapColors.configure(settings.useServerMapColors)
        engine.settingsChanged()
        startBackgroundTasks()
    }

    private fun startBackgroundTasks() {
        scanTask?.cancel()
        saveTask?.cancel()
        val period = settings.inventoryScanSeconds * 20L
        scanTask = Bukkit.getScheduler().runTaskTimer(this, Runnable {
            if (settings.scanPlayerInventories) for (p in Bukkit.getOnlinePlayers()) tracker.scanPlayer(p)
        }, period, period)
        saveTask = Bukkit.getScheduler().runTaskTimer(this, Runnable { tracker.saveIfDirty() }, 6000L, 6000L)
    }

    private fun initialDiscovery() {
        if (settings.scanPlayerInventories) for (p in Bukkit.getOnlinePlayers()) tracker.scanPlayer(p)
        if (settings.scanItemFrames) {
            for (w in Bukkit.getWorlds()) {
                try {
                    for (f in w.getEntitiesByClass(ItemFrame::class.java)) tracker.scanFrame(f)
                } catch (_: Throwable) { /* ignore */ }
            }
        }
    }
}
