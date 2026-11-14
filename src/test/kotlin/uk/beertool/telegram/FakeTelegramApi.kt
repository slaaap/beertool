package uk.beertool.telegram

class FakeTelegramApi : TelegramApi {
    data class Sent(val chatId: Long, val text: String, val keyboard: InlineKeyboardMarkup?, val messageId: Long = 0) {
        val buttons get() = keyboard?.inlineKeyboard.orEmpty().flatten()
    }

    val sent = mutableListOf<Sent>()
    val edited = mutableListOf<Sent>()
    val prompts = mutableListOf<Sent>()
    val deleted = mutableListOf<Long>()
    val callbackAnswers = mutableListOf<Pair<String, String?>>()
    val inlineAnswers = mutableListOf<List<InlineQueryResultArticle>>()

    val last get() = sent.last()

    var blocked = false
    var editFailure: TelegramException? = null
    private var nextMessageId = 100L

    override suspend fun getMe() = TgUser(id = 1, isBot = true, firstName = "beertool", username = "beertool_test_bot")
    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int) = emptyList<Update>()
    override suspend fun deleteWebhook() {}
    override suspend fun setMyCommands(commands: List<BotCommand>) {}

    override suspend fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup?): Long {
        if (blocked) throw TelegramException(403, "Forbidden: bot was blocked by the user")
        return (nextMessageId++).also { sent += Sent(chatId, text, keyboard, it) }
    }

    override suspend fun sendPrompt(chatId: Long, text: String, placeholder: String): Long =
        (nextMessageId++).also { prompts += Sent(chatId, text, null, it) }

    override suspend fun deleteMessage(chatId: Long, messageId: Long) {
        deleted += messageId
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboardMarkup?) {
        editFailure?.let { throw it }
        edited += Sent(chatId, text, keyboard, messageId)
    }

    override suspend fun answerCallbackQuery(id: String, text: String?) {
        callbackAnswers += id to text
    }

    override suspend fun answerInlineQuery(id: String, results: List<InlineQueryResultArticle>) {
        inlineAnswers += results
    }
}
