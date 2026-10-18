package uk.beertool.common

const val PAGE_SIZE = 20

data class Page<T>(val items: List<T>, val page: Int, val pageSize: Int, val total: Int) {
    val pageCount = if (total == 0) 1 else (total + pageSize - 1) / pageSize
    val hasPrev = page > 1
    val hasNext = page < pageCount
}

fun <T> List<T>.paginate(page: Int, pageSize: Int = PAGE_SIZE): Page<T> {
    val pageCount = if (isEmpty()) 1 else (size + pageSize - 1) / pageSize
    val p = page.coerceIn(1, pageCount)
    val from = (p - 1) * pageSize
    return Page(subList(from, minOf(from + pageSize, size)).toList(), p, pageSize, size)
}
