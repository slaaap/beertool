package uk.beertool.telegram

import org.slf4j.LoggerFactory
import uk.beertool.batch.Batch
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.toDraft
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.boilAdditions
import uk.beertool.recipe.stats
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import uk.beertool.user.readOnly
import uk.beertool.web.SingleUser
import uk.beertool.web.paginate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val BOT_COMMANDS = listOf(
    BotCommand("recipes", "Browse recipes"),
    BotCommand("find", "Search by name or ingredient"),
    BotCommand("log", "Brew log"),
    BotCommand("brew", "Brew day: plan, boil timer, readings"),
    BotCommand("help", "Commands"),
    BotCommand("unlink", "Disconnect Telegram"),
)

private val LINKED_ONLY_COMMANDS = setOf("brew", "unlink")

private val BREW_DAY_GUIDE = """
    🔥 <b>Brew day</b>
    <i>Nothing brewing right now. Here's how it works:</i>

    1. Pick a recipe in /recipes and tap <b>Start brew day</b>.
    2. You get its water, mash and boil plan.
    3. Tap <b>Start boil timer</b> and I'll message you at each addition and at flame-out.
    4. Send readings as plain messages: $READINGS_HINT
    5. Once the FG is in, tap <b>Mark packaged</b>.
""".trimIndent()

private const val LIST_PAGE_SIZE = 8
private const val SEARCH_LIMIT = 10
private const val BUTTON_TEXT_MAX = 60

private const val READ_ONLY = "🔒 This is a read-only showcase, so brewing and logging are switched off."

