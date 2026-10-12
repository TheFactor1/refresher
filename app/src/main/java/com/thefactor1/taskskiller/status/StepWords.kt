package com.thefactor1.taskskiller.status

/**
 * The restart service logs what it did in its own terms ("Refreshed via Shizuku
 * (ADB, no root)", "relaunched"); people read "Stopped", "Opened again".
 */
object StepWords {

    fun friendly(step: String): String {
        val s = step.trim()
        return when {
            s.startsWith("Refreshed via") -> "Stopped"
            s.startsWith("Refresh failed") -> "Couldn't stop it: " + s.substringAfter("): ", s.substringAfter(": ", s))
            s == "relaunched" -> "Opened again"
            s.startsWith("relaunched (") -> "Opened again (" + s.removePrefix("relaunched (")
            s.startsWith("relaunch failed:") -> "Couldn't open it again:" + s.removePrefix("relaunch failed:")
            s.startsWith("not relaunched") -> "Not opened again: it has no home-screen entry"
            s == "returned to Home" -> "Back to the home screen"
            s.startsWith("returned to ") -> "Back to " + s.removePrefix("returned to ")
            s.startsWith("could not return to Home") -> "Couldn't go back to the home screen"
            s.startsWith("could not return to ") -> "Couldn't go back to " + s.removePrefix("could not return to ").substringBefore(":")
            s.startsWith("Deferred") -> "Skipped: the screen was on"
            s.startsWith("Skipped:") -> "Skipped:" + s.removePrefix("Skipped:")
            else -> s.replaceFirstChar { it.uppercase() }
        }
    }

    /** A step that reports something going wrong, drawn in red. */
    fun isProblem(step: String): Boolean {
        val s = step.lowercase()
        return "failed" in s || "could not" in s || "error" in s || s.startsWith("skipped:") && "not installed" in s
    }
}
