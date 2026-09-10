package com.mojing.app.ui.session

/** Null selection means the dialog has not received its first successful load. */
internal fun openingCharacterSelection(availableIds: Set<Long>, selectedIds: Set<Long>?): Set<Long> =
    if (selectedIds == null) availableIds.takeIf { it.size == 1 }.orEmpty()
    else selectedIds.intersect(availableIds)
