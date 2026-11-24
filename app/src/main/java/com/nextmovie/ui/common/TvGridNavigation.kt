package com.nextmovie.ui.common

/** Explicit row/column navigation; virtualized grids must not jump to column zero on Down. */
object TvGridNavigation {
    fun columns(widthDp: Float): Int = ((widthDp + 16f) / (128f + 16f)).toInt().coerceAtLeast(1)
    fun target(index: Int, columns: Int, count: Int, keyCode: Int): Int? {
        if (count <= 0 || columns <= 0) return null
        return when (keyCode) {
            19 -> if (index < columns) null else index - columns
            20 -> if (index / columns >= (count - 1) / columns) index else minOf(index + columns, count - 1)
            21 -> if (index % columns == 0) index else index - 1
            22 -> if (index % columns == columns - 1 || index == count - 1) index else index + 1
            else -> null
        }
    }
}
