package uk.beertool.web

import io.ktor.http.HttpStatusCode
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
import uk.beertool.auth.LoginThrottle
import uk.beertool.auth.PasswordHasher
import uk.beertool.auth.UserSession
import uk.beertool.common.io
import uk.beertool.user.SingleUser
import uk.beertool.user.UserRepository
import java.net.InetAddress
import java.net.URI

@Serializable
data class RegisterForm(val email: String = "", val displayName: String = "", val password: String = "")

@Serializable
data class LoginForm(val email: String = "", val password: String = "", val next: String = "")

// Only a plain same-site path: browsers drop tabs and newlines from URLs, so "/\t/evil.example" would leave the site.
internal fun safeNext(next: String?): String? = next?.takeIf { path ->
    path.startsWith("/") && !path.startsWith("//") && path.none { it.code < 0x20 || it.code == 0x7f || it == '\\' } &&
        runCatching { URI(path) }.getOrNull()?.let { it.scheme == null && it.rawAuthority == null } == true
}

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
            io { UserRepository.findByEmail(email) } != null -> "That email is already registered."
            else -> null
        }
        if (error != null) {
            call.respondRegisterForm(email, displayName, error)
            return@post
        }

        val user = io { UserRepository.create(email, PasswordHasher.hash(form.password), displayName) }
        call.sessions.set(UserSession(user.id))
        call.respondRedirect("/recipes")
    }

    get<Login> { res -> call.respondLoginForm(next = safeNext(res.next)) }

    post<Login> {
        val form = call.receiveForm<LoginForm>()
        val email = form.email.trim()
        val address = call.clientAddress()

        if (LoginThrottle.blocked(address, email)) {
            call.respondLoginForm(email, "Too many failed attempts. Try again in a few minutes.", safeNext(form.next), HttpStatusCode.TooManyRequests)
            return@post
        }
        val found = io { UserRepository.findByEmail(email)?.takeIf { PasswordHasher.verify(form.password, it.passwordHash) } }
        if (found == null) {
            LoginThrottle.fail(address, email)
            call.respondLoginForm(email, "Invalid email or password.", safeNext(form.next))
            return@post
        }

        LoginThrottle.succeed(address, email)
        call.sessions.set(UserSession(found.user.id))
        call.respondRedirect(safeNext(form.next) ?: "/recipes")
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

// Behind the local reverse proxy every request comes from loopback, and the proxy appends the real client last.
private fun ApplicationCall.clientAddress(): String {
    val peer = request.local.remoteAddress
    val forwarded = request.headers["X-Forwarded-For"]?.substringAfterLast(',')?.trim()
    val viaProxy = runCatching { InetAddress.getByName(peer).isLoopbackAddress }.getOrDefault(false)
    return if (viaProxy && !forwarded.isNullOrEmpty()) forwarded else peer
}

private suspend fun ApplicationCall.respondLoginForm(
    email: String = "",
    error: String? = null,
    next: String? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondHtml(status) {
    page("Log in") {
        div("auth-card") {
            h1 { +"Log in" }
            error?.let { p("alert") { +it } }
            form(action = "/login", method = FormMethod.post) {
                next?.let { hiddenInput(name = "next") { value = it } }
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
