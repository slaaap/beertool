package uk.beertool.telegram

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.SingleUser
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

class TelegramBotTest {

    private val api = FakeTelegramApi()
    private var now = Instant.parse("2026-10-16T10:00:00Z")
    private val bot = TelegramBot(api, publicUrl = "https://beer.example", clock = { now }, zone = ZoneOffset.UTC)
    private val telegramId = Random.nextLong(1, Long.MAX_VALUE)

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
    }

    @AfterEach
    fun tearDown() {
        SingleUser.email = null
    }

    @Test
    fun `should link a Telegram account with a token issued from settings`() = runBlocking<Unit> {
        val user = newUser()
        val token = TelegramLinkRepository.issueToken(user.id)

        send("/start $token")

        TelegramLinkRepository.findByTelegramUser(telegramId)!!.userId shouldBe user.id
        api.last.text shouldContain "Connected as Brewer"
    }

    @Test
    fun `should refuse a link token that was already used`() = runBlocking<Unit> {
        val token = TelegramLinkRepository.issueToken(newUser().id)
        TelegramLinkRepository.redeem(token, telegramUserId = 42, chatId = 42)

        send("/start $token")

        TelegramLinkRepository.findByTelegramUser(telegramId).shouldBeNull()
        api.last.text shouldContain "expired or was already used"
    }

    @Test
    fun `should greet a linked brewer with recipes, search and the brew log`() = runBlocking<Unit> {
        linkedUser()

        send("/start")

        api.last.text shouldContain "/recipes - "
        api.last.text shouldContain "/find - "
        api.last.text shouldContain "/log - "
        api.last.text shouldContain "/unlink - "
        api.last.text shouldNotContain "/brew"
    }

    @Test
    fun `should not offer to disconnect a showcase visitor, who has nothing linked`() = runBlocking<Unit> {
        SingleUser.email = newUser().email

        send("/help")

        api.last.text shouldContain "/recipes - "
        api.last.text shouldNotContain "/unlink"
    }

    @Test
    fun `should let anyone browse the showcase brewer's recipes`() = runBlocking<Unit> {
        val owner = newUser()
        val recipe = newRecipe(owner)
        SingleUser.email = owner.email

        send("/recipe ${recipe.no}")

        api.last.text shouldContain "Pale Ale"
        api.last.buttons.map { it.text } shouldBe listOf("📚 Brews")
    }

    @Test
    fun `should search the recipes for whatever the brewer types`() = runBlocking<Unit> {
        val user = linkedUser()
        newRecipe(user, "Citra Pale")
        newRecipe(user, "Dry Stout")

        send("citra")

        api.last.text shouldContain "1 match for “citra”"
        api.last.buttons.map { it.text } shouldBe listOf("Citra Pale")
    }

    @Test
    fun `should show a brew from the log as a card with nothing to press`() = runBlocking<Unit> {
        val user = linkedUser()
        val recipe = newRecipe(user)
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id, brewDate = LocalDate.parse("2026-10-01"), measuredOg = 1.050))!!

        press("b:${recipe.no}:${batch.no}")

        api.last.text shouldContain "Pale Ale #${recipe.no} · batch ${batch.no}"
        api.last.text shouldContain "OG 1.050"
        api.last.buttons.shouldBeEmpty()
    }

    @Test
    fun `should tell an unlinked chat how to connect when there is no showcase`() = runBlocking<Unit> {
        send("/recipes")

        api.last.text shouldContain "isn't connected to beertool yet"
    }

    @Test
    fun `should ignore messages in group chats`() = runBlocking<Unit> {
        linkedUser()

        send("/recipes", chatType = "group")

        api.sent.shouldBeEmpty()
    }

    @Test
    fun `should offer recipe cards for inline sharing`() = runBlocking<Unit> {
        val user = linkedUser()
        newRecipe(user, "Citra Pale")
        newRecipe(user, "Dry Stout")

        bot.handle(Update(updateId = 1, inlineQuery = InlineQuery("q1", TgUser(telegramId), "citra")))

        val results = api.inlineAnswers.single()
        results.map { it.title } shouldBe listOf("Citra Pale")
        results.single().inputMessageContent.messageText shouldContain "Citra Pale #1</a></b>"
    }

    @Test
    fun `should page through the recipe list in place`() = runBlocking<Unit> {
        val user = linkedUser()
        repeat(9) { newRecipe(user, "Beer $it") }

        send("/recipes")
        press("rl:2")

        api.last.buttons.map { it.text } shouldContain "Next »"
        api.edited.single().text shouldBe "📚 <b>Recipes</b>\n<i>Page 2 of 2</i>"
        api.edited.single().buttons.map { it.text } shouldContain "« Prev"
    }

    private suspend fun send(text: String, chatType: String = "private") =
        bot.handle(Update(updateId = 1, message = Message(1, Chat(telegramId, chatType), TgUser(telegramId), text)))

    private suspend fun press(data: String, messageId: Long = 2) = bot.handle(
        Update(updateId = 1, callbackQuery = CallbackQuery("cb", TgUser(telegramId), Message(messageId, Chat(telegramId, "private")), data)),
    )

    private fun newUser(): User = UserRepository.create("u-${UUID.randomUUID()}@beertool.test", "hash", "Brewer")

    private fun linkedUser(): User {
        val user = newUser()
        TelegramLinkRepository.redeem(TelegramLinkRepository.issueToken(user.id), telegramId, telegramId)
        return user
    }

    private fun newRecipe(user: User, name: String = "Pale Ale"): Recipe =
        RecipeRepository.create(user.id, NewRecipe(name = name, fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5))))
}
