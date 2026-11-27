package uk.beertool.telegram

import uk.beertool.batch.Batch
import uk.beertool.batch.BatchRepository
import uk.beertool.common.paginate
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.stats
import uk.beertool.user.SingleUser
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import uk.beertool.user.readOnly
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val BOT_COMMANDS = listOf(
    BotCommand("recipes", "Browse recipes"),
    BotCommand("find", "Search by name or ingredient"),
    BotCommand("log", "Brew log"),
    BotCommand("help", "Commands"),
    BotCommand("unlink", "Disconnect Telegram"),
)

private val LINKED_ONLY_COMMANDS = setOf("unlink")

private const val LIST_PAGE_SIZE = 8
private const val SEARCH_LIMIT = 10
private const val BUTTON_TEXT_MAX = 60

class TelegramBot(
    private val api: TelegramApi,
    private val publicUrl: String? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
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

        // Anything that isn't a command is taken as a search, the one thing worth typing here.
        if (!text.startsWith("/")) return withUser(who) { user -> onFind(who, user, text) }
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
        say(who, recipeCard(recipe, recipe.stats(user.preferences.whirlpool), publicUrl), recipeKeyboard(recipe))
    }

    private suspend fun showBatch(who: Chatter, user: User, recipe: Recipe, batch: Batch) {
        say(who, batchCard(recipe, batch, recipe.stats(user.preferences.whirlpool), today(), publicUrl))
    }

    private suspend fun onCallback(query: CallbackQuery) {
        try {
            val message = query.message ?: return
            if (!message.chat.isPrivate) return
            val who = chatter(query.from.id, message.chat.id)
            val user = who.user ?: return say(who, notLinked())
            val parts = query.data.orEmpty().split(':')
            val n1 = parts.getOrNull(1)?.toIntOrNull()
            val n2 = parts.getOrNull(2)?.toIntOrNull()
            val action = parts.first()

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
            }
        } finally {
            api.answerCallbackQuery(query.id, null)
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

    private fun recipeKeyboard(recipe: Recipe) = keyboard(listOf(button("📚 Brews", "rb:${recipe.no}")))

    private suspend fun withUser(who: Chatter, block: suspend (User) -> Unit) {
        val user = who.user ?: return say(who, notLinked())
        block(user)
    }

    private fun settings() = publicUrl?.let { "<a href=\"${"$it/settings".escapeAttribute()}\">Settings</a>" } ?: "<b>Settings</b>"

    private fun notLinked() = "🔒 This Telegram account isn't connected to beertool yet. " +
        "Open ${settings()} on the website and tap <b>Connect Telegram</b>."

    private suspend fun say(who: Chatter, text: String, keyboard: InlineKeyboardMarkup? = null) {
        api.sendMessage(who.chatId, text, keyboard)
    }

    private fun today(): LocalDate = clock().atZone(zone).toLocalDate()

    private fun String.short() = shortened(BUTTON_TEXT_MAX)
}
