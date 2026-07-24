package uk.beertool.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.html.*
import kotlinx.serialization.Serializable
import uk.beertool.auth.PasswordHasher
import uk.beertool.auth.UserSession
import uk.beertool.user.UserRepository

@Serializable
data class RegisterForm(val email: String = "", val displayName: String = "", val password: String = "")

@Serializable
data class LoginForm(val email: String = "", val password: String = "")

fun Route.authRoutes() {
    get<Register> {
        if (SingleUser.enabled) call.respondRedirect("/login") else call.respondRegisterForm()
    }

    post<Register> {
        if (SingleUser.enabled) { call.respondRedirect("/login"); return@post }
        val form = call.receiveForm<RegisterForm>()
        val email = form.email.trim()
        val displayName = form.displayName.trim()

        val error = when {
            email.isBlank() || displayName.isBlank() -> "Email and display name are required."
            form.password.length < 8 -> "Password must be at least 8 characters."
            UserRepository.findByEmail(email) != null -> "That email is already registered."
            else -> null
        }
        if (error != null) {
            call.respondRegisterForm(email, displayName, error)
            return@post
        }

        val user = UserRepository.create(email, PasswordHasher.hash(form.password), displayName)
        call.sessions.set(UserSession(user.id))
        call.respondRedirect("/recipes")
    }

    get<Login> { call.respondLoginForm() }

    post<Login> {
        val form = call.receiveForm<LoginForm>()
        val email = form.email.trim()

        val found = UserRepository.findByEmail(email)
        if (found == null || !PasswordHasher.verify(form.password, found.passwordHash)) {
            call.respondLoginForm(email, "Invalid email or password.")
            return@post
        }

        call.sessions.set(UserSession(found.user.id))
        call.respondRedirect("/recipes")
    }

    post<Logout> {
        call.sessions.clear<UserSession>()
        call.respondRedirect("/")
    }
}

private suspend fun ApplicationCall.respondRegisterForm(
    email: String = "",
    displayName: String = "",
    error: String? = null,
) = respondHtml {
    page("Register") {
        div("auth-card") {
            h1 { +"Create your account" }
            error?.let { p("alert") { +it } }
            form(action = "/register", method = FormMethod.post) {
                fieldGrid(columns = 1) {
                    field("Email") { emailInput(name = "email") { value = email; required = true } }
                    field("Display name") {
                        textInput(name = "displayName") { value = displayName; required = true }
                    }
                    field("Password", hint = "At least 8 characters.") {
                        passwordInput(name = "password") { required = true }
                    }
                }
                submitInput { value = "Create account" }
            }
            p("auth-alt") { +"Already brewing? "; a(href = "/login") { +"Log in" } }
        }
    }
}

private suspend fun ApplicationCall.respondLoginForm(
    email: String = "",
    error: String? = null,
) = respondHtml {
    page("Log in") {
        div("auth-card") {
            h1 { +"Log in" }
            error?.let { p("alert") { +it } }
            form(action = "/login", method = FormMethod.post) {
                fieldGrid(columns = 1) {
                    field("Email") { emailInput(name = "email") { value = email; required = true } }
                    field("Password") { passwordInput(name = "password") { required = true } }
                }
                submitInput { value = "Log in" }
            }
            if (!SingleUser.enabled) p("auth-alt") { +"No account yet? "; a(href = "/register") { +"Create one" } }
        }
    }
}
