package com.thefactor1.taskskiller.home

import android.content.Context
import com.thefactor1.taskskiller.kill.KillBackend

/**
 * What's playing, from Android's own media sessions (read through the shell, so no extra permission). Home shows
 * it; Clean up and scheduled refreshes use it to leave music playing in the background alone. Nothing is kept.
 */
object NowPlaying {

    data class Session(val packageName: String, val state: Int, val title: String?, val artist: String?) {
        val playing get() = state == STATE_PLAYING
    }

    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3

    /** Parses `dumpsys media_session`: one block per session, in the system's order (most recent first). */
    fun parse(dump: String): List<Session> {
        val out = mutableListOf<Session>()
        var pkg: String? = null
        var state = 0
        var title: String? = null
        var artist: String? = null
        fun flush() {
            pkg?.let { out += Session(it, state, title, artist) }
            pkg = null; state = 0; title = null; artist = null
        }
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("package=") -> { flush(); pkg = line.removePrefix("package=").trim() }
                pkg == null -> Unit
                line.startsWith("state=PlaybackState") ->
                    state = Regex("""state=(\d+)""").find(line.removePrefix("state=PlaybackState"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.startsWith("metadata:") -> {
                    val parts = line.substringAfter("description=", "").split(", ").map { it.trim() }.map { if (it == "null" || it.isEmpty()) null else it }
                    title = parts.getOrNull(0); artist = parts.getOrNull(1)
                }
            }
        }
        flush()
        return out
    }

    /** The session to show: one that's playing first, else the one [keepPaused] names (the viewer just paused it). */
    fun pick(sessions: List<Session>, keepPaused: String?): Session? =
        sessions.firstOrNull { it.playing } ?: sessions.firstOrNull { it.packageName == keepPaused && it.state == STATE_PAUSED }

    fun read(context: Context, backend: KillBackend): List<Session>? =
        backend.shellOutput(context, arrayOf("dumpsys", "media_session"))?.let { parse(it) }

    /** Packages playing right now; empty when it can't tell. */
    fun playingPackages(context: Context, backend: KillBackend): Set<String> =
        read(context, backend).orEmpty().filter { it.playing }.map { it.packageName }.toSet()

    /** Sends play/pause to whatever is playing, like a remote's media button. */
    fun playPause(context: Context, backend: KillBackend) {
        backend.shellOutput(context, arrayOf("cmd", "media_session", "dispatch", "play-pause"))
    }
}
