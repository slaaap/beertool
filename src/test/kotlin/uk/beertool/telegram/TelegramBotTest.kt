package uk.beertool.telegram

import io.kotest.inspectors.forAll
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
    fun `should greet a linked brewer with the bot commands only`() = runBlocking<Unit> {
        linkedUser()

        send("/start")

        api.last.text shouldContain "/brew - "
        api.last.text shouldNotContain "og 1.052"
    }

    @Test
    fun `should not offer brewing commands to a showcase visitor`() = runBlocking<Unit> {
        SingleUser.email = newUser().email

        send("/help")

        api.last.text shouldContain "/recipes - "
        api.last.text shouldNotContain "/brew"
        api.last.text shouldNotContain "/unlink"
    }

    @Test
    fun `should explain how brew day works when nothing is brewing`() = runBlocking<Unit> {
        linkedUser()

        send("/brew")

        api.last.text shouldContain "Start brew day"
        api.last.text shouldContain "<code>og 1.052</code>"
    }

    @Test
    fun `should tell an unlinked chat how to connect when there is no showcase`() = runBlocking<Unit> {
        send("/recipes")

        api.last.text shouldContain "isn't connected to beertool yet"
    }

    @Test
    fun `should let anyone browse the showcase brewer's recipes without offering to brew`() = runBlocking<Unit> {
        val owner = newUser()
        val recipe = newRecipe(owner)
        SingleUser.email = owner.email

        send("/recipe ${recipe.no}")

        api.last.text shouldContain "Pale Ale"
        api.last.buttons.map { it.text } shouldNotContain "🔥 Start brew day"
    }

    @Test
    fun `should refuse readings from a showcase visitor`() = runBlocking<Unit> {
        val owner = newUser()
        BatchRepository.create(owner.id, NewBatch(recipeId = newRecipe(owner).id))
        SingleUser.email = owner.email

        send("og 1.050")

        api.last.text shouldContain "read-only showcase"
        BatchRepository.listByUser(owner.id).single().batch.measuredOg.shouldBeNull()
    }

    @Test
    fun `should log readings against the only brew on the go and stamp its ABV`() = runBlocking<Unit> {
        val user = linkedUser()
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = newRecipe(user).id, measuredOg = 1.050))!!

        send("fg 1010 fermenter 21")

        val saved = BatchRepository.findById(batch.id, user.id)!!
        saved.measuredFg shouldBe 1.010
        saved.measuredFermenterVolumeL shouldBe 21.0
        saved.abv.shouldNotBeNull() shouldBe (5.25 plusOrMinus 0.1)
        api.last.text shouldContain "✅ Saved FG 1.010, fermenter 21 L"
    }

    @Test
    fun `should ask which brew the readings are for when several are on the go`() = runBlocking<Unit> {
        val user = linkedUser()
        BatchRepository.create(user.id, NewBatch(recipeId = newRecipe(user, "Stout").id))
        BatchRepository.create(user.id, NewBatch(recipeId = newRecipe(user, "Lager").id))

        send("og 1.050")

        api.last.text shouldContain "Which brew"
        BatchRepository.listByUser(user.id).map { it.batch.measuredOg }.filterNotNull().shouldBeEmpty()
    }

    @Test
    fun `should ignore messages in group chats`() = runBlocking<Unit> {
        linkedUser()

        send("/recipes", chatType = "group")

        api.sent.shouldBeEmpty()
    }

    @Test
    fun `should start today's brew from a recipe and send readings to it`() = runBlocking<Unit> {
        val user = linkedUser()
        val recipe = newRecipe(user)

        press("sb:${recipe.no}")
        send("og 1.048")

        val batch = BatchRepository.listByRecipe(recipe.id, user.id).single()
        batch.brewDate shouldBe LocalDate.of(2026, 10, 16)
        batch.measuredOg shouldBe 1.048
    }

    @Test
    fun `should call each boil addition when it falls due and then flame-out`() = runBlocking<Unit> {
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

        val alerts = api.sent.map { it.text }.filter { "<b>Add now</b>" in it || "<b>Flame-out</b>" in it }
        alerts shouldHaveSize 3
        alerts[0] shouldContain "Magnum 20 g"
        alerts[1] shouldContain "Cascade 30 g"
        alerts[2] shouldContain "<b>Flame-out</b> · Pale Ale #1"
        BoilTimerRepository.find(batch.id).shouldBeNull()
    }

    @Test
    fun `should not let a showcase visitor press a write button`() = runBlocking<Unit> {
        val owner = newUser()
        val recipe = newRecipe(owner)
        SingleUser.email = owner.email

        press("sb:${recipe.no}")

        BatchRepository.listByRecipe(recipe.id, owner.id).shouldBeEmpty()
        api.last.text shouldContain "read-only showcase"
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

    @Test
    fun `should give up on a boil timer whose chat has blocked the bot`() = runBlocking<Unit> {
        val user = linkedUser()
        val recipe = boilRecipe(user)
        val batch = BatchRepository.startBrewDay(recipe.id, user.id, LocalDate.of(2026, 10, 16))!!
        BoilTimerRepository.start(batch.id, user.id, telegramId, recipe.boilTimeMin, now)
        api.blocked = true

        now = now.plusSeconds(61 * 60)
        bot.tickBoilTimers()

        BoilTimerRepository.find(batch.id).shouldBeNull()
    }

    @Test
    fun `should disconnect and silence running boil timers on unlink`() = runBlocking<Unit> {
        val user = linkedUser()
        val recipe = boilRecipe(user)
        val batch = BatchRepository.startBrewDay(recipe.id, user.id, LocalDate.of(2026, 10, 16))!!
        BoilTimerRepository.start(batch.id, user.id, telegramId, recipe.boilTimeMin, now)

        send("/unlink")

        TelegramLinkRepository.findByTelegramUser(telegramId).shouldBeNull()
        BoilTimerRepository.find(batch.id).shouldBeNull()
        api.last.text shouldContain "Disconnected"
    }

    @Test
    fun `should ask for the final gravity before packaging`() = runBlocking<Unit> {
        val user = linkedUser()
        val recipe = newRecipe(user)
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id, measuredOg = 1.050))!!

        press("pkg:${recipe.no}:${batch.no}")

        BatchRepository.findById(batch.id, user.id)!!.packagedDate.shouldBeNull()
        api.last.text shouldContain "fg 1.010"
    }

    @Test
    fun `should package a finished batch and stop its boil timer`() = runBlocking<Unit> {
        val user = linkedUser()
        val recipe = boilRecipe(user)
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id, measuredOg = 1.050, measuredFg = 1.010))!!
        BoilTimerRepository.start(batch.id, user.id, telegramId, recipe.boilTimeMin, now)

        press("pkg:${recipe.no}:${batch.no}")

        BatchRepository.findById(batch.id, user.id)!!.packagedDate shouldBe LocalDate.of(2026, 10, 16)
        BoilTimerRepository.find(batch.id).shouldBeNull()
        api.callbackAnswers.last().second shouldBe "Packaged 🍻"
    }

    @Test
    fun `should stop a boil timer on request`() = runBlocking<Unit> {
        val user = linkedUser()
        val recipe = boilRecipe(user)
        val batch = BatchRepository.startBrewDay(recipe.id, user.id, LocalDate.of(2026, 10, 16))!!
        BoilTimerRepository.start(batch.id, user.id, telegramId, recipe.boilTimeMin, now)

        press("stop:${recipe.no}:${batch.no}")

        BoilTimerRepository.find(batch.id).shouldBeNull()
        api.callbackAnswers.last().second shouldBe "Boil timer stopped"
    }

    @Test
    fun `should refuse every write button to a showcase visitor`() = runBlocking<Unit> {
        val owner = newUser()
        val recipe = boilRecipe(owner)
        val batch = BatchRepository.create(owner.id, NewBatch(recipeId = recipe.id, measuredOg = 1.050, measuredFg = 1.010))!!
        SingleUser.email = owner.email

        listOf("boil", "pkg", "cur", "stop").forEach { press("$it:${recipe.no}:${batch.no}") }

        BoilTimerRepository.find(batch.id).shouldBeNull()
        BatchRepository.findById(batch.id, owner.id)!!.packagedDate.shouldBeNull()
        api.sent.map { it.text }.forAll { it shouldContain "read-only showcase" }
    }

    private fun boilRecipe(user: User) = newRecipe(
        user,
        hops = listOf(RecipeHop(name = "Magnum", amountG = 20.0, alphaAcid = 12.0, usage = HopUsage.BOIL, boilTimeMin = 60)),
    )

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
