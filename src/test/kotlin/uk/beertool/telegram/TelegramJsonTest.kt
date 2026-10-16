package uk.beertool.telegram

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TelegramJsonTest {

    @Test
    fun `should read a Telegram message update and ignore fields it does not use`() {
        val json = """
            {"update_id": 10, "message": {"message_id": 5, "date": 1760000000,
              "from": {"id": 99, "is_bot": false, "first_name": "Ann", "language_code": "en"},
              "chat": {"id": 99, "type": "private", "first_name": "Ann"},
              "text": "/recipes", "entities": [{"offset": 0, "length": 8, "type": "bot_command"}]}}
        """

        val update = TelegramJson.decodeFromString(Update.serializer(), json)

        update shouldBe Update(10, message = Message(5, Chat(99, "private"), TgUser(99, firstName = "Ann"), "/recipes"))
    }

    @Test
    fun `should send a callback button in the shape Telegram expects`() {
        val markup = keyboard(listOf(button("Brews", "rb:3")))

        val json = TelegramJson.encodeToString(InlineKeyboardMarkup.serializer(), markup)

        json shouldBe """{"inline_keyboard":[[{"text":"Brews","callback_data":"rb:3"}]]}"""
    }

    @Test
    fun `should send an inline result as an HTML article without link previews`() {
        val article = InlineQueryResultArticle(id = "3", title = "Pale", inputMessageContent = InputTextMessageContent("<b>Pale</b>"))

        val json = TelegramJson.encodeToString(InlineQueryResultArticle.serializer(), article)

        json shouldBe """{"id":"3","title":"Pale","input_message_content":{"message_text":"<b>Pale</b>",""" +
            """"parse_mode":"HTML","link_preview_options":{"is_disabled":true}},"type":"article"}"""
    }
}
