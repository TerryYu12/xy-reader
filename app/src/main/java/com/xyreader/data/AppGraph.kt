package com.xyreader.data

import android.content.Context
import com.xyreader.core.LibraryRepository

/**
 * data 层依赖注入点：其他层只通过 [AppGraph.libraryRepository] 拿仓库。
 *
 * DataStore 同一文件全局只能有一个实例，因此仓库必须全局单例，
 * 由这里的双检锁保证。
 */
object AppGraph {

    @Volatile
    private var repo: LibraryRepository? = null

    fun libraryRepository(context: Context): LibraryRepository =
        repo ?: synchronized(this) {
            repo ?: LibraryRepositoryImpl(context.applicationContext).also { repo = it }
        }
}
