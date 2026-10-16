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

@Resource("/settings/tokens")
class SettingsTokens

@Resource("/settings/tokens/{id}/revoke")
class SettingsTokenRevoke(val id: Long)

@Resource("/settings/telegram")
class SettingsTelegram

@Resource("/settings/telegram/unlink")
class SettingsTelegramUnlink
