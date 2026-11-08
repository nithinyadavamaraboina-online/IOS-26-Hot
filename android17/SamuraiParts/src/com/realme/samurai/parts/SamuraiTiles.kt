package com.realme.samurai.parts

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DcDimmingTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val p = SamuraiHardwareBridge.prefs(this)
        val next = !p.getBoolean(SamuraiHardwareBridge.KEY_DC_DIM, false)
        p.edit().putBoolean(SamuraiHardwareBridge.KEY_DC_DIM, next).apply()
        SamuraiHardwareBridge.sendCommand("DC_DIM", next)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = SamuraiHardwareBridge.prefs(this).getBoolean(SamuraiHardwareBridge.KEY_DC_DIM, false)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "Anti-flicker on" else "Off"
        tile.updateTile()
    }
}

class HbmTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val p = SamuraiHardwareBridge.prefs(this)
        val cur = SamuraiHardwareBridge.isHbmHardwareActive(this)
        val next = !cur
        p.edit().putBoolean(SamuraiHardwareBridge.KEY_HBM, next).apply()
        SamuraiHardwareBridge.sendCommand("HBM", next)
        val tile = qsTile
        if (tile != null) {
            tile.state = if (next) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = if (next) "1000 nits" else "Standard"
            tile.updateTile()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val p = SamuraiHardwareBridge.prefs(this)
        val on = SamuraiHardwareBridge.isHbmHardwareActive(this)
        if (!on && p.getBoolean(SamuraiHardwareBridge.KEY_HBM, false)) {
            p.edit().putBoolean(SamuraiHardwareBridge.KEY_HBM, false).apply()
        }
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "1000 nits" else "Standard"
        tile.updateTile()
    }
}

class RefreshRateTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val p = SamuraiHardwareBridge.prefs(this)
        val next = (p.getInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, 0) + 1) % 3
        p.edit()
            .putInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, next)
            .putInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, -1)
            .apply()
        SamuraiHardwareBridge.applyRefreshRateMode(this, next)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val mode = SamuraiHardwareBridge.prefs(this).getInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, 0)
        tile.state = if (mode == 2) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = when (mode) {
            1 -> "Adaptive 90Hz"
            2 -> "60Hz Saver"
            else -> "Smooth 90Hz"
        }
        tile.updateTile()
    }
}

class PerfModeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val p = SamuraiHardwareBridge.prefs(this)
        val next = (p.getInt(SamuraiHardwareBridge.KEY_PERF_PROFILE, 0) + 1) % 3
        p.edit().putInt(SamuraiHardwareBridge.KEY_PERF_PROFILE, next).apply()
        SamuraiHardwareBridge.sendCommand("PERF", next)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val mode = SamuraiHardwareBridge.prefs(this).getInt(SamuraiHardwareBridge.KEY_PERF_PROFILE, 0)
        tile.state = if (mode == 1) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when (mode) {
            1 -> "Gaming 2.96GHz"
            2 -> "Efficiency"
            else -> "Balanced 855+"
        }
        tile.updateTile()
    }
}

class SmartChargeTileService : TileService() {
    private val steps = intArrayOf(100, 80, 85, 90)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val p = SamuraiHardwareBridge.prefs(this)
        val cur = p.getInt(SamuraiHardwareBridge.KEY_CHG_LIMIT, 100)
        val idx = steps.indexOf(cur).let { if (it < 0) 0 else it }
        val next = steps[(idx + 1) % steps.size]
        p.edit().putInt(SamuraiHardwareBridge.KEY_CHG_LIMIT, next).apply()
        SamuraiHardwareBridge.sendCommand("CHG_LIMIT", next)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val limit = SamuraiHardwareBridge.prefs(this).getInt(SamuraiHardwareBridge.KEY_CHG_LIMIT, 100)
        tile.state = if (limit < 100) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (limit >= 100) "100% (50W)" else "Stop at $limit%"
        tile.updateTile()
    }
}

class OtgTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val p = SamuraiHardwareBridge.prefs(this)
        val next = !p.getBoolean(SamuraiHardwareBridge.KEY_OTG, false)
        p.edit().putBoolean(SamuraiHardwareBridge.KEY_OTG, next).apply()
        SamuraiHardwareBridge.sendCommand("OTG", next)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = SamuraiHardwareBridge.prefs(this).getBoolean(SamuraiHardwareBridge.KEY_OTG, false)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "Powered" else "Off"
        tile.updateTile()
    }
}
