package com.nextmovie.ui.player.engine

enum class PlayerEngineType(val wire: String, val label: String) {
    EXO("exo", "ExoPlayer"),
    IJK("ijk", "IJK（可选）"),
    MPV("mpv", "MPV（可选）");

    companion object {
        fun fromWire(value: String?): PlayerEngineType =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: EXO
    }
}
