package uk.beertool.web

import kotlinx.html.*
import uk.beertool.user.User

const val LOGO = "/static/logo.svg"

fun HTML.page(pageTitle: String, currentUser: User? = null, block: MAIN.() -> Unit) {
    head {
        title("$pageTitle · beertool")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        link(rel = "icon", href = LOGO, type = "image/svg+xml")
        link(rel = "stylesheet", href = "/static/css/app.css")
    }
    body {
        header("bar") {
            a(href = "/", classes = "wordmark") {
                brandMark()
                +"beertool"
            }
            nav("bar-nav") {
                if (currentUser == null) {
                    a(href = "/login") { +"Log in" }
                    if (!SingleUser.enabled) a(href = "/register") { +"Register" }
                } else {
                    a(href = "/recipes") { +"Recipes" }
                    a(href = "/batches") { +"Brew log" }
                    if (currentUser.canWrite) {
                        a(href = "/settings") { +"Settings" }
                        form(action = "/logout", method = FormMethod.post, classes = "inline") {
                            submitInput(classes = "btn") { value = "Log out" }
                        }
                    } else {
                        a(href = "/login") { +"Log in" }
                    }
                }
            }
        }
        main("wrap") { block() }
    }
}

fun FlowContent.brandMark() = img(alt = "", src = LOGO, classes = "mark")
