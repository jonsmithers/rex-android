package dev.rexclient.android

import android.net.Uri
import org.json.JSONObject

/**
 * What a terminal block is doing, for telling blocks apart in the list: the
 * title a program set (OSC 0/2), the foreground process, and its directory.
 * Filled from `terminal.title`/`terminal.process`, then kept current by the
 * block's `title_changed`, `process_changed`, and `pwd_changed` events.
 */
data class BlockDetails(
    val title: String = "",
    val program: String? = null,
    val programPid: Int? = null,
    val processCwd: String? = null,
    val reportedPwd: String? = null,
    val reportedPwdPid: Int? = null,
    val home: String? = null,
) {
    /** True when the foreground process is a shell sitting at its prompt (or running a builtin). */
    val atShell get() = program == null || program in SHELLS

    /**
     * The directory to show. A directory reported with OSC 7 wins while the
     * process that reported it is still in the foreground, which covers ssh,
     * where the local process's cwd says nothing about the remote shell.
     */
    val cwd: String?
        get() = reportedPwd?.takeIf { reportedPwdPid != null && reportedPwdPid == programPid } ?: processCwd ?: reportedPwd

    /** The directory with the home directory shortened to ~. */
    val shortCwd: String?
        get() {
            val dir = cwd ?: return null
            val h = home?.trimEnd('/')
            return when {
                h.isNullOrEmpty() -> dir
                dir == h -> "~"
                dir.startsWith("$h/") -> "~" + dir.removePrefix(h)
                else -> dir
            }
        }

    /** A short "what's running where" line, such as `vim · ~/src` or just `~/src` at a prompt. */
    val activity: String?
        get() {
            val dir = shortCwd
            return when {
                atShell -> dir ?: program
                dir == null -> program
                else -> "$program · $dir"
            }
        }

    /** Applies a `foreground` process object from `terminal.process` or `process_changed`. */
    fun withProcess(fg: JSONObject?): BlockDetails =
        if (fg == null) this else copy(
            program = processLabel(fg),
            programPid = fg.optInt("pid").takeIf { fg.has("pid") },
            processCwd = fg.optString("cwd").ifEmpty { null },
        )

    /** Applies a `terminal.pwd` result or `pwd_changed` payload. */
    fun withPwd(p: JSONObject): BlockDetails {
        val pwd = p.optString("pwd")
        // OSC 7 sends file://host/path; a plain path is used as is.
        val path = if (pwd.startsWith("file://")) Uri.parse(pwd).path else pwd
        val owner = p.optJSONObject("owner")
        return copy(
            reportedPwd = path?.ifEmpty { null },
            reportedPwdPid = owner?.optInt("pid")?.takeIf { owner.has("pid") },
        )
    }

    companion object {
        private val SHELLS = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "tcsh", "csh", "nu", "pwsh", "login")

        /** Names a process the way the API suggests: script, then argv0, then name. */
        fun processLabel(p: JSONObject): String? {
            val raw = p.optString("script").ifEmpty { p.optString("argv0") }.ifEmpty { p.optString("name") }
            return raw.substringAfterLast('/').removePrefix("-").ifEmpty { null }
        }

        private val DEFAULT_WINDOW_LABEL = Regex("^Window [0-9a-f]{6,}$")

        /** False for the label Rex makes up from a window's ID, such as `Window 89abcdef`. */
        fun isCustomWindowLabel(label: String) = label.isNotBlank() && !DEFAULT_WINDOW_LABEL.matches(label)
    }
}

/**
 * The block's label, unless it's the generic one Rex gives every shell (the
 * flavor's name, such as `shell`) or one made from its ID.
 */
val BlockInfo.customLabel: String?
    get() = label.takeIf {
        it.isNotBlank() && !it.equals(flavor.substringAfterLast('.'), ignoreCase = true) && it != id.takeLast(8)
    }

/** The best one-line name for a block: its own label, the terminal's title, or what it's running. */
fun BlockInfo.displayTitle(d: BlockDetails?): String =
    customLabel ?: d?.title?.takeIf { it.isNotBlank() } ?: d?.activity ?: label

/** The second line: what's running where, plus the label when the title came from elsewhere. */
fun BlockInfo.displaySubtitle(d: BlockDetails?): String {
    val title = displayTitle(d)
    val parts = mutableListOf<String>()
    d?.activity?.takeIf { it != title }?.let { parts += it }
    if (!isTerminal) parts += creator
    return parts.joinToString("  ·  ")
}

/** `Tab 2` or `Tab 2 · editor`, when the tab has a label of its own. */
fun BlockInfo.tabName(): String =
    "Tab ${windowIndex + 1}" + if (BlockDetails.isCustomWindowLabel(windowLabel)) " · $windowLabel" else ""
