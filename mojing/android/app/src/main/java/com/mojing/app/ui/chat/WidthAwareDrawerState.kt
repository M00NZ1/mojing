package com.mojing.app.ui.chat

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** Keep semantic open/closed intent when a full-width drawer gets new pixel anchors. */
internal class WidthAwareDrawerState(initialValue: DrawerValue = DrawerValue.Closed) {
    private var width: Int? = null
    private var state = DrawerState(initialValue)

    fun forWidth(widthDp: Int): DrawerState {
        if (width != null && width != widthDp) {
            // Reusing the old offset lets updateAnchors choose the closest *new* anchor,
            // which can turn a closed portrait drawer into an open landscape drawer.
            state = DrawerState(state.targetValue)
        }
        width = widthDp
        return state
    }

    companion object {
        val saver = Saver<WidthAwareDrawerState, DrawerValue>(
            save = { it.state.targetValue },
            restore = { WidthAwareDrawerState(it) },
        )
    }
}

@Composable
internal fun rememberWidthAwareDrawerState(widthDp: Int): DrawerState =
    rememberSaveable(saver = WidthAwareDrawerState.saver) { WidthAwareDrawerState() }
        .forWidth(widthDp)
