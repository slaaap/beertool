package uk.beertool.web

import kotlinx.html.*

enum class Icon(private val file: String) {
    PLUS("plus"), EDIT("edit"), TRASH("trash"), BACK("back"), BREW("brew"), LOG("log"), PACKAGE("package");

    private val svg by lazy { loadIcon(file) }
    fun markup() = svg
}

private fun loadIcon(file: String): String =
    Icon::class.java.getResourceAsStream("/static/icons/$file.svg")
        ?.bufferedReader()?.use { it.readText().trim() }
        ?: error("Missing icon: static/icons/$file.svg")

private fun FlowContent.iconSvg(icon: Icon) = span("ico") { unsafe { +icon.markup() } }

fun FlowContent.js(file: String) = script(src = "/static/js/$file") {}

enum class ButtonStyle(val css: String?) { DEFAULT(null), PRIMARY("primary"), DANGER("danger") }

private fun cssClasses(vararg names: String?) = names.filterNotNull().joinToString(" ")

fun FlowContent.btnLink(href: String, label: String, icon: Icon, style: ButtonStyle = ButtonStyle.DEFAULT) {
    a(href = href, classes = cssClasses("btn", style.css)) {
        iconSvg(icon)
        span { +label }
    }
}

fun FlowContent.btnPost(
    action: String,
    label: String,
    icon: Icon,
    style: ButtonStyle = ButtonStyle.DEFAULT,
    iconOnly: Boolean = false,
    confirm: String? = null,
) {
    form(action = action, method = FormMethod.post, classes = "inline") {
        confirm?.let { onSubmit = "return confirm('${it.replace("'", "\\'")}')" }
        button(type = ButtonType.submit, classes = cssClasses("btn", style.css, if (iconOnly) "icon-only" else null)) {
            title = label
            attributes["aria-label"] = label
            iconSvg(icon)
            if (!iconOnly) span { +label }
        }
    }
}

fun FlowContent.deleteButton(action: String, confirm: String) =
    btnPost(action, "Delete", Icon.TRASH, style = ButtonStyle.DANGER, iconOnly = true, confirm = confirm)

fun FlowContent.rowRemoveButton(onClick: String, label: String) {
    button(type = ButtonType.button, classes = "btn danger icon-only") {
        this.onClick = onClick
        title = label
        attributes["aria-label"] = label
        iconSvg(Icon.TRASH)
    }
}

fun FlowContent.actionBar(block: DIV.() -> Unit) = div("actions") { block() }

fun FlowContent.pageHead(title: String, actions: DIV.() -> Unit) {
    div("page-head") {
        h1 { +title }
        div("page-actions") { actions() }
    }
}

fun FlowContent.sectionHead(heading: String, actions: (DIV.() -> Unit)? = null) {
    div("section-head") {
        h2 { +heading }
        actions?.let { div("section-actions") { it() } }
    }
}

fun FlowContent.field(text: String, hint: String? = null, block: DIV.() -> Unit) {
    div("field") {
        label { +text }
        block()
        hint?.let { span("hint") { +it } }
    }
}

fun FlowContent.fieldGrid(columns: Int = 2, block: DIV.() -> Unit) = div("grid g$columns") { block() }

fun FlowContent.scrollTable(tableClass: String? = null, block: TABLE.() -> Unit) =
    div("table-scroll") { table(tableClass) { block() } }
