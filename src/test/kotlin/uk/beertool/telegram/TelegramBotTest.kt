package uk.beertool.telegram

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import uk.beertool.web.SingleUser
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
    fun `should link a Telegram account with a token issued from settings`() = runBlocking {
        val user = newUser()
        val token = TelegramLinkRepository.issueToken(user.id)

        send("/start $token")

        TelegramLinkRepository.findByTelegramUser(telegramId)!!.userId shouldBe user.id
        api.last.text shouldContain "Linked to Brewer"
    }

    @Test
    fun `should refuse a link token that was already used`() = runBlocking {
        val token = TelegramLinkRepository.issueToken(newUser().id)
        TelegramLinkRepository.redeem(token, telegramUserId = 42, chatId = 42)

        send("/start $token")

        TelegramLinkRepository.findByTelegramUser(telegramId).shouldBeNull()
        api.last.text shouldContain "expired or was already used"
    }

    @Test
    fun `should greet a linked brewer with the bot commands only`() = runBlocking {
        linkedUser()

        send("/start")

        api.last.text shouldContain "/brew - "
        api.last.text shouldNotContain "og 1.052"
    }

    @Test
    fun `should not offer brewing commands to a showcase visitor`() = runBlocking {
        SingleUser.email = newUser().email

        send("/help")

        api.last.text shouldContain "/recipes - "
        api.last.text shouldNotContain "/brew"
        api.last.text shouldNotContain "/unlink"
    }

    @Test
    fun `should explain how brew day works when nothing is brewing`() = runBlocking {
        linkedUser()

        send("/brew")

        api.last.text shouldContain "Start brew day"
        api.last.text shouldContain "<code>og 1.052</code>"
    }

    @Test
    fun `should tell an unlinked chat how to connect when there is no showcase`() = runBlocking {
        send("/recipes")

        api.last.text shouldContain "isn't linked to beertool yet"
    }

    @Test
    fun `should let anyone browse the showcase brewer's recipes without offering to brew`() = runBlocking {
        val owner = newUser()
        val recipe = newRecipe(owner)
        SingleUser.email = owner.email

        send("/recipe ${recipe.no}")

        api.last.text shouldContain "Pale Ale"
        api.last.buttons.map { it.text } shouldNotContain "Start brew day"
    }

    @Test
    fun `should refuse readings from a showcase visitor`() = runBlocking {
        val owner = newUser()
        BatchRepository.create(owner.id, NewBatch(recipeId = newRecipe(owner).id))
        SingleUser.email = owner.email

        send("og 1.050")

        api.last.text shouldContain "read-only showcase"
        BatchRepository.listByUser(owner.id).single().batch.measuredOg.shouldBeNull()
    }

    @Test
    fun `should log readings against the only brew on the go and stamp its ABV`() = runBlocking {
        val user = linkedUser()
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = newRecipe(user).id, measuredOg = 1.050))!!

        send("fg 1010 fermenter 21")

        val saved = BatchRepository.findById(batch.id, user.id)!!
        saved.measuredFg shouldBe 1.010
        saved.measuredFermenterVolumeL shouldBe 21.0
        saved.abv.shouldNotBeNull() shouldBe (5.25 plusOrMinus 0.1)
        api.last.text shouldContain "Saved ✓"
    }

    @Test
    fun `should ask which brew the readings are for when several are on the go`() = runBlocking {
        val user = linkedUser()
        BatchRepository.create(user.id, NewBatch(recipeId = newRecipe(user, "Stout").id))
        BatchRepository.create(user.id, NewBatch(recipeId = newRecipe(user, "Lager").id))

        send("og 1.050")

        api.last.text shouldContain "Which brew"
        BatchRepository.listByUser(user.id).map { it.batch.measuredOg }.filterNotNull().shouldBeEmpty()
    }

    @Test
    fun `should ignore messages in group chats`() = runBlocking {
        linkedUser()

        send("/recipes", chatType = "group")

        api.sent.shouldBeEmpty()
    }

    @Test
    fun `should start today's brew from a recipe and send readings to it`() = runBlocking {
        val user = linkedUser()
        val recipe = newRecipe(user)

        press("sb:${recipe.no}")
        send("og 1.048")

        val batch = BatchRepository.listByRecipe(recipe.id, user.id).single()
        batch.brewDate shouldBe LocalDate.of(2026, 10, 16)
        batch.measuredOg shouldBe 1.048
    }

    @Test
    fun `should call each boil addition when it falls due and then flame-out`() = runBlocking {
        val user = linkedUser()
        val recipe = newRecipe(
            user,
            hops = listOf(
                RecipeHop(name = "Magnum", amountG = 20.0, alphaAcid = 12.0, usage = HopUsage.BOIL, boilTimeMin = 60),
                RecipeHop(name = "Cascade", amountG = 30.0, alphaAcid = 6.0, usage = HopUsage.BOIL, boilTimeMin = 15),
            ),
        )
        val batch = BatchRepository.startBrewDay(recipe.id, user.id, LocalDate.of(2026, 10, 16))!!

        press("boil:${recipe.no}:${batch.no}")
        now = now.plusSeconds(45 * 60)
        bot.tickBoilTimers()
        now = now.plusSeconds(15 * 60)
        bot.tickBoilTimers()
        bot.tickBoilTimers()

        val alerts = api.sent.map { it.text }.filter { it.startsWith("<b>Pale Ale</b>:") }
        alerts shouldHaveSize 3
        alerts[0] shouldContain "Magnum 20 g"
        alerts[1] shouldContain "Cascade 30 g"
        alerts[2] shouldContain "flame-out!"
        BoilTimerRepository.find(batch.id).shouldBeNull()
    }

    @Test
    fun `should not let a showcase visitor press a write button`() = runBlocking {
        val owner = newUser()
        val recipe = newRecipe(owner)
        SingleUser.email = owner.email

        press("sb:${recipe.no}")

        BatchRepository.listByRecipe(recipe.id, owner.id).shouldBeEmpty()
        api.last.text shouldContain "read-only showcase"
    }

    @Test
    fun `should offer recipe cards for inline sharing`() = runBlocking {
        val user = linkedUser()
        newRecipe(user, "Citra Pale")
        newRecipe(user, "Dry Stout")

        bot.handle(Update(updateId = 1, inlineQuery = InlineQuery("q1", TgUser(telegramId), "citra")))

        val results = api.inlineAnswers.single()
        results.map { it.title } shouldBe listOf("Citra Pale")
        results.single().inputMessageContent.messageText shouldContain "<b>Citra Pale</b>"
    }

    @Test
    fun `should page through the recipe list in place`() = runBlocking {
        val user = linkedUser()
        repeat(9) { newRecipe(user, "Beer $it") }

        send("/recipes")
        press("rl:2")

        api.last.buttons.map { it.text } shouldContain "Next »"
        api.edited.single().text shouldBe "Recipes · page 2 of 2"
        api.edited.single().buttons.map { it.text } shouldContain "« Prev"
    }

    private suspend fun send(text: String, chatType: String = "private") = bot.handle(
        Update(updateId = 1, message = Message(1, Chat(telegramId, chatType), TgUser(telegramId), text)),
    )

    private suspend fun press(data: String) = bot.handle(
        Update(updateId = 1, callbackQuery = CallbackQuery("cb", TgUser(telegramId), Message(2, Chat(telegramId, "private")), data)),
    )

    private fun newUser(): User = UserRepository.create("u-${UUID.randomUUID()}@beertool.test", "hash", "Brewer")

    private fun linkedUser(): User {
        val user = newUser()
        TelegramLinkRepository.redeem(TelegramLinkRepository.issueToken(user.id), telegramId, telegramId)
        return user
    }

    private fun newRecipe(user: User, name: String = "Pale Ale", hops: List<RecipeHop> = emptyList()): Recipe =
        RecipeRepository.create(
            user.id,
            NewRecipe(name = name, fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5)), hops = hops),
        )
}
