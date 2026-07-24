package uk.beertool.web

import io.ktor.resources.Resource

@Resource("/")
class Home

@Resource("/register")
class Register

@Resource("/login")
class Login

@Resource("/logout")
class Logout

@Resource("/settings")
class Settings(val saved: Boolean = false)
