package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.EncyclopediaLibraryItem
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class WorldLibraryDaoTest {
    @Test fun fullSetFilteringAllSortsAndLiveCountsUsePairedKeysets() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AppDatabase::class.java).build()
        try {
            val dao=db.encyclopediaDao()
            val worlds=(1..79).map { n ->
                val world=EncyclopediaEntity(name=if(n%3==0) "同名" else "海港${n%7}",
                    updatedAt=(n%4).toLong(),pinnedAt=if(n%5==0) 50 else 0,entryCount=999,
                    worldPrompt="large prompt ".repeat(3000))
                world.copy(id=dao.upsert(world))
            }
            for(sort in listOf("pinned","updated","name")) for(pinned in listOf(false,true)) for(query in listOf("","同名","海港6")) {
                var cursor:EncyclopediaLibraryItem?=null
                val seen=mutableListOf<Long>()
                do {
                    val page=dao.getLibraryPage(query,cursor?.let { if(sort=="pinned" && it.pinnedAt>0) 1 else 0 },
                        cursor?.let { if(sort=="pinned") it.pinnedAt else 0L },cursor?.updatedAt,cursor?.id,25,
                        pinned,sort,cursor?.let { if(sort=="name") it.name else "" })
                    assertTrue(page.all { it.preview.length<=160 && it.entryCount==0 })
                    seen+=page.take(24).map { it.id };cursor=page.take(24).lastOrNull()
                } while(page.size>24)
                val comparator=when(sort) {
                    "pinned" -> compareByDescending<EncyclopediaEntity> { it.pinnedAt>0 }.thenByDescending { it.pinnedAt }.thenByDescending { it.updatedAt }.thenByDescending { it.id }
                    "name" -> compareBy<EncyclopediaEntity> { it.name }.thenByDescending { it.updatedAt }.thenByDescending { it.id }
                    else -> compareByDescending<EncyclopediaEntity> { it.updatedAt }.thenByDescending { it.id }
                }
                val expected=worlds.filter { (!pinned || it.pinnedAt>0) && (query.isEmpty() || it.name.contains(query)) }.sortedWith(comparator).map { it.id }
                assertEquals("$sort/$pinned/$query",expected,seen);assertEquals(seen.size,seen.toSet().size)
            }
            val world=worlds.first()
            val entries=listOf("character","character","location","faction","world").map { type ->
                db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=world.id,entryType=type,content="body".repeat(5000)))
            }
            suspend fun counts()=dao.getLibraryPage("",null,null,null,null,100).first { it.id==world.id }
            val populated=counts();assertEquals(5,populated.entryCount);assertEquals(2,populated.characterCount)
            assertEquals(1,populated.locationCount);assertEquals(1,populated.factionCount)
            db.encyclopediaEntryDao().delete(entries.first())
            assertEquals(4,counts().entryCount);assertEquals(1,counts().characterCount)
            dao.updatePinned(world.id,90L,80L)
            assertTrue(dao.getLibraryPage("",null,null,null,null,25,true).any { it.id==world.id })
        } finally { db.close() }
    }
}
