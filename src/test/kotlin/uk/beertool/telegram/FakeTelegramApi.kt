package uk.beertool.telegram

class FakeTelegramApi : TelegramApi {
    data class Sent(val chatId: Long, val text: String, val keyboard: InlineKeyboardMarkup?) {
        val buttons get() = keyboard?.inlineKeyboard.orEmpty().flatten()
    }

    val sent = mutableListOf<Sent>()
    val edited = mutableListOf<Sent>()
    val callbackAnswers = mutableListOf<Pair<String, String?>>()
    val inlineAnswers = mutableListOf<List<InlineQueryResultArticle>>()

    val last get() = sent.last()

    override suspend fun getMe() = TgUser(id = 1, isBot = true, firstName = "beertool", username = "beertool_test_bot")
    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int) = emptyList<Update>()
    override suspend fun deleteWebhook() {}
    override suspend fun setMyCommands(commands: List<BotCommand>) {}

    override suspend fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup?) {
        sent += Sent(chatId, text, keyboard)
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboardMarkup?) {
        edited += Sent(chatId, text, keyboard)
    }

    override suspend fun answerCallbackQuery(id: String, text: String?) {
        callbackAnswers += id to text
    }

    override suspend fun answerInlineQuery(id: String, results: List<InlineQueryResultArticle>) {
        inlineAnswers += results
    }
}
