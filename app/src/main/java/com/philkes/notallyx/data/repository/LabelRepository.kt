package com.philkes.notallyx.data.repository

import androidx.lifecycle.LiveData
import com.philkes.notallyx.data.dao.CommonDao
import com.philkes.notallyx.data.dao.LabelDao
import com.philkes.notallyx.data.imports.ImportResult
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Label

/** Repository seam over [LabelDao] + the label-related transactions of [CommonDao]. */
interface LabelRepository {

    fun getAll(): LiveData<List<Label>>

    suspend fun getArrayOfAll(): Array<String>

    suspend fun exists(value: String): Boolean

    suspend fun getMaxOrder(): Int?

    suspend fun insert(label: Label)

    suspend fun update(labels: List<Label>)

    suspend fun delete(value: String)

    suspend fun deleteAll()

    /** Transactionally removes the label from all notes and deletes it. */
    suspend fun deleteLabel(value: String)

    /** Transactionally renames a label everywhere (notes + label table). */
    suspend fun updateLabel(oldValue: String, newValue: String)

    suspend fun importBackup(
        baseNotes: List<BaseNote>,
        labels: List<Label>,
        readCorrupted: Int,
        checkDuplicates: Boolean,
    ): ImportResult
}

/** Room-backed implementation. DAOs are resolved lazily — see [RoomNoteRepository]. */
class RoomLabelRepository(
    private val labelDaoProvider: () -> LabelDao,
    private val commonDaoProvider: () -> CommonDao,
) : LabelRepository {

    private val labelDao: LabelDao
        get() = labelDaoProvider()

    private val commonDao: CommonDao
        get() = commonDaoProvider()

    override fun getAll(): LiveData<List<Label>> = labelDao.getAll()

    override suspend fun getArrayOfAll(): Array<String> = labelDao.getArrayOfAll()

    override suspend fun exists(value: String): Boolean = labelDao.exists(value)

    override suspend fun getMaxOrder(): Int? = labelDao.getMaxOrder()

    override suspend fun insert(label: Label) = labelDao.insert(label)

    override suspend fun update(labels: List<Label>) = labelDao.update(labels)

    override suspend fun delete(value: String) = labelDao.delete(value)

    override suspend fun deleteAll() = labelDao.deleteAll()

    override suspend fun deleteLabel(value: String) = commonDao.deleteLabel(value)

    override suspend fun updateLabel(oldValue: String, newValue: String) =
        commonDao.updateLabel(oldValue, newValue)

    override suspend fun importBackup(
        baseNotes: List<BaseNote>,
        labels: List<Label>,
        readCorrupted: Int,
        checkDuplicates: Boolean,
    ): ImportResult = commonDao.importBackup(baseNotes, labels, readCorrupted, checkDuplicates)
}
