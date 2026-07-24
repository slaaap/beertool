package uk.beertool.web

import kotlinx.html.*

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

fun FlowContent.pager(page: Page<*>, href: (Int) -> String) {
    if (page.pageCount <= 1) return
    div("pager") {
        pagerStep("← Prev", if (page.hasPrev) href(page.page - 1) else null)
        span("pager-info") { +"Page ${page.page} of ${page.pageCount}" }
        pagerStep("Next →", if (page.hasNext) href(page.page + 1) else null)
    }
}

private fun FlowContent.pagerStep(label: String, href: String?) {
    if (href == null) span("btn small disabled") { +label }
    else a(href = href, classes = "btn small") { +label }
}
