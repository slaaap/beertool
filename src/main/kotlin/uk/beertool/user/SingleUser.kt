package uk.beertool.user

object SingleUser {
    var email: String? = System.getenv("SINGLE_USER_EMAIL")?.trim()?.ifBlank { null }
    val enabled: Boolean get() = email != null
    fun defaultUser(): User? = email?.let { UserRepository.findByEmail(it)?.user }
}
