package uk.beertool.web

import kotlinx.html.*
import uk.beertool.common.Page

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
