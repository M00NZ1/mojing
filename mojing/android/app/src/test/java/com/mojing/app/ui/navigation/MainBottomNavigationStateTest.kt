package com.mojing.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBottomNavigationStateTest {

    @Test
    fun rootRoutesKeepBottomNavigationVisible() {
        assertTrue(shouldShowMainBottomNavigation(Routes.SESSION_LIST))
        assertTrue(shouldShowMainBottomNavigation(Routes.CREATION_HUB))
        assertTrue(shouldShowMainBottomNavigation(Routes.CHARACTER_LIST))
        assertTrue(shouldShowMainBottomNavigation(Routes.ENCYCLOPEDIA_LIST))
        assertTrue(shouldShowMainBottomNavigation(Routes.WORKBENCH))
        assertTrue(shouldShowMainBottomNavigation(Routes.STORY_SIMULATION))
        assertTrue(shouldShowMainBottomNavigation(Routes.SETTINGS))
    }

    @Test
    fun detailAndEditRoutesHideBottomNavigation() {
        assertFalse(shouldShowMainBottomNavigation(Routes.chat(1L)))
        assertFalse(shouldShowMainBottomNavigation(Routes.characterEdit(1L)))
        assertFalse(shouldShowMainBottomNavigation(Routes.encyclopediaDetail(1L)))
        assertFalse(shouldShowMainBottomNavigation(Routes.entryEdit(1L, 2L)))
        assertFalse(shouldShowMainBottomNavigation(Routes.templateEdit(1L)))
        assertFalse(shouldShowMainBottomNavigation(Routes.GENERATION_TASKS))
    }

    @Test
    fun detailRoutesStillMapToTheirRootTab() {
        assertEquals(MainNavTab.Session, routeToMainNavTab(Routes.chat(1L)))
        assertEquals(MainNavTab.Create, routeToMainNavTab(Routes.CREATION_HUB))
        assertEquals(MainNavTab.Create, routeToMainNavTab(Routes.characterEdit(1L)))
        assertEquals(MainNavTab.Create, routeToMainNavTab(Routes.encyclopediaDetail(1L)))
        assertEquals(MainNavTab.Create, routeToMainNavTab(Routes.templateEdit(1L)))
        assertEquals(MainNavTab.Create, routeToMainNavTab(Routes.STORY_SIMULATION))
        assertEquals(MainNavTab.Settings, routeToMainNavTab(Routes.SETTINGS))
    }

    @Test
    fun routeIdsRejectMissingAndInvalidValuesWithoutBreakingNewItemRoutes() {
        assertEquals(null, validatedRouteId(null))
        assertEquals(null, validatedRouteId(0L))
        assertEquals(null, validatedRouteId(-1L))
        assertEquals(7L, validatedRouteId(7L))

        assertEquals(null, validatedRouteId(null, allowZero = true))
        assertEquals(null, validatedRouteId(-1L, allowZero = true))
        assertEquals(0L, validatedRouteId(0L, allowZero = true))
        assertEquals(7L, validatedRouteId(7L, allowZero = true))
    }
}
