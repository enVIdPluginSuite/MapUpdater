package dev.etran.mapupdater

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.MapMeta

class MapUpdaterCommand(private val plugin: MapUpdaterPlugin) : CommandExecutor, TabCompleter {
    private val subs = listOf("help", "status", "reload", "refresh", "info", "track", "untrack", "config")

    private fun ok(s: CommandSender, m: String) = s.sendMessage("${ChatColor.GREEN}[MapUpdater] ${ChatColor.GRAY}$m")
    private fun err(s: CommandSender, m: String) = s.sendMessage("${ChatColor.RED}[MapUpdater] $m")
    private fun line(s: CommandSender, k: String, v: Any?) = s.sendMessage("  ${ChatColor.YELLOW}$k${ChatColor.GRAY}: ${ChatColor.WHITE}$v")

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mapupdater.admin")) { err(sender, "You do not have permission."); return true }
        when (args.firstOrNull()?.lowercase() ?: "help") {
            "help" -> help(sender, label)
            "status" -> status(sender)
            "reload" -> {
                plugin.reloadSettings()
                ok(sender, "Configuration reloaded and applied.")
            }
            "refresh" -> refresh(sender, args)
            "info" -> info(sender)
            "track" -> trackCmd(sender, args, true)
            "untrack" -> trackCmd(sender, args, false)
            "config" -> config(sender, args)
            else -> help(sender, label)
        }
        return true
    }

    private fun help(s: CommandSender, label: String) {
        ok(s, "Commands:")
        listOf(
            "status" to "show what the refresher is doing",
            "reload" to "reload config.yml",
            "refresh [mapId]" to "start a cycle now (all maps, or just one)",
            "info" to "details for the map you are holding",
            "track <mapId> / untrack <mapId>" to "manage the tracked map list",
            "config [key] [value]" to "view or change settings in-game"
        ).forEach { s.sendMessage("  ${ChatColor.YELLOW}/$label ${it.first}${ChatColor.GRAY} - ${it.second}") }
    }

    private fun status(s: CommandSender) {
        val e = plugin.engine
        val cfg = plugin.settings
        ok(s, "Status")
        line(s, "Enabled", cfg.enabled)
        line(s, "Tracked maps", plugin.tracker.size)
        line(s, "Server internals", when {
            !MapAccess.checked -> "not probed yet (checked on the first map)"
            MapAccess.supported -> "OK" + if (MapAccess.hasDirtyMethod) "" else " (no dirty flag: direct resend fallback)"
            else -> "UNSUPPORTED: ${MapAccess.failure}"
        })
        line(s, "Avg tick time", "%.1f ms%s".format(e.avgTickMs, if (e.lagPaused) "  (PAUSED by lag guard)" else ""))
        if (e.cycleActive) {
            line(s, "Cycle", "running: ${e.cycleMapsDone + e.cycleMapsSkipped}/${e.cycleTotal} maps, " +
                "${e.cycleChunks} chunks, ${e.cyclePixels} pixels changed" +
                (e.currentMapId?.let { ", now on #$it" } ?: ""))
        } else {
            val secs = ((e.nextCycleAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            line(s, "Next cycle", if (cfg.enabled) "in ${fmt(secs)}" else "disabled")
        }
        line(s, "Last cycle", e.lastSummary)
        if (e.lastSkipReasons.isNotEmpty()) {
            e.lastSkipReasons.entries.sortedByDescending { it.value }.take(5).forEach {
                s.sendMessage("    ${ChatColor.DARK_GRAY}skipped ${it.value}x: ${it.key}")
            }
        }
    }

    private fun fmt(secs: Long) = if (secs >= 3600) "${secs / 3600}h ${(secs % 3600) / 60}m" else if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"

    private fun refresh(s: CommandSender, args: Array<out String>) {
        if (args.size >= 2) {
            val id = args[1].toIntOrNull() ?: return err(s, "Map id must be a number.")
            plugin.tracker.track(id)
            plugin.engine.requestNow(listOf(id))
            ok(s, "Refreshing map #$id.")
        } else {
            val n = plugin.engine.requestNow()
            ok(s, "Refresh started for $n tracked maps (it works through them lazily; see /mapupdater status).")
        }
    }

    private fun info(s: CommandSender) {
        val p = s as? Player ?: return err(s, "Hold a map in-game to use this.")
        val item = p.inventory.itemInMainHand
        if (item.type != Material.FILLED_MAP) return err(s, "Hold a filled map in your main hand.")
        val view = try { (item.itemMeta as? MapMeta)?.mapView } catch (_: Throwable) { null }
            ?: return err(s, "That map has no data.")
        val id = Compat.id(view)
        ok(s, "Map #$id")
        line(s, "World", view.world?.name ?: "none")
        line(s, "Center", "${view.centerX}, ${view.centerZ}")
        line(s, "Scale", "${view.scale} (${1 shl view.scale.value.toInt()} blocks per pixel)")
        line(s, "Tracked", plugin.tracker.contains(id))
        val reason = plugin.engine.skipReason(view)
        line(s, "Will be refreshed", if (reason == null) "yes" else "no - $reason")
    }

    private fun trackCmd(s: CommandSender, args: Array<out String>, add: Boolean) {
        val id = args.getOrNull(1)?.toIntOrNull() ?: return err(s, "Usage: /mapupdater ${if (add) "track" else "untrack"} <mapId>")
        if (add) {
            if (plugin.tracker.view(id) == null) return err(s, "No map with id $id exists.")
            ok(s, if (plugin.tracker.track(id)) "Now tracking map #$id." else "Map #$id was already tracked.")
        } else {
            ok(s, if (plugin.tracker.untrack(id)) "Stopped tracking map #$id (it may be re-discovered when next seen)." else "Map #$id was not tracked.")
        }
    }

    private fun config(s: CommandSender, args: Array<out String>) {
        if (args.size == 1) {
            ok(s, "Settings (change with /mapupdater config <key> <value>):")
            for (k in ConfigSchema.keys) line(s, k.path, display(k))
            return
        }
        val key = ConfigSchema.find(args[1]) ?: return err(s, "Unknown setting '${args[1]}'. Use /mapupdater config to list them.")
        if (args.size == 2) {
            line(s, key.path, display(key))
            s.sendMessage("  ${ChatColor.DARK_GRAY}${key.help}")
            return
        }
        val raw = args.drop(2).joinToString(" ")
        val value = try { ConfigSchema.parse(key, raw) } catch (e: IllegalArgumentException) { return err(s, "${key.path}: ${e.message}") }
        plugin.config.set(key.path, value)
        plugin.saveConfig()
        plugin.applyConfig()
        ok(s, "${key.path} = ${display(key)}  (saved and applied)")
    }

    private fun display(k: ConfigSchema.Key): String = when (k.type) {
        ConfigSchema.Type.LIST -> plugin.config.getStringList(k.path).let { if (it.isEmpty()) "(none)" else it.joinToString(", ") }
        else -> plugin.config.get(k.path).toString()
    }

    override fun onTabComplete(sender: CommandSender, cmd: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("mapupdater.admin")) return emptyList()
        fun List<String>.starting(prefix: String) = filter { it.startsWith(prefix, ignoreCase = true) }
        return when (args.size) {
            1 -> subs.starting(args[0])
            2 -> when (args[0].lowercase()) {
                "config" -> ConfigSchema.keys.map { it.path }.starting(args[1])
                "untrack", "refresh" -> plugin.tracker.snapshot().map { it.toString() }.starting(args[1]).take(40)
                else -> emptyList()
            }
            3 -> if (args[0].equals("config", true)) {
                val key = ConfigSchema.find(args[1])
                when (key?.type) {
                    ConfigSchema.Type.BOOL -> listOf("true", "false").starting(args[2])
                    ConfigSchema.Type.LIST -> if (key.path == "disabled-worlds")
                        (Bukkit.getWorlds().map { it.name } + "none").starting(args[2]) else emptyList()
                    else -> emptyList()
                }
            } else emptyList()
            else -> emptyList()
        }
    }
}
