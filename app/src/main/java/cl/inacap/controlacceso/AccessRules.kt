package cl.inacap.controlacceso

object AccessRules {
    const val AUTHORIZED_UID = "40 84 2C 33"
    fun normalizeUid(text: String): String? {
        val compact = text.replace(" ", "").uppercase(java.util.Locale.ROOT)
        if (!compact.matches(Regex("[0-9A-F]{8}"))) return null
        return compact.chunked(2).joinToString(" ")
    }
    fun isAllowed(uid: String) = uid == AUTHORIZED_UID
}
