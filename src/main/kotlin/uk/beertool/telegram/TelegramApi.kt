package uk.beertool.telegram

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

interface TelegramApi {
    suspend fun getMe(): TgUser
    suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<Update>
    suspend fun deleteWebhook()
    suspend fun setMyCommands(commands: List<BotCommand>)
    suspend fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup? = null)
    suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboardMarkup? = null)
    suspend fun answerCallbackQuery(id: String, text: String? = null)
    suspend fun answerInlineQuery(id: String, results: List<InlineQueryResultArticle>)
}

class TelegramException(val errorCode: Int?, message: String) : RuntimeException(message) {
    val isUnauthorized get() = errorCode == 401
    val isNotModified get() = errorCode == 400 && "not modified" in message.orEmpty()
}

internal val TelegramJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

class HttpTelegramApi(private val token: String, pollTimeoutSeconds: Int) : TelegramApi, AutoCloseable {

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = (pollTimeoutSeconds + 15) * 1000L }
    }

    override suspend fun getMe() = call("getMe", Empty(), Empty.serializer(), TgUser.serializer())

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int) =
        call("getUpdates", GetUpdates(offset, timeoutSeconds), GetUpdates.serializer(), ListSerializer(Update.serializer()))

    override suspend fun deleteWebhook() {
        call("deleteWebhook", Empty(), Empty.serializer(), Boolean.serializer())
    }

    override suspend fun setMyCommands(commands: List<BotCommand>) {
        call("setMyCommands", SetMyCommands(commands), SetMyCommands.serializer(), Boolean.serializer())
    }

    override suspend fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup?) {
        call("sendMessage", SendMessage(chatId, text, keyboard), SendMessage.serializer(), Message.serializer())
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboardMarkup?) {
        try {
            call(
                "editMessageText", EditMessageText(chatId, messageId, text, keyboard),
                EditMessageText.serializer(), Message.serializer(),
            )
        } catch (e: TelegramException) {
            if (!e.isNotModified) throw e
        }
    }

    override suspend fun answerCallbackQuery(id: String, text: String?) {
        call("answerCallbackQuery", AnswerCallback(id, text), AnswerCallback.serializer(), Boolean.serializer())
    }

    override suspend fun answerInlineQuery(id: String, results: List<InlineQueryResultArticle>) {
        call("answerInlineQuery", AnswerInline(id, results), AnswerInline.serializer(), Boolean.serializer())
    }

    override fun close() = client.close()

    private suspend fun <Q, R> call(method: String, request: Q, requestSerializer: KSerializer<Q>, resultSerializer: KSerializer<R>): R {
        val body = try {
            client.post("https://api.telegram.org/bot$token/$method") {
                setBody(TextContent(TelegramJson.encodeToString(requestSerializer, request), ContentType.Application.Json))
            }.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transport errors can quote the request URL, and the URL carries the bot token.
            throw TelegramException(null, "$method failed: ${e::class.simpleName}: ${e.message?.replace(token, "<token>")}")
        }
        val response = TelegramJson.decodeFromString(Response.serializer(resultSerializer), body)
        return response.result.takeIf { response.ok }
            ?: throw TelegramException(response.errorCode, "$method failed: ${response.description}")
    }

    @Serializable
    private class Response<R>(
        val ok: Boolean,
        val result: R? = null,
        @SerialName("error_code") val errorCode: Int? = null,
        val description: String? = null,
    )

    @Serializable
    private class Empty

    @Serializable
    private class GetUpdates(
        val offset: Long?,
        val timeout: Int,
        @SerialName("allowed_updates") val allowedUpdates: List<String> = listOf("message", "callback_query", "inline_query"),
    )

    @Serializable
    private class SetMyCommands(val commands: List<BotCommand>)

    @Serializable
    private class SendMessage(
        @SerialName("chat_id") val chatId: Long,
        val text: String,
        @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup?,
        @SerialName("parse_mode") val parseMode: String = HTML,
        @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions = NO_PREVIEW,
    )

    @Serializable
    private class EditMessageText(
        @SerialName("chat_id") val chatId: Long,
        @SerialName("message_id") val messageId: Long,
        val text: String,
        @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup?,
        @SerialName("parse_mode") val parseMode: String = HTML,
        @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions = NO_PREVIEW,
    )

    @Serializable
    private class AnswerCallback(
        @SerialName("callback_query_id") val callbackQueryId: String,
        val text: String?,
    )

    @Serializable
    private class AnswerInline(
        @SerialName("inline_query_id") val inlineQueryId: String,
        val results: List<InlineQueryResultArticle>,
        @SerialName("cache_time") val cacheTime: Int = 10,
        @SerialName("is_personal") val isPersonal: Boolean = true,
    )
}
