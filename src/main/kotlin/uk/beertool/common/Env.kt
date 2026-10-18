package uk.beertool.common

typealias Env = (String) -> String?

val systemEnv: Env = System::getenv

fun Env.text(name: String) = this(name)?.trim()?.ifBlank { null }

fun Env.flag(name: String, default: Boolean) = when (text(name)?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> default
}

fun Env.publicUrl() = text("PUBLIC_URL")?.trimEnd('/')?.ifBlank { null }
