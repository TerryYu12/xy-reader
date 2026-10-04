package com.xyreader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xyreader.core.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 首页排序与书架布局单独存放；DataStore 委托只在顶层定义一次。 */
private val Context.libraryLayoutDataStore by preferencesDataStore(name = "library_layout")

private val KEY_HOME_ORDER = stringPreferencesKey("home_book_order")
private val KEY_HOME_MANUAL_ORDER = booleanPreferencesKey("home_manual_order")
private val KEY_SHELF_CABINET_VIEW = booleanPreferencesKey("shelf_cabinet_view")

data class LibraryLayout(
    val homeBookOrder: List<Long> = emptyList(),
    val homeManualOrder: Boolean = false,
    val shelfCabinetView: Boolean = true,
)

/** 持久化首页排序和书架视图选择，不改 Room 记录或数据库迁移。 */
class LibraryLayoutStore(context: Context) {
    private val dataStore = context.applicationContext.libraryLayoutDataStore

    val layout: Flow<LibraryLayout> = dataStore.data.map { prefs ->
        LibraryLayout(
            homeBookOrder = prefs[KEY_HOME_ORDER]
                .orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull() }
                .distinct(),
            homeManualOrder = prefs[KEY_HOME_MANUAL_ORDER] ?: false,
            shelfCabinetView = prefs[KEY_SHELF_CABINET_VIEW] ?: true,
        )
    }

    suspend fun setHomeManualOrder(bookIds: List<Long>) {
        dataStore.edit { prefs ->
            prefs[KEY_HOME_ORDER] = bookIds.distinct().joinToString(",")
            prefs[KEY_HOME_MANUAL_ORDER] = true
        }
    }

    suspend fun useAutomaticHomeOrder() {
        dataStore.edit { prefs -> prefs[KEY_HOME_MANUAL_ORDER] = false }
    }

    suspend fun setShelfCabinetView(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SHELF_CABINET_VIEW] = enabled }
    }
}

/** 已保存的书籍排在前面，新导入且尚未写入顺序的书保留当前顺序。 */
internal fun applyHomeBookOrder(books: List<BookEntity>, orderedIds: List<Long>): List<BookEntity> {
    if (books.size < 2 || orderedIds.isEmpty()) return books
    val byId = books.associateBy(BookEntity::id)
    val stored = orderedIds.mapNotNull(byId::get)
    val storedIds = stored.asSequence().map(BookEntity::id).toHashSet()
    return stored + books.filterNot { it.id in storedIds }
}

/** 只替换当前可见书籍所在的位置，搜索或分类中的拖动不会覆盖隐藏书籍的顺序。 */
internal fun reorderVisibleBookIds(
    allBookIds: List<Long>,
    visibleBookIds: List<Long>,
    reorderedVisibleIds: List<Long>,
): List<Long> {
    if (allBookIds.isEmpty() || visibleBookIds.isEmpty()) return allBookIds
    val allIds = allBookIds.toHashSet()
    val visible = visibleBookIds.asSequence().filter { it in allIds }.distinct().toSet()
    if (visible.isEmpty()) return allBookIds
    val reordered = reorderedVisibleIds.asSequence()
        .filter { it in visible }
        .distinct()
        .toMutableList()
    val included = reordered.toHashSet()
    visibleBookIds.forEach { if (it in visible && included.add(it)) reordered += it }

    var nextVisible = 0
    return allBookIds.map { id ->
        if (id in visible) reordered[nextVisible++] else id
    }
}
