package dev.omalauncher.linux

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** A coding agent CLI, installed the way Omarchy's omarchy-default-agent does it: via mise. */
data class Agent(
    val bin: String,
    val name: String,
    @StringRes val description: Int,
    /** mise package spec. */
    val pkg: String,
    /** What starts it without a prompt, when that's more than [bin]. */
    val command: String = bin,
    /** Whether mise needs node to install it: npm packages run their postinstall scripts with it. */
    val node: Boolean = pkg.startsWith("npm:"),
    /** Whether mise needs uv to install it: the pypi backend runs `uv tool install`. */
    val uv: Boolean = pkg.startsWith("pypi:"),
) {
    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/** Omarchy's agent list (omarchy-default-agent), limited to ones mise can install on arm64. */
val AGENTS =
    listOf(
        Agent("claude", "Claude Code", R.string.linux_agent_claude, "claude"),
        // mise installs OpenCode 2 from npm (@opencode/cli).
        Agent("opencode", "OpenCode", R.string.linux_agent_opencode, "opencode", node = true),
        Agent("codex", "Codex", R.string.linux_agent_codex, "codex"),
        Agent("grok", "Grok", R.string.linux_agent_grok, "npm:@xai-official/grok"),
        Agent("copilot", "GitHub Copilot", R.string.linux_agent_copilot, "copilot"),
        Agent("crush", "Crush", R.string.linux_agent_crush, "crush"),
        Agent("cursor-agent", "Cursor CLI", R.string.linux_agent_cursor, "cursor-agent"),
        Agent("agy", "Antigravity", R.string.linux_agent_antigravity, "antigravity-cli"),
        Agent("pi", "Pi", R.string.linux_agent_pi, "pi"),
        Agent("omp", "Oh My Pi", R.string.linux_agent_omp, "github:can1357/oh-my-pi"),
        Agent("ori", "Ori", R.string.linux_agent_ori, "github:OpenRouterLabs/ori-releases", command = "ori code"),
        Agent(
            "muse",
            "Muse Code",
            R.string.linux_agent_muse,
            // Meta's launcher fetches and updates the native binary for this platform.
            "http:muse[url=https://api.meta.ai/muse-launcher.sh,bin=muse,version_list_url=https://api.meta.ai/muse-code/channels/muse-stable,version_json_path=.version]",
        ),
        Agent("kimi", "Kimi", R.string.linux_agent_kimi, "npm:@moonshot-ai/kimi-code"),
        // Nous Research's agent; mise installs it from PyPI, so it needs uv (see [Agent.uv]).
        Agent("hermes", "Hermes", R.string.linux_agent_hermes, "pypi:hermes-agent"),
    )

/**
 * Brings mise itself up to date. Which backend a tool installs from is built into mise, and an old mise
 * breaks when a tool moves: OpenCode 2 left GitHub releases for npm, and mise 2026.10.8 still looked for
 * it on GitHub (404). Best effort, since the old mise may still manage.
 */
const val MISE_SELF_UPDATE = "{ mise self-update -y || true; }"

/**
 * `mise prune -y`, but deleting the unused versions itself first: under proot, mise can't delete a version
 * with a hard link in it (OpenCode's npm postinstall makes one), stops at "Directory not empty", and leaves
 * 255 MB behind. `mise ls --prunable` keeps versions a running program started from, like prune does.
 * proot's stand-in for the hard link (`.l2s.*`) can come back while the first `rm -rf` empties the folder, so
 * it tries up to three times (#196).
 */
const val MISE_PRUNE =
    "{ mise ls --prunable --json | sed -n 's/.*\"install_path\": \"\\([^\"]*\\)\".*/\\1/p' | " +
        "while read -r p; do case \"\$p\" in */mise/installs/*) for i in 1 2 3; do rm -rf \"\$p\" 2>/dev/null && break; done ;; esac; done; " +
        "mise prune -y; }"

/**
 * Downloads and caches are only needed during installs and upgrades; storage on phones is tight. npm-backed
 * agents leave npm's cache behind too (125 MB after one update).
 */
const val MISE_CLEANUP = "mise cache clear && rm -rf ~/.local/share/mise/downloads ~/.npm/_cacache"

/**
 * `uv tool install`, which mise's pypi backend runs, hardlinks what it unpacks from its cache into the
 * venv. proot's link2symlink turns that hardlink into a symlink uv can't make ("Operation not permitted"),
 * so it copies instead.
 */
const val UV_LINK_MODE = "UV_LINK_MODE=copy"

/**
 * A Python for the pypi backend, which uv's `tool install` needs. A fresh Linux has none: uv then
 * fetches the newest on its own, and with no wheels for it yet it compiles pydantic-core from source,
 * which fails without a Rust toolchain ("error: can't find Rust compiler"). 3.13 has wheels for what
 * the agents need.
 */
const val PYTHON_FOR_UV = "{ command -v python3 >/dev/null || mise use -g python@3.13; }"

/** Adds node to the global config when an installed agent needs it but was installed before it did (OpenCode 1). */
val MISE_NODE_FOR_AGENTS =
    AGENTS.filter { it.node }.joinToString(" ", prefix = "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g ", postfix = ")\" ]; then mise use -g node@lts; fi") {
        shellQuote(it.pkg)
    }

/**
 * Starts [agentBin] with a first prompt; OpenCode takes it as --prompt, Antigravity and Copilot as -i (they
 * refuse a bare argument), the others as an argument.
 * Ori's agent is `ori code`, which stays open only with --interactive. Kimi takes a prompt only in
 * print mode, so the session it ran in is continued after the answer.
 * [readOnly], for reading something untrusted: Claude Code and Antigravity start in plan mode, where they
 * can read but not run commands or change files whatever the user's settings say. The others have no flag
 * for that we can rely on inside proot, and only have the prompt.
 */
fun agentInvocation(
    agentBin: String,
    prompt: String,
    readOnly: Boolean = false,
): String {
    // A prompt starting with a dash would be read as an option (claude --settings=... runs hooks); a space in front isn't.
    val quoted = shellQuote(if (prompt.startsWith("-")) " $prompt" else prompt)
    return when {
        agentBin == "opencode" -> "$agentBin --prompt $quoted"
        agentBin == "copilot" -> "$agentBin -i $quoted"
        agentBin == "claude" && readOnly -> "$agentBin --permission-mode plan $quoted"
        agentBin == "ori" -> "ori code --interactive --prompt $quoted"
        agentBin == "kimi" -> "kimi --prompt $quoted && kimi --continue"
        // Hermes' TUI takes no prompt either: it answers in one-shot mode, then resumes that session.
        // Should the answer not come (no model picked yet), the TUI opens anyway, its setup included.
        agentBin == "hermes" -> "hermes -z $quoted && hermes --tui --continue || hermes --tui"
        agentBin == "agy" && readOnly -> "$agentBin --mode plan -i $quoted"
        agentBin == "agy" -> "$agentBin -i $quoted"
        else -> "$agentBin $quoted"
    }
}

/**
 * Removes an agent's mise package, its shim and what's left of its install.
 *
 * `mise unuse` drops the package from the config and `mise prune` its versions, but mise leaves the
 * tool's shim behind (`reshim` only adds to the shims), and [AgentStore.isInstalled] reads that shim:
 * the agent's row kept offering open and remove for an agent Linux no longer had. Deleting it before
 * the prune also covers mise writing it again while it empties the install.
 */
fun agentRemoval(
    pkg: String,
    bin: String,
) = "mise unuse -g ${shellQuote(pkg)}; rm -f ~/.local/share/mise/shims/$bin; $MISE_PRUNE"

class AgentStore(
    private val proot: Proot,
    /** Called when the set of installed agents changed, so their desktop entries follow. */
    private val onChanged: () -> Unit,
) {
    private val mise get() = File(proot.home, ".local/bin/mise")
    private val shims get() = File(proot.home, ".local/share/mise/shims")

    val states = mutableStateMapOf<String, JobState>()

    /** Shims are symlinks to a guest path (/root/.local/bin/mise), so don't follow them from the host. */
    fun isInstalled(agent: Agent) = Files.exists(File(shims, agent.bin).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** The agent picked in the prompt bar, kept in the guest home where scripts can read it too. */
    private val preferredFile get() = File(proot.launcherConfig, "agent")
    private var preferred by mutableStateOf<String?>(null)

    /** Reads what's on disk; call off the main thread before the UI needs [default]. */
    fun load() {
        preferred = runCatching { preferredFile.readText().trim() }.getOrNull()
        refresh()
    }

    /** Installed agents in Omarchy's order; re-read from disk after installs and removals. */
    var installed by mutableStateOf<List<Agent>>(emptyList())
        private set

    private fun refresh() {
        installed = AGENTS.filter { isInstalled(it) }
    }

    /** The agent the launcher opens for prompts and widget work: the picked one, else the first installed in Omarchy's order. */
    val default: Agent? get() = installed.firstOrNull { it.bin == preferred } ?: installed.firstOrNull()

    fun choose(agent: Agent) {
        preferred = agent.bin
        runCatching {
            preferredFile.parentFile?.mkdirs()
            preferredFile.writeText(agent.bin + "\n")
        }.onFailure { Log.w("agents", "cannot save the default agent", it) }
    }

    suspend fun install(agent: Agent) =
        run(agent, R.string.linux_job_installing) { installScript(agent) }.also { ok -> if (ok) onInstalled(agent) }

    /** Installs [agent] with mise, bootstrapping mise itself if needed. */
    fun installScript(agent: Agent) =
        buildString {
            append(if (mise.exists()) "$MISE_SELF_UPDATE && " else "curl -fsSL https://mise.run | sh && ")
            if (agent.node) append("mise use -g node@lts && ")
            if (agent.uv) append("mise use -g uv@latest && $PYTHON_FOR_UV && $UV_LINK_MODE ")
            append("mise use -g ${shellQuote(agent.pkg)} && $MISE_CLEANUP")
        }

    /** Call after [installScript] succeeded: makes the agent show up in the drawer. */
    fun onInstalled(agent: Agent) {
        refresh()
        onChanged()
    }

    suspend fun remove(agent: Agent) =
        run(agent, R.string.linux_job_removing) { agentRemoval(agent.pkg, agent.bin) }
            .also {
                refresh()
                onChanged()
            }

    /** Drawer entries for the installed agents, with their logos (see [bundledIcons]); each opens in a terminal in the home directory. */
    fun managed(): List<Managed> =
        installed.map { agent ->
            Managed.text(File(proot.applications, "agent-${agent.bin}.desktop"), Policy.Owned) {
                "[Desktop Entry]\nType=Application\nName=${agent.name}\nTerminal=true\nExec=${agent.command}\nIcon=${bundledIcon("agent-${agent.bin}")}\n"
            }
        } + codexConfig()

    /**
     * Codex starts a shared background server and reads its start time with `ps`; when that fails, Codex
     * refuses to start ("failed to read start time for pid-managed app server", rerun with --no-daemon).
     * Proot kills that server with its window anyway, so don't start it. Codex's sandbox can't run either:
     * before calling bwrap it looks up its socket directory in /proc/self/mountinfo, which is Android's
     * under proot, and fails every command ("cannot establish app-server socket mount isolation"). The
     * bwrap shim drops the sandbox anyway, so full access is what Codex already had. A system layer, so
     * the user's ~/.codex/config.toml stays theirs and can change both.
     */
    private fun codexConfig() =
        if (installed.none { it.bin == "codex" }) {
            emptyList()
        } else {
            listOf(
                Managed.text(File(proot.rootfs, "etc/codex/config.toml"), Policy.Owned) {
                    "# Written by the launcher.\nsandbox_mode = \"danger-full-access\"\n\n[features]\ndaemon_auto_start = false\n"
                },
            )
        }

    private suspend fun run(
        agent: Agent,
        @StringRes action: Int,
        script: () -> String,
    ): Boolean {
        val (ok, _) = proot.trackedJob(action, withContext(Dispatchers.IO) { script() }) { states[agent.bin] = it }
        Log.i("agents", "${agent.bin}: ${if (ok) "ok" else "failed"}")
        return ok
    }
}
