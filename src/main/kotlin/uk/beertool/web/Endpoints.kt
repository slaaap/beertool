package uk.beertool.web

import io.ktor.resources.Resource

@Resource("/")
class Home

@Resource("/register")
class Register

@Resource("/login")
class Login(val next: String? = null)

@Resource("/logout")
class Logout

@Resource("/settings")
class Settings(val saved: Boolean = false)

@Resource("/settings/connections")
class SettingsConnections

@Resource("/settings/tokens")
class SettingsTokens

@Resource("/settings/tokens/{id}/revoke")
class SettingsTokenRevoke(val id: Long)

@Resource("/settings/apps/{id}/revoke")
class SettingsAppRevoke(val id: Long)

@Resource("/settings/telegram")
class SettingsTelegram

@Resource("/settings/telegram/unlink")
class SettingsTelegramUnlink
