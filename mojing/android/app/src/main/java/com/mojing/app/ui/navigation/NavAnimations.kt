package com.mojing.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.navigation.NavBackStackEntry

object NavAnimations {
    private val roots = setOf(Routes.SESSION_LIST, Routes.CREATION_HUB, Routes.SETTINGS)
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenRoots() =
        initialState.destination.route in roots && targetState.destination.route in roots

    fun enterTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (betweenRoots()) EnterTransition.None else slideInHorizontally(tween(220, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { it / 18 }
    }

    fun exitTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        ExitTransition.None
    }

    fun popEnterTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        EnterTransition.None
    }

    fun popExitTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (betweenRoots()) ExitTransition.None else slideOutHorizontally(tween(200, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { it / 18 }
    }
}
