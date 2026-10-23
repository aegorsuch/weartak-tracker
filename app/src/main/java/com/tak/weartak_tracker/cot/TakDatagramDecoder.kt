package com.tak.weartak_tracker.cot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a mesh (multicast) datagram into CoT XML. ATAK sends either plain XML or TAK protocol v1:
 * `0xbf 0x01 0xbf` followed by a protobuf TakMessage. Only what chat commands need is decoded (type,
 * uid, send time and the XML detail, which carries `__chat` and `remarks`).
 */
object TakDatagramDecoder {
    private const val MAGIC = 0xbf

    fun decode(bytes: ByteArray, length: Int = bytes.size): String? {
        if (length <= 0) return null
        if ((bytes[0].toInt() and 0xff) != MAGIC) {
            return String(bytes, 0, length, Charsets.UTF_8).takeIf { it.trimStart().startsWith("<") }
        }
        if (length < 3 || bytes[1].toInt() != 1 || (bytes[2].toInt() and 0xff) != MAGIC) return null
        return try {
            val event = Proto(bytes, 3, length).message(2) ?: return null
            toXml(event)
        } catch (error: IndexOutOfBoundsException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private fun toXml(event: Proto): String? {
        var type = ""
        var uid = ""
        var sendTime = 0L
        var detail = ""
        event.forEach { field, wire, value, start, end ->
            when {
                field == 1 && wire == 2 -> type = event.string(start, end)
                field == 5 && wire == 2 -> uid = event.string(start, end)
                field == 6 && wire == 0 -> sendTime = value
                field == 15 && wire == 2 -> Proto(event.bytes, start, end).forEach { f, w, _, s, e ->
                    if (f == 1 && w == 2) detail = event.string(s, e)
                }
            }
        }
        if (uid.isEmpty()) return null
        val time = TIME.get()!!.format(Date(sendTime))
        return "<event version='2.0' uid='${CotBuilder.esc(uid)}' type='${CotBuilder.esc(type)}' time='$time' " +
            "start='$time' stale='$time' how='h-g-i-g-o'><detail>$detail</detail></event>"
    }

    private val TIME = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private class Proto(val bytes: ByteArray, private val from: Int, private val to: Int) {
        fun string(start: Int, end: Int) = String(bytes, start, end - start, Charsets.UTF_8)

        fun message(field: Int): Proto? {
            var found: Proto? = null
            forEach { f, w, _, s, e -> if (f == field && w == 2) found = Proto(bytes, s, e) }
            return found
        }

        /** Calls [visit] with (field, wire type, varint value, payload start, payload end) for each field. */
        fun forEach(visit: (Int, Int, Long, Int, Int) -> Unit) {
            var i = from
            while (i < to) {
                val (tag, afterTag) = varint(i)
                i = afterTag
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                require(field > 0)
                when (wire) {
                    0 -> { val (v, next) = varint(i); visit(field, wire, v, i, next); i = next }
                    1 -> { require(i + 8 <= to); visit(field, wire, 0, i, i + 8); i += 8 }
                    2 -> {
                        val (len, start) = varint(i)
                        require(len in 0..(to - start).toLong())
                        val end = start + len.toInt()
                        visit(field, wire, 0, start, end)
                        i = end
                    }
                    5 -> { require(i + 4 <= to); visit(field, wire, 0, i, i + 4); i += 4 }
                    else -> throw IllegalArgumentException("wire type $wire")
                }
            }
        }

        private fun varint(at: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var i = at
            while (true) {
                require(i < to && shift < 64)
                val b = bytes[i++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result to i
                shift += 7
            }
        }
    }
}
