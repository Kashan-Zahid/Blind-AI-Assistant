package com.example.blindaassistant

import kotlinx.coroutines.CompletableDeferred

/**
 * Safety confirmation layer.
 * Intercepts dangerous actions and requires explicit spoken confirmation.
 *
 * Dangerous actions:
 *   - Sending messages (WhatsApp, SMS)
 *   - Deleting files
 *   - Calling unknown contacts
 *   - Emergency SOS
 *   - Ambiguous contact resolution
 */
class SafetyLayer {

    data class PendingAction(
        val description: String,
        val confirmationPrompt: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private var pendingAction: PendingAction? = null

    /**
     * Call this before any dangerous action.
     * Returns a spoken prompt to read to the user.
     * The caller must await `awaitConfirmation()` for the result.
     */
    fun requestConfirmation(
        actionDescription: String,
        prompt: String
    ): Pair<String, CompletableDeferred<Boolean>> {
        val deferred = CompletableDeferred<Boolean>()
        pendingAction = PendingAction(actionDescription, prompt, deferred)
        return Pair(prompt, deferred)
    }

    /**
     * Called when the user speaks. Returns true if confirmation was resolved.
     */
    fun handleUserResponse(userInput: String): Boolean {
        val action = pendingAction ?: return false
        val lower = userInput.trim().lowercase()

        return when {
            isConfirmation(lower) -> {
                action.deferred.complete(true)
                pendingAction = null
                true
            }
            isCancellation(lower) -> {
                action.deferred.complete(false)
                pendingAction = null
                true
            }
            else -> false // not a confirmation response
        }
    }

    fun hasPendingAction(): Boolean = pendingAction != null

    fun getPendingPrompt(): String? = pendingAction?.confirmationPrompt

    fun cancelPending() {
        pendingAction?.deferred?.complete(false)
        pendingAction = null
    }

    private fun isConfirmation(input: String): Boolean {
        val confirmWords = listOf(
            "yes", "yeah", "yep", "confirm", "send", "ok", "okay",
            "do it", "proceed", "go ahead", "continue", "accept",
            "sure", "correct", "right", "haan", "ji", "ji haan"
        )
        return confirmWords.any { input == it || input.contains(it) }
    }

    private fun isCancellation(input: String): Boolean {
        val cancelWords = listOf(
            "no", "cancel", "stop", "abort", "nope", "nah", "don't",
            "do not", "back", "quit", "exit", "never mind", "nahi",
            "nai", "ruk ja", "band karo"
        )
        return cancelWords.any { input == it || input.contains(it) }
    }

    companion object {
        /**
         * Checks whether a command is safety-sensitive and needs confirmation.
         */
        fun isSensitiveCommand(input: String): Boolean {
            val sensitivePatterns = listOf(
                "send", "whatsapp", "sms", "message",
                "delete", "remove", "erase",
                "call unknown", "call new",
                "emergency", "sos",
                "purchase", "buy", "pay",
                "change password", "reset"
            )
            val lower = input.lowercase()
            return sensitivePatterns.any { lower.contains(it) }
        }

        /**
         * Build the read-back confirmation prompt for a message send action.
         */
        fun buildMessageConfirmation(recipient: String, message: String): String {
            return "I am about to send \"$message\" to $recipient. Say Send to confirm, or Cancel to stop."
        }

        /**
         * Build prompt for ambiguous contact disambiguation.
         */
        fun buildDisambiguationPrompt(name: String, matches: List<String>): String {
            val options = matches.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString(", ")
            return "I found multiple contacts named $name: $options. Which one do you mean? Say the number or full name."
        }

        /**
         * Build call confirmation prompt.
         */
        fun buildCallConfirmation(name: String, number: String): String {
            return "Calling $name at $number. Say Cancel within 5 seconds to stop."
        }

        /**
         * Build delete confirmation prompt.
         */
        fun buildDeleteConfirmation(itemName: String): String {
            return "Permanently delete $itemName? Say Yes to confirm or Cancel to stop."
        }
    }
}
