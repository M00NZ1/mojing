package com.mojing.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.navigation.NavBackStackEntry

object NavAnimations {
    fun enterTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(220)) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 12 }
    }

    fun exitTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -it / 24 }
    }

    fun popEnterTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(200)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 24 }
    }

    fun popExitTransition(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 12 }
    }
}
