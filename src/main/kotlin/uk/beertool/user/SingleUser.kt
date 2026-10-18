package uk.beertool.user

import uk.beertool.common.systemEnv
import uk.beertool.common.text

object SingleUser {
    var email: String? = systemEnv.text("SINGLE_USER_EMAIL")
    val enabled: Boolean get() = email != null
    fun defaultUser(): User? = email?.let { UserRepository.findByEmail(it)?.user }
}
