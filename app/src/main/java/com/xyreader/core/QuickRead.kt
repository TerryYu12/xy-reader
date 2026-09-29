package com.xyreader.core

import kotlin.random.Random

/**
 * 「开始阅读」菜单的四种选取逻辑（纯函数，主页与书架页 FAB 菜单、单测共用）：
 * 当前书架上次阅读 = 当前分类内最近阅读；上次阅读 = 全库最近阅读；另两个为对应范围的随机。
 * 选中结果由 UI 经 Navigation.onOpenReader 直达阅读器续读（不经过详情页）。
 */
object QuickRead {

    /** 最近阅读：有阅读记录（lastReadAt 非空）中最大者；无记录返回 null */
    fun lastRead(books: List<BookEntity>): BookEntity? =
        books.filter { it.lastReadAt != null }.maxByOrNull { it.lastReadAt!! }

    /** 随机一本；空列表返回 null。[random] 可注入固定种子便于测试 */
    fun random(books: List<BookEntity>, random: Random = Random.Default): BookEntity? =
        if (books.isEmpty()) null else books[random.nextInt(books.size)]
}
