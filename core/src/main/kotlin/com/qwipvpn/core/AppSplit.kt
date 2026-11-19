package com.qwipvpn.core

/** Per-application tunnel selection. Android forbids mixing allowed and disallowed lists in one session. */
object AppSplit {
    const val ALL = "all"
    const val EXCEPT = "except"
    const val ONLY = "only"
    const val MAX_PACKAGES = 300
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun clean(packages: List<String>): List<String> =
        packages.map(String::trim).filter { it.length <= 255 && packageName.matches(it) }.distinct().take(MAX_PACKAGES)

    /** An empty "only" list would silently tunnel every app, so it is rejected rather than reinterpreted. */
    fun isValid(mode: String, packages: List<String>): Boolean = when (mode) {
        ALL, EXCEPT -> true
        ONLY -> clean(packages).isNotEmpty()
        else -> false
    }

    fun toggle(packages: List<String>, name: String, selected: Boolean): List<String> {
        val current = clean(packages).filter { it != name }
        return if (selected) clean(current + name) else current
    }
}