class TelegramBot(
    private val api: TelegramApi,
    private val publicUrl: String? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val log = LoggerFactory.getLogger(TelegramBot::class.java)

    private class Chatter(val telegramUserId: Long, val chatId: Long, val user: User?, val link: TelegramLink?)

    private fun chatter(telegramUserId: Long, chatId: Long): Chatter {
        val link = TelegramLinkRepository.findByTelegramUser(telegramUserId)
        val user = link?.let { UserRepository.findById(it.userId) } ?: SingleUser.defaultUser()?.readOnly()
        return Chatter(telegramUserId, chatId, user, link?.takeIf { user?.canWrite == true })
    }

    suspend fun handle(update: Update) {
        update.message?.let { onMessage(it) }
        update.callbackQuery?.let { onCallback(it) }
        update.inlineQuery?.let { onInlineQuery(it) }
    }

    // Groups are ignored: a linked account can write, and a group would hand that to everyone in it.
    private suspend fun onMessage(message: Message) {
        val from = message.from ?: return
        val text = message.text?.trim() ?: return
        if (!message.chat.isPrivate || from.isBot) return
        val who = chatter(from.id, message.chat.id)

        if (!text.startsWith("/")) return onReadings(who, text)
        val command = text.substringBefore(' ').drop(1).substringBefore('@').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        when (command) {
            "start" -> onStart(who, arg)
            "help" -> say(who, helpText(who))
            "unlink" -> onUnlink(who)
            else -> withUser(who) { user ->
                when (command) {
                    "recipes" -> recipeListPage(user, 1).let { (text, keys) -> say(who, text, keys) }
                    "find" -> onFind(who, user, arg)
                    "recipe" -> arg.removePrefix("#").toIntOrNull()?.let { showRecipe(who, user, it) }
                        ?: say(who, "Which recipe? Send its number, e.g. <code>/recipe 12</code>, or browse /recipes.")
                    "log" -> logPage(user, 1).let { (text, keys) -> say(who, text, keys) }
                    "brew" -> onBrew(who, user)
                    else -> say(who, "🤔 I don't know that command. /help lists them all.")
                }
            }
        }
    }

    private suspend fun onStart(who: Chatter, token: String) {
        if (token.isBlank()) return say(who, "🍺 <b>Welcome to beertool</b>\n" + helpText(who))
        val link = TelegramLinkRepository.redeem(token, who.telegramUserId, who.chatId, clock())
        val user = link?.let { UserRepository.findById(it.userId) }
        if (user == null) say(who, "⚠️ That link has expired or was already used. Get a fresh one from ${settings()} → <b>Connect Telegram</b>.")
        else say(who, "✅ <b>Connected</b>\n" + helpText(chatter(who.telegramUserId, who.chatId)))
    }

    private suspend fun onUnlink(who: Chatter) {
        if (who.link == null) return say(who, "This Telegram account isn't connected.")
        TelegramLinkRepository.unlinkUser(who.link.userId)
        say(who, "👋 Disconnected. Connect again any time from ${settings()}.")
    }

    private fun helpText(who: Chatter): String {
        val status = when {
            who.link != null -> "Connected as ${who.user!!.displayName.escapeHtml()}."
            who.user != null -> "Browsing ${who.user.displayName.escapeHtml()}'s recipes, read-only."
            else -> return notLinked()
        }
        val commands = if (who.link != null) BOT_COMMANDS else BOT_COMMANDS.filterNot { it.command in LINKED_ONLY_COMMANDS }
        return "<i>$status</i>\n\n" + commands.joinToString("\n") { "/${it.command} - ${it.description}" }
    }

    private suspend fun onFind(who: Chatter, user: User, term: String) {
        if (term.isBlank()) return say(who, "🔎 What should I look for? Send e.g. <code>/find citra</code>")
        val found = RecipeRepository.search(user.id, term, user.preferences.whirlpool)
        val quoted = "“${term.escapeHtml()}”"
        if (found.isEmpty()) return say(who, "🔎 Nothing matches $quoted. Try a hop, malt or style.")
        val heading = when {
            found.size > SEARCH_LIMIT -> "🔎 <b>Top $SEARCH_LIMIT of ${found.size} matches for $quoted</b>"
            found.size == 1 -> "🔎 <b>1 match for $quoted</b>"
            else -> "🔎 <b>${found.size} matches for $quoted</b>"
        }
        say(who, heading, keyboard(*found.take(SEARCH_LIMIT).map { listOf(button(recipeLine(it).short(), "r:${it.no}")) }.toTypedArray()))
    }

    private suspend fun showRecipe(who: Chatter, user: User, no: Int) {
        val recipe = RecipeRepository.findByNo(no, user.id) ?: return say(who, "⚠️ There's no recipe #$no. Browse /recipes.")
        say(who, recipeCard(recipe, recipe.stats(user.preferences.whirlpool), publicUrl), recipeKeyboard(user, recipe))
    }

    private suspend fun showBatch(who: Chatter, user: User, recipe: Recipe, batch: Batch) {
        say(who, batchCard(recipe, batch, recipe.stats(user.preferences.whirlpool), today(), publicUrl), batchKeyboard(user, recipe, batch))
    }

    private suspend fun showBrewDay(who: Chatter, user: User, recipe: Recipe, batch: Batch) {
        say(who, brewDayCard(recipe, batch, user.preferences, today(), publicUrl), brewDayKeyboard(recipe, batch))
    }

    private suspend fun onBrew(who: Chatter, user: User) {
        if (!user.canWrite) return say(who, READ_ONLY)
        val open = openBatches(user)
        val current = currentBatch(who, user)
        if (open.size > 1) {
            val heading = if (current == null) "🔥 <b>Which brew?</b>\n<i>Pick the one you're working on.</i>" else "🔥 <b>Brews on the go</b>"
            say(who, heading, keyboard(*open.map { (recipe, batch) ->
                val mark = if (batch.id == current?.second?.id) "✓ " else ""
                listOf(button("$mark${friendlyDate(batch.brewDate, today())} · ${recipe.name}".short(), "cur:${recipe.no}:${batch.no}"))
            }.toTypedArray()))
        }
        when {
            current != null -> showBrewDay(who, user, current.first, current.second)
            open.isEmpty() -> say(who, BREW_DAY_GUIDE)
        }
    }

    private suspend fun onReadings(who: Chatter, text: String) {
        val user = who.user ?: return say(who, notLinked())
        val readings = when (val parsed = parseReadings(text)) {
            is ReadingsParse.NotReadings -> return say(who, "🤔 Not sure what to do with that. /help lists the commands.")
            is ReadingsParse.Invalid -> return say(who, "⚠️ ${parsed.reason.escapeHtml()}")
            is ReadingsParse.Ok -> parsed.readings
        }
        if (!user.canWrite) return say(who, READ_ONLY)
        val (recipe, batch) = currentBatch(who, user)
            ?: return say(who, if (openBatches(user).isEmpty()) "Nothing is brewing. Start a brew day from /recipes first." else "Which brew is that for? Pick one in /brew, then send it again.")
        val saved = BatchRepository.update(batch.id, user.id, readings.applyTo(batch.toDraft()))
            ?: return say(who, "⚠️ That didn't save. Please send it again.")
        TelegramLinkRepository.setCurrentBatch(who.telegramUserId, saved.id)
        say(who, readingsSaved(readings) + "\n\n" + batchCard(recipe, saved, recipe.stats(user.preferences.whirlpool), today(), publicUrl), batchKeyboard(user, recipe, saved))
    }

    private fun openBatches(user: User): List<Pair<Recipe, Batch>> =
        BatchRepository.listByUser(user.id)
            .filter { it.batch.packagedDate == null }
            .mapNotNull { s -> RecipeRepository.findById(s.batch.recipeId, user.id)?.let { it to s.batch } }

    private fun currentBatch(who: Chatter, user: User): Pair<Recipe, Batch>? {
        val chosen = who.link?.currentBatchId?.let { BatchRepository.findById(it, user.id) }?.takeIf { it.packagedDate == null }
        val batch = chosen ?: openBatches(user).singleOrNull()?.second ?: return null
        return RecipeRepository.findById(batch.recipeId, user.id)?.let { it to batch }
    }

    private suspend fun onCallback(query: CallbackQuery) {
        var notice: String? = null
        try {
            val message = query.message ?: return
            if (!message.chat.isPrivate) return
            val who = chatter(query.from.id, message.chat.id)
            val user = who.user ?: return say(who, notLinked())
            val parts = query.data.orEmpty().split(':')
            val n1 = parts.getOrNull(1)?.toIntOrNull()
            val n2 = parts.getOrNull(2)?.toIntOrNull()
            val action = parts.first()
            if (action in WRITE_ACTIONS && !user.canWrite) return say(who, READ_ONLY)

            when (action) {
                "rl" -> recipeListPage(user, n1 ?: 1).let { (text, keys) -> api.editMessageText(who.chatId, message.messageId, text, keys) }
                "ll" -> logPage(user, n1 ?: 1).let { (text, keys) -> api.editMessageText(who.chatId, message.messageId, text, keys) }
                "r" -> n1?.let { showRecipe(who, user, it) }
                "rb" -> recipeOf(user, n1)?.let { recipe ->
                    val batches = BatchRepository.listByRecipe(recipe.id, user.id)
                    if (batches.isEmpty()) say(who, "📚 ${recipeName(recipe).escapeHtml()} hasn't been brewed yet.")
                    else say(who, "📚 <b>Brews of ${recipeName(recipe).escapeHtml()}</b>", keyboard(*batches.map {
                        listOf(button("${friendlyDate(it.brewDate, today())}${activeMark(it)}", "b:${recipe.no}:${it.no}"))
                    }.toTypedArray()))
                }
                "b" -> batchOf(user, n1, n2)?.let { (recipe, batch) -> showBatch(who, user, recipe, batch) }
                "sb" -> recipeOf(user, n1)?.let { recipe ->
                    val batch = BatchRepository.startBrewDay(recipe.id, user.id, today()) ?: return
                    TelegramLinkRepository.setCurrentBatch(who.telegramUserId, batch.id)
                    showBrewDay(who, user, recipe, batch)
                }
                "cur" -> batchOf(user, n1, n2)?.let { (recipe, batch) ->
                    TelegramLinkRepository.setCurrentBatch(who.telegramUserId, batch.id)
                    showBrewDay(who, user, recipe, batch)
                }
                "boil" -> batchOf(user, n1, n2)?.let { (recipe, batch) ->
                    BoilTimerRepository.start(batch.id, user.id, who.chatId, recipe.boilTimeMin, clock())
                    TelegramLinkRepository.setCurrentBatch(who.telegramUserId, batch.id)
                    say(
                        who,
                        "⏱ <b>Boil timer started</b> · ${recipe.boilTimeMin} min\n<i>I'll message you at each addition and at flame-out.</i>",
                        keyboard(listOf(button("⏹ Stop boil timer", "stop:${recipe.no}:${batch.no}"))),
                    )
                    tickBoilTimers()
                }
                "stop" -> batchOf(user, n1, n2)?.let { (_, batch) ->
                    notice = if (BoilTimerRepository.stop(batch.id)) "Boil timer stopped" else "No boil timer running"
                }
                "pkg" -> batchOf(user, n1, n2)?.let { (recipe, batch) ->
                    if (batch.measuredFg == null) return say(who, "⚠️ Send the final gravity first, e.g. <code>fg 1.010</code>")
                    val packaged = BatchRepository.markPackaged(batch.id, user.id, today()) ?: return
                    BoilTimerRepository.stop(batch.id)
                    notice = "Packaged 🍻"
                    showBatch(who, user, recipe, packaged)
                }
            }
        } finally {
            api.answerCallbackQuery(query.id, notice)
        }
    }

    private suspend fun onInlineQuery(query: InlineQuery) {
        val user = chatter(query.from.id, query.from.id).user ?: return api.answerInlineQuery(query.id, emptyList())
        val term = query.query.trim()
        val summaries = if (term.isEmpty()) RecipeRepository.listByUser(user.id, user.preferences.whirlpool)
        else RecipeRepository.search(user.id, term, user.preferences.whirlpool)
        val results = summaries.take(SEARCH_LIMIT).mapNotNull { s ->
            val recipe = RecipeRepository.findById(s.id, user.id) ?: return@mapNotNull null
            InlineQueryResultArticle(
                id = recipe.no.toString(),
                title = recipe.name,
                description = listOfNotNull(recipe.style, statsLine(s.stats)).joinToString("\n"),
                inputMessageContent = InputTextMessageContent(recipeCard(recipe, s.stats, publicUrl)),
            )
        }
        api.answerInlineQuery(query.id, results)
    }

    suspend fun tickBoilTimers() {
        val now = clock()
        for (timer in BoilTimerRepository.all()) {
            try {
                val batch = BatchRepository.findById(timer.batchId, timer.userId)?.takeIf { it.packagedDate == null }
                val recipe = batch?.let { RecipeRepository.findById(it.recipeId, timer.userId) }
                    ?: run { BoilTimerRepository.stop(timer.batchId); null }
                    ?: continue
                val alert = timer.alertAt(now, recipe.boilAdditions()) ?: continue
                val claimed = if (alert.flameOut) BoilTimerRepository.stop(timer.batchId)
                else BoilTimerRepository.claim(timer.batchId, timer.announcedDownTo, alert.announcedDownTo)
                if (claimed) api.sendMessage(timer.chatId, boilAlertText(recipe, alert, timer.minutesLeft(now)))
            } catch (e: Exception) {
                log.warn("Boil timer for batch {} failed: {}", timer.batchId, e.message)
            }
        }
    }

    private fun recipeOf(user: User, no: Int?) = no?.let { RecipeRepository.findByNo(it, user.id) }

    private fun batchOf(user: User, recipeNo: Int?, no: Int?): Pair<Recipe, Batch>? {
        val recipe = recipeOf(user, recipeNo) ?: return null
        val batch = no?.let { BatchRepository.findByRecipeAndNo(recipe.id, it, user.id) } ?: return null
        return recipe to batch
    }

    private fun recipeListPage(user: User, page: Int): Pair<String, InlineKeyboardMarkup> {
        val p = RecipeRepository.listByUser(user.id, user.preferences.whirlpool).paginate(page, LIST_PAGE_SIZE)
        val text = if (p.total == 0) "📚 No recipes yet." else "📚 <b>Recipes</b>\n<i>Page ${p.page} of ${p.pageCount}</i>"
        return text to keyboard(
            *p.items.map { listOf(button(recipeLine(it).short(), "r:${it.no}")) }.toTypedArray(),
            pager(p.page, p.hasPrev, p.hasNext, "rl"),
        )
    }

    private fun logPage(user: User, page: Int): Pair<String, InlineKeyboardMarkup> {
        val p = BatchRepository.listByUser(user.id).paginate(page, LIST_PAGE_SIZE)
        val text = if (p.total == 0) "📚 Nothing brewed yet." else "📚 <b>Brew log</b>\n<i>Page ${p.page} of ${p.pageCount}</i>"
        return text to keyboard(
            *p.items.map { listOf(button(batchLine(it, today()).short(), "b:${it.recipeNo}:${it.batch.no}")) }.toTypedArray(),
            pager(p.page, p.hasPrev, p.hasNext, "ll"),
        )
    }

    private fun pager(page: Int, hasPrev: Boolean, hasNext: Boolean, action: String) = listOfNotNull(
        button("« Prev", "$action:${page - 1}").takeIf { hasPrev },
        button("Next »", "$action:${page + 1}").takeIf { hasNext },
    )

    private fun recipeKeyboard(user: User, recipe: Recipe) = keyboard(
        listOfNotNull(
            button("📚 Brews", "rb:${recipe.no}"),
            button("🔥 Start brew day", "sb:${recipe.no}").takeIf { user.canWrite },
        ),
    )

    private fun batchKeyboard(user: User, recipe: Recipe, batch: Batch) = keyboard(
        listOfNotNull(
            button("🔥 Brew day", "cur:${recipe.no}:${batch.no}").takeIf { user.canWrite && batch.packagedDate == null },
            button("🍺 Mark packaged", "pkg:${recipe.no}:${batch.no}")
                .takeIf { user.canWrite && batch.packagedDate == null && batch.measuredFg != null },
        ),
    )

    private fun brewDayKeyboard(recipe: Recipe, batch: Batch) = keyboard(
        listOf(
            if (BoilTimerRepository.find(batch.id) == null) button("⏱ Start boil timer", "boil:${recipe.no}:${batch.no}")
            else button("⏹ Stop boil timer", "stop:${recipe.no}:${batch.no}"),
            button("📊 Readings", "b:${recipe.no}:${batch.no}"),
        ),
    )

    private suspend fun withUser(who: Chatter, block: suspend (User) -> Unit) {
        val user = who.user ?: return say(who, notLinked())
        block(user)
    }

    private fun settings() = publicUrl?.let { "<a href=\"${"$it/settings".escapeAttribute()}\">Settings</a>" } ?: "<b>Settings</b>"

    private fun notLinked() = "🔒 This Telegram account isn't connected to beertool yet. " +
        "Open ${settings()} on the website and tap <b>Connect Telegram</b>."

    private suspend fun say(who: Chatter, text: String, keyboard: InlineKeyboardMarkup? = null) =
        api.sendMessage(who.chatId, text, keyboard)

    private fun today(): LocalDate = clock().atZone(zone).toLocalDate()

    private fun String.short() = shortened(BUTTON_TEXT_MAX)

    private companion object {
        val WRITE_ACTIONS = setOf("sb", "cur", "boil", "stop", "pkg")
    }
}
