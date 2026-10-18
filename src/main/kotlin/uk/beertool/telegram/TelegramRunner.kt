package uk.beertool.telegram

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.beertool.common.Env
import uk.beertool.common.publicUrl
import uk.beertool.common.systemEnv
import uk.beertool.common.text

data class TelegramConfig(val token: String, val publicUrl: String?) {
    companion object {
        fun fromEnv(env: Env = systemEnv): TelegramConfig? {
            val token = env.text("TELEGRAM_BOT_TOKEN") ?: return null
            return TelegramConfig(token, env.publicUrl())
        }
    }
}

// The bot's @username, known once it has connected; null means the bot is off and Settings hides it.
object Telegram {
    @Volatile
    var botUsername: String? = null
    val enabled get() = botUsername != null
}

private val log = LoggerFactory.getLogger("uk.beertool.telegram.TelegramRunner")

private const val POLL_TIMEOUT_SECONDS = 50
private const val BOIL_TICK_MS = 10_000L
private const val FIRST_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 60_000L

fun Application.configureTelegram(config: TelegramConfig? = TelegramConfig.fromEnv()) {
    if (config == null) {
        log.info("TELEGRAM_BOT_TOKEN not set - Telegram bot disabled")
        return
    }
    val api = HttpTelegramApi(config.token, POLL_TIMEOUT_SECONDS)
    val bot = TelegramBot(api, config.publicUrl)

    val job = launch(Dispatchers.IO) {
        val me = connect(api) ?: return@launch
        Telegram.botUsername = me.username
        log.info("Telegram bot @{} is up", me.username)
        launch { tickForever(bot) }
        pollForever(api, bot)
    }
    monitor.subscribe(ApplicationStopping) {
        job.cancel()
        Telegram.botUsername = null
        api.close()
    }
}

private class Backoff {
    var millis = FIRST_BACKOFF_MS
        private set

    fun reset() {
        millis = FIRST_BACKOFF_MS
    }

    suspend fun pause() {
        delay(millis)
        millis = (millis * 2).coerceAtMost(MAX_BACKOFF_MS)
    }
}

private suspend fun connect(api: TelegramApi): TgUser? {
    val backoff = Backoff()
    while (currentCoroutineContext().isActive) {
        try {
            val me = api.getMe()
            // Long polling and a webhook are mutually exclusive; clear any webhook left by an earlier deployment.
            api.deleteWebhook()
            api.setMyCommands(BOT_COMMANDS)
            return me
        } catch (e: TelegramException) {
            if (e.isUnauthorized) {
                log.error("Telegram rejected TELEGRAM_BOT_TOKEN - bot disabled. Check the token with @BotFather.")
                return null
            }
            log.warn("Telegram not reachable, retrying in {} s: {}", backoff.millis / 1000, e.message)
            backoff.pause()
        }
    }
    return null
}

private suspend fun pollForever(api: TelegramApi, bot: TelegramBot) {
    var offset: Long? = null
    val backoff = Backoff()
    while (currentCoroutineContext().isActive) {
        val updates = try {
            api.getUpdates(offset, POLL_TIMEOUT_SECONDS).also { backoff.reset() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Telegram poll failed, retrying in {} s: {}", backoff.millis / 1000, e.message)
            backoff.pause()
            continue
        }
        for (update in updates) {
            // Acknowledge before handling, so one update that keeps failing can't wedge the bot.
            offset = update.updateId + 1
            try {
                bot.handle(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Telegram update {} failed: {}", update.updateId, e.message, e)
            }
        }
    }
}

private suspend fun tickForever(bot: TelegramBot) {
    while (currentCoroutineContext().isActive) {
        try {
            bot.tickBoilTimers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Boil timer tick failed: {}", e.message)
        }
        delay(BOIL_TICK_MS)
    }
}
