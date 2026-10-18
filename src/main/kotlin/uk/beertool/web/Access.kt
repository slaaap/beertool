package uk.beertool.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import uk.beertool.auth.UserSession
import uk.beertool.user.SingleUser
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import uk.beertool.user.readOnly

internal fun ApplicationCall.currentUser(): User? {
    sessions.get<UserSession>()?.let { s -> UserRepository.findById(s.userId)?.let { return it } }
    return SingleUser.defaultUser()?.readOnly()
}
