package uk.beertool.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
    @SerialName("callback_query") val callbackQuery: CallbackQuery? = null,
    @SerialName("inline_query") val inlineQuery: InlineQuery? = null,
)

@Serializable
data class Message(
    @SerialName("message_id") val messageId: Long,
    val chat: Chat,
    val from: TgUser? = null,
    val text: String? = null,
)

@Serializable
data class Chat(
    val id: Long,
    val type: String,
) {
    val isPrivate get() = type == "private"
}

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String = "",
    val username: String? = null,
)

@Serializable
data class CallbackQuery(
    val id: String,
    val from: TgUser,
    val message: Message? = null,
    val data: String? = null,
)

@Serializable
data class InlineQuery(
    val id: String,
    val from: TgUser,
    val query: String,
)

@Serializable
data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>,
)

@Serializable
data class InlineKeyboardButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String? = null,
    val url: String? = null,
)

@Serializable
data class BotCommand(
    val command: String,
    val description: String,
)

@Serializable
data class InlineQueryResultArticle(
    val id: String,
    val title: String,
    val description: String? = null,
    @SerialName("input_message_content") val inputMessageContent: InputTextMessageContent,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
    val type: String = "article",
)

@Serializable
data class InputTextMessageContent(
    @SerialName("message_text") val messageText: String,
    @SerialName("parse_mode") val parseMode: String = HTML,
    @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions = NO_PREVIEW,
)

@Serializable
data class LinkPreviewOptions(
    @SerialName("is_disabled") val isDisabled: Boolean,
)

internal const val HTML = "HTML"
internal val NO_PREVIEW = LinkPreviewOptions(isDisabled = true)

fun keyboard(vararg rows: List<InlineKeyboardButton>) = InlineKeyboardMarkup(rows.filter { it.isNotEmpty() })

fun button(text: String, data: String) = InlineKeyboardButton(text, callbackData = data)

fun linkButton(text: String, url: String) = InlineKeyboardButton(text, url = url)
