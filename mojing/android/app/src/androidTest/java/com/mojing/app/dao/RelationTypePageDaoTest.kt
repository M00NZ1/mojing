package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.EntryRelationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelationTypePageDaoTest {
    @Test fun eitherEndpointWorldScopeBoundariesAndCursorUseActualRoomSql() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AppDatabase::class.java).build()
        try {
            val entries=db.encyclopediaEntryDao();val dao=db.entryRelationDao()
            for(count in listOf(0,1,23,24,25)) {
                val world=count.toLong()+1
                db.encyclopediaDao().upsert(EncyclopediaEntity(id=world,name="边界$count"))
                val char=entries.upsert(EncyclopediaEntryEntity(encyclopediaId=world,entryType="character"))
                val place=entries.upsert(EncyclopediaEntryEntity(encyclopediaId=world,entryType="location"))
                val expected=mutableListOf<Long>()
                repeat(count) { i -> expected+=dao.upsert(EntryRelationEntity(encyclopediaId=world,
                    fromEntryId=if(i%2==0)char else place,toEntryId=if(i%2==0)place else char,relationType="守护")) }
                // Newer nonmatching edges must not consume the first 24 matching slots.
                repeat(28) { dao.upsert(EntryRelationEntity(encyclopediaId=world,fromEntryId=char,toEntryId=char)) }
                val first=dao.getTypePage(world,Long.MAX_VALUE,"location",25)
                assertEquals(expected.reversed(),first.map { it.id })
                if(count==25) assertEquals(expected.take(1),dao.getTypePage(world,first[23].id,"location",25).map { it.id })
                assertTrue(dao.getTypePage(world,Long.MAX_VALUE,"faction",25).isEmpty())
            }
            db.encyclopediaDao().upsert(EncyclopediaEntity(id=100,name="完整世界"))
            db.encyclopediaDao().upsert(EncyclopediaEntity(id=200,name="异世界"))
            val a=entries.upsert(EncyclopediaEntryEntity(encyclopediaId=100,entryType="location",content="大正文".repeat(10000)))
            val b=entries.upsert(EncyclopediaEntryEntity(encyclopediaId=100,entryType="location"))
            val other=entries.upsert(EncyclopediaEntryEntity(encyclopediaId=200,entryType="faction"))
            val double=dao.upsert(EntryRelationEntity(encyclopediaId=100,fromEntryId=a,toEntryId=b))
            dao.upsert(EntryRelationEntity(encyclopediaId=200,fromEntryId=a,toEntryId=b))
            dao.upsert(EntryRelationEntity(encyclopediaId=100,fromEntryId=a,toEntryId=other))
            assertEquals(listOf(double),dao.getTypePage(100,Long.MAX_VALUE,"location",25).map { it.id })
            assertTrue(dao.getTypePage(100,Long.MAX_VALUE,"faction",25).isEmpty())
            val expected=(1..65).map { dao.upsert(EntryRelationEntity(encyclopediaId=100,fromEntryId=a,toEntryId=b)) }.reversed()
            val seen=mutableListOf<Long>();var cursor=Long.MAX_VALUE
            while(true) {
                val rows=dao.getTypePage(100,cursor,"location",25);seen+=rows.take(24).map { it.id }
                if(rows.size<=24)break
                cursor=rows[23].id
            }
            assertEquals(expected,seen.take(65));assertEquals(seen.size,seen.toSet().size)
            assertEquals("大正文".repeat(10000),entries.getById(a)?.content)
        } finally { db.close() }
    }
}
