package com.blackback.batterydetector.network

/**
 * Minimal JSON writer with correct string escaping.
 *
 * Deliberately hand-rolled instead of using `org.json.JSONObject`: on the JVM the
 * Android SDK only ships a stub of org.json, so anything built on it cannot be
 * unit tested at all ("Method put in org.json.JSONObject not mocked"). Escaping is
 * exactly the part that must be tested - the previous implementation interpolated
 * user text into a JSON literal and produced invalid documents - so the writer
 * lives here where it can be exercised directly.
 *
 * Only the operations this app needs are implemented: string, number and boolean
 * fields on a single flat object. Nested structures are not supported and are not
 * needed by any payload we send.
 */
internal class JsonBuilder {

    private val fields = StringBuilder()
    private var count = 0

    private fun begin(): JsonBuilder {
        if (count > 0) fields.append(',')
        count++
        return this
    }

    fun put(name: String, value: String): JsonBuilder {
        begin()
        fields.append(quote(name)).append(':').append(quote(value))
        return this
    }

    fun put(name: String, value: Int): JsonBuilder {
        begin()
        fields.append(quote(name)).append(':').append(value)
        return this
    }

    fun put(name: String, value: Long): JsonBuilder {
        begin()
        fields.append(quote(name)).append(':').append(value)
        return this
    }

    fun put(name: String, value: Boolean): JsonBuilder {
        begin()
        fields.append(quote(name)).append(':').append(value)
        return this
    }

    fun build(): String = "{$fields}"

    companion object {
        private const val HEX = "0123456789abcdef"

        /**
         * Wraps a value in quotes, escaping everything JSON requires.
         *
         * Control characters below 0x20 become `\uXXXX`, which is what keeps a
         * device name containing a newline from splitting the document. Non-ASCII
         * is emitted as-is; the request is sent as UTF-8, so Chinese text and emoji
         * survive without escaping.
         */
        fun quote(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> {
                        if (ch < ' ') {
                            sb.append("\\u")
                                .append(HEX[(ch.code shr 12) and 0xF])
                                .append(HEX[(ch.code shr 8) and 0xF])
                                .append(HEX[(ch.code shr 4) and 0xF])
                                .append(HEX[ch.code and 0xF])
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
