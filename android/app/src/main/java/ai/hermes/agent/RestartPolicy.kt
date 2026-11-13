package ai.hermes.agent

/**
 * Pure restart decisions, deliberately free of Android types so the JVM test
 * suite can lock down the two rules that protect chat history.
 *
 * The web UI keeps login state, session lists and drafts in localStorage, which
 * is scoped to the `host:port` origin. Moving to a different port across a
 * restart therefore looks like a brand-new empty site. Reloading the WebView
 * when nothing changed drops a reply the web UI has not flushed to disk yet.
 * Both must be avoided.
 */
object RestartPolicy {

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /**
     * Once a port has been pinned it is mandatory, even when it still looks
     * busy: a dying proot child can hold the socket for a moment, and hopping
     * to a free port used to silently discard every chat. Only the very first
     * launch (no pin yet) is allowed to scan for a free port.
     */
    fun portToUse(pinnedPort: Int, defaultPort: Int, isFree: (Int) -> Boolean): Int {
        if (pinnedPort in MIN_PORT..MAX_PORT) return pinnedPort
        val start = defaultPort.coerceIn(MIN_PORT, MAX_PORT)
        var candidate = start
        repeat(20) {
            if (isFree(candidate)) return candidate
            candidate = if (candidate < MAX_PORT) candidate + 1 else MIN_PORT
        }
        return start
    }

    /**
     * Scheme + authority only. The web UI is a hash-router SPA, so the loaded
     * page's URL usually differs from the launch URL (`#/chat/…` vs `#token=…`).
     * Comparing full strings would reload on every visit and lose messages;
     * only a genuine origin change (i.e. a new port) warrants a reload.
     */
    fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val start = schemeEnd + 3
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') return url.substring(0, i)
        }
        return url
    }

    /**
     * A reload is only warranted when the origin really changed, when no page
     * is loaded yet, or when the user is retrying after a load error. A restored
     * WebView already carries its page, so an extra `loadUrl` would throw away
     * the conversation sitting in its DOM.
     */
    fun shouldReloadWorkspace(
        loadedUrl: String?,
        currentUrl: String,
        restored: Boolean,
        errorVisible: Boolean,
    ): Boolean {
        if (currentUrl.isEmpty()) return false
        if (loadedUrl == null) return !restored
        if (!originOf(loadedUrl).equals(originOf(currentUrl), ignoreCase = true)) return true
        return errorVisible
    }
}
