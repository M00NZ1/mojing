package com.mojing.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.navigation.NavBackStackEntry

object NavAnimations {
    fun enterTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(220)) + slideInHorizontally(tween(220, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { it / 18 }
    }

    fun exitTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(180)) + slideOutHorizontally(tween(180, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { -it / 36 }
    }

    fun popEnterTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(200)) + slideInHorizontally(tween(220, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { -it / 24 }
    }

    fun popExitTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(180)) + slideOutHorizontally(tween(200, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { it / 18 }
    }
}
