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
import uk.beertool.web.fmt
import uk.beertool.web.paginate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val BOT_COMMANDS = listOf(
    BotCommand("recipes", "Browse recipes"),
    BotCommand("find", "Search recipes, e.g. /find citra"),
    BotCommand("log", "Brew log"),
    BotCommand("brew", "Today's brew: water, mash, boil, readings"),
    BotCommand("help", "What I can do"),
    BotCommand("unlink", "Disconnect this Telegram account"),
)

private const val LIST_PAGE_SIZE = 8
private const val SEARCH_LIMIT = 10
private const val BUTTON_TEXT_MAX = 60

private const val NOT_LINKED =
    "This Telegram account isn't linked to beertool yet. Open <b>Settings</b> in beertool and tap <b>Connect Telegram</b>."
private const val READ_ONLY =
    "This is a read-only showcase. To log brews, link your own account from <b>Settings</b> in beertool."

class TelegramBot(
    private val api: TelegramApi,
    private val publicUrl: String? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val log = LoggerFactory.getLogger(TelegramBot::class.java)

    private class Chatter(val telegramUserId: Long, val chatId: Long, val user: User?, val link: TelegramLink?)

    private fun chatter(from: TgUser, chatId: Long): Chatter {
        val link = TelegramLinkRepository.findByTelegramUser(from.id)
        val user = link?.let { UserRepository.findById(it.userId) } ?: SingleUser.defaultUser()?.readOnly()
        return Chatter(from.id, chatId, user, link?.takeIf { user?.canWrite == true })
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
        val who = chatter(from, message.chat.id)

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
                        ?: say(who, "Which one? e.g. <code>/recipe 12</code>")
                    "log" -> logPage(user, 1).let { (text, keys) -> say(who, text, keys) }
                    "brew" -> onBrew(who, user)
                    else -> say(who, "I don't know that one. Try /help.")
                }
            }
        }
    }

    private suspend fun onStart(who: Chatter, token: String) {
        if (token.isBlank()) return say(who, "Cheers! 🍺\n\n" + helpText(who))
        val link = TelegramLinkRepository.redeem(token, who.telegramUserId, who.chatId, clock())
        val user = link?.let { UserRepository.findById(it.userId) }
        if (user == null) say(who, "That link has expired or was already used. Make a new one from <b>Settings</b> in beertool.")
        else say(who, "Linked to ${user.displayName.escapeHtml()}'s beertool account. 🍺\n\n" + helpText(chatter(TgUser(who.telegramUserId), who.chatId)))
    }

    private suspend fun onUnlink(who: Chatter) {
        if (who.link == null) return say(who, "This Telegram account isn't linked.")
        TelegramLinkRepository.unlinkUser(who.link.userId)
        say(who, "Unlinked. You can connect again from <b>Settings</b> in beertool.")
    }

    private fun helpText(who: Chatter): String {
        val status = when {
            who.link != null -> "Linked to ${who.user!!.displayName.escapeHtml()}."
            who.user != null -> "Browsing ${who.user.displayName.escapeHtml()}'s recipes read-only."
            else -> NOT_LINKED
        }
        return status + "\n\n" + BOT_COMMANDS.joinToString("\n") { "/${it.command} - ${it.description}" } +
            if (who.link != null) "\n\nOn brew day, just send readings: <code>og 1.052</code>, <code>post 23</code>, <code>note …</code>" else ""
    }

    private suspend fun onFind(who: Chatter, user: User, term: String) {
        if (term.isBlank()) return say(who, "What should I look for? e.g. <code>/find citra</code>")
        val found = RecipeRepository.search(user.id, term, user.preferences.whirlpool).take(SEARCH_LIMIT)
        if (found.isEmpty()) return say(who, "Nothing matches “${term.escapeHtml()}”.")
        say(who, "Recipes matching “${term.escapeHtml()}”:", keyboard(*found.map { listOf(button(recipeLine(it).short(), "r:${it.no}")) }.toTypedArray()))
    }

    private suspend fun showRecipe(who: Chatter, user: User, no: Int) {
        val recipe = RecipeRepository.findByNo(no, user.id) ?: return say(who, "There's no recipe #$no.")
        say(who, recipeCard(recipe, recipe.stats(user.preferences.whirlpool)), recipeKeyboard(user, recipe))
    }

    private suspend fun showBatch(who: Chatter, user: User, recipe: Recipe, batch: Batch) {
        say(who, batchCard(recipe, batch, recipe.stats(user.preferences.whirlpool)), batchKeyboard(user, recipe, batch))
    }

    private suspend fun showBrewDay(who: Chatter, user: User, recipe: Recipe, batch: Batch) {
        say(who, brewDayCard(recipe, batch, user.preferences), brewDayKeyboard(recipe, batch))
    }

    private suspend fun onBrew(who: Chatter, user: User) {
        if (!user.canWrite) return say(who, READ_ONLY)
        val open = openBatches(user)
        val current = currentBatch(who, user)
        if (open.size > 1) {
            say(who, "Brews on the go:", keyboard(*open.map { (recipe, batch) ->
                val mark = if (batch.id == current?.second?.id) "✓ " else ""
                listOf(button("$mark${recipe.name} #${recipe.no}/${batch.no} · ${batchStatus(batch)}".short(), "cur:${recipe.no}:${batch.no}"))
            }.toTypedArray()))
        }
        when {
            current != null -> showBrewDay(who, user, current.first, current.second)
            open.isEmpty() -> say(who, "Nothing on the go. Pick a recipe with /recipes and tap <b>Start brew day</b>.")
        }
    }

    private suspend fun onReadings(who: Chatter, text: String) {
        val user = who.user ?: return say(who, NOT_LINKED)
        if (!user.canWrite) return say(who, READ_ONLY)
        val readings = when (val parsed = parseReadings(text)) {
            is ReadingsParse.NotReadings -> return say(who, "I didn't catch that. Send readings like <code>og 1.052</code>, or try /help.")
            is ReadingsParse.Invalid -> return say(who, parsed.reason.escapeHtml())
            is ReadingsParse.Ok -> parsed.readings
        }
        val (recipe, batch) = currentBatch(who, user)
            ?: return say(who, if (openBatches(user).isEmpty()) "No brew is on the go. Start one from /recipes." else "Which brew is this for? Pick one with /brew.")
        val saved = BatchRepository.update(batch.id, user.id, readings.applyTo(batch.toDraft())) ?: return
        TelegramLinkRepository.setCurrentBatch(who.telegramUserId, saved.id)
        say(who, "Saved ✓\n\n" + batchCard(recipe, saved, recipe.stats(user.preferences.whirlpool)), batchKeyboard(user, recipe, saved))
    }

    private fun openBatches(user: User): List<Pair<Recipe, Batch>> =
        BatchRepository.listByUser(user.id)
            .filter { it.batch.packagedDate == null }
            .mapNotNull { s -> RecipeRepository.findById(s.batch.recipeId, user.id)?.let { it to s.batch } }

    // The chosen brew, or the only one on the go when nothing has been chosen.
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
            val who = chatter(query.from, message.chat.id)
            val user = who.user ?: return say(who, NOT_LINKED)
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
                    if (batches.isEmpty()) say(who, "${recipe.name.escapeHtml()} hasn't been brewed yet.")
                    else say(who, "Brews of ${recipe.name.escapeHtml()}:", keyboard(*batches.map {
                        listOf(button("Batch ${it.no} · ${fmt(it.brewDate)} · ${batchStatus(it)}", "b:${recipe.no}:${it.no}"))
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
                        "Boil timer on: ${recipe.boilTimeMin} min. I'll message you at each addition and at flame-out.",
                        keyboard(listOf(button("Stop boil timer", "stop:${recipe.no}:${batch.no}"))),
                    )
                    tickBoilTimers()
                }
                "stop" -> batchOf(user, n1, n2)?.let { (_, batch) ->
                    notice = if (BoilTimerRepository.stop(batch.id)) "Boil timer stopped" else "No boil timer running"
                }
                "pkg" -> batchOf(user, n1, n2)?.let { (recipe, batch) ->
                    if (batch.measuredFg == null) return say(who, "Log the final gravity first, e.g. <code>fg 1.010</code>.")
                    val packaged = BatchRepository.markPackaged(batch.id, user.id, today()) ?: return
                    notice = "Packaged 🍻"
                    showBatch(who, user, recipe, packaged)
                }
            }
        } finally {
            api.answerCallbackQuery(query.id, notice)
        }
    }

    private suspend fun onInlineQuery(query: InlineQuery) {
        val user = chatter(query.from, query.from.id).user ?: return api.answerInlineQuery(query.id, emptyList())
        val term = query.query.trim()
        val summaries = if (term.isEmpty()) RecipeRepository.listByUser(user.id, user.preferences.whirlpool)
        else RecipeRepository.search(user.id, term, user.preferences.whirlpool)
        val results = summaries.take(SEARCH_LIMIT).mapNotNull { s ->
            val recipe = RecipeRepository.findById(s.id, user.id) ?: return@mapNotNull null
            InlineQueryResultArticle(
                id = recipe.no.toString(),
                title = recipe.name,
                description = listOfNotNull(recipe.style, statsLine(s.stats)).joinToString("\n"),
                inputMessageContent = InputTextMessageContent(recipeCard(recipe, s.stats)),
                replyMarkup = publicUrl?.let { keyboard(listOf(linkButton("Open in beertool", "$it/recipes/${recipe.no}"))) },
            )
        }
        api.answerInlineQuery(query.id, results)
    }

    suspend fun tickBoilTimers() {
        val now = clock()
        for (timer in BoilTimerRepository.all()) {
            try {
                val recipe = BatchRepository.findById(timer.batchId, timer.userId)
                    ?.let { RecipeRepository.findById(it.recipeId, timer.userId) }
                    ?: run { BoilTimerRepository.stop(timer.batchId); null }
                    ?: continue
                val alert = timer.alertAt(now, recipe.boilAdditions()) ?: continue
                api.sendMessage(timer.chatId, boilAlertText(recipe, alert, timer.minutesLeft(now)))
                if (alert.flameOut) BoilTimerRepository.stop(timer.batchId)
                else BoilTimerRepository.advance(timer.batchId, alert.announcedDownTo)
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
        val text = if (p.total == 0) "No recipes yet." else "Recipes · page ${p.page} of ${p.pageCount}"
        return text to keyboard(
            *p.items.map { listOf(button(recipeLine(it).short(), "r:${it.no}")) }.toTypedArray(),
            pager(p.page, p.hasPrev, p.hasNext, "rl"),
        )
    }

    private fun logPage(user: User, page: Int): Pair<String, InlineKeyboardMarkup> {
        val p = BatchRepository.listByUser(user.id).paginate(page, LIST_PAGE_SIZE)
        val text = if (p.total == 0) "Nothing brewed yet." else "Brew log · page ${p.page} of ${p.pageCount}"
        return text to keyboard(
            *p.items.map { listOf(button(batchLine(it).short(), "b:${it.recipeNo}:${it.batch.no}")) }.toTypedArray(),
            pager(p.page, p.hasPrev, p.hasNext, "ll"),
        )
    }

    private fun pager(page: Int, hasPrev: Boolean, hasNext: Boolean, action: String) = listOfNotNull(
        button("« Prev", "$action:${page - 1}").takeIf { hasPrev },
        button("Next »", "$action:${page + 1}").takeIf { hasNext },
    )

    private fun recipeKeyboard(user: User, recipe: Recipe) = keyboard(
        listOfNotNull(
            button("Brews", "rb:${recipe.no}"),
            button("Start brew day", "sb:${recipe.no}").takeIf { user.canWrite },
        ),
        listOfNotNull(publicUrl?.let { linkButton("Open in beertool", "$it/recipes/${recipe.no}") }),
    )

    private fun batchKeyboard(user: User, recipe: Recipe, batch: Batch) = keyboard(
        listOfNotNull(
            button("Brew day", "cur:${recipe.no}:${batch.no}").takeIf { user.canWrite && batch.packagedDate == null },
            button("Mark packaged", "pkg:${recipe.no}:${batch.no}")
                .takeIf { user.canWrite && batch.packagedDate == null && batch.measuredFg != null },
        ),
        listOfNotNull(publicUrl?.let { linkButton("Open in beertool", "$it/recipes/${recipe.no}/batches/${batch.no}") }),
    )

    private fun brewDayKeyboard(recipe: Recipe, batch: Batch) = keyboard(
        listOf(
            if (BoilTimerRepository.find(batch.id) == null) button("Start boil timer", "boil:${recipe.no}:${batch.no}")
            else button("Stop boil timer", "stop:${recipe.no}:${batch.no}"),
            button("Readings so far", "b:${recipe.no}:${batch.no}"),
        ),
        listOfNotNull(publicUrl?.let { linkButton("Open brew day page", "$it/recipes/${recipe.no}/batches/${batch.no}/brew") }),
    )

    private suspend fun withUser(who: Chatter, block: suspend (User) -> Unit) {
        val user = who.user ?: return say(who, NOT_LINKED)
        block(user)
    }

    private suspend fun say(who: Chatter, text: String, keyboard: InlineKeyboardMarkup? = null) =
        api.sendMessage(who.chatId, text, keyboard)

    private fun today(): LocalDate = clock().atZone(zone).toLocalDate()

    private fun String.short() = if (length <= BUTTON_TEXT_MAX) this else take(BUTTON_TEXT_MAX - 1) + "…"

    private companion object {
        val WRITE_ACTIONS = setOf("sb", "cur", "boil", "stop", "pkg")
    }
}
