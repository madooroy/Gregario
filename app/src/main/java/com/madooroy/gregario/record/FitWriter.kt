package com.madooroy.gregario.record

import java.io.ByteArrayOutputStream

/**
 * A minimal FIT file writer: just what an activity file for intervals.icu and Strava needs.
 * Field numbers and types follow the FIT profile (checked against fit-tool's profile).
 * Each message type gets one local definition the first time it is written; a field whose
 * value is null is written as the FIT "invalid" value for its type, so it is simply absent.
 */
class FitWriter {
    enum class T(val id: Int, val size: Int, val invalid: Long) {
        ENUM(0x00, 1, 0xFF), UINT8(0x02, 1, 0xFF), UINT16(0x84, 2, 0xFFFF), UINT32(0x86, 4, 0xFFFFFFFFL),
        UINT32Z(0x8C, 4, 0L)
    }
    data class F(val num: Int, val type: T, val value: Long?)
    class Str(val num: Int, val size: Int, val value: String)
    /** A developer field's value: [num] as declared by [developerField], developer data index 0. */
    data class Dev(val num: Int, val type: T, val value: Long?)

    private val body = ByteArrayOutputStream()
    private val locals = HashMap<String, Int>()   // message layout -> local message type

    /** Write one message ([global] number) with these fields (and fixed-size strings). */
    fun message(global: Int, fields: List<F>, strings: List<Str> = emptyList(), devs: List<Dev> = emptyList()) {
        val key = global.toString() + fields.joinToString { "${it.num}:${it.type}" } + strings.joinToString { "s${it.num}:${it.size}" } +
            devs.joinToString { "d${it.num}:${it.type}" }
        val local = locals[key] ?: run {
            val l = locals.size % 16
            // A local number reused for a new layout must be redefined: drop the old layout that had it.
            locals.entries.removeAll { it.value == l }
            locals[key] = l
            // definition (0x20: with developer fields), reserved, little-endian
            body.write(0x40 or (if (devs.isEmpty()) 0 else 0x20) or l); body.write(0); body.write(0)
            u16(global); body.write(fields.size + strings.size)
            for (f in fields) { body.write(f.num); body.write(f.type.size); body.write(f.type.id) }
            for (s in strings) { body.write(s.num); body.write(s.size); body.write(0x07) }
            if (devs.isNotEmpty()) {
                body.write(devs.size)
                for (d in devs) { body.write(d.num); body.write(d.type.size); body.write(0) }   // developer data index 0
            }
            l
        }
        body.write(local)
        for (f in fields) put(f.value ?: f.type.invalid, f.type.size)
        for (s in strings) {
            val b = s.value.toByteArray(Charsets.UTF_8).copyOf(s.size - 1)   // null-terminated, padded
            body.write(b); body.write(0)
        }
        for (d in devs) put(d.value ?: d.type.invalid, d.type.size)
    }

    /** Declares developer data index 0 (once, before [developerField]): message 207 with the app's 16-byte id. */
    fun developerDataId(appId: ByteArray) {
        val l = locals.size % 16
        locals.entries.removeAll { it.value == l }
        locals["207:dev-id"] = l
        body.write(0x40 or l); body.write(0); body.write(0); u16(207); body.write(2)
        body.write(1); body.write(16); body.write(0x0D)          // application_id: byte[16]
        body.write(3); body.write(1); body.write(0x02)           // developer_data_index: uint8
        body.write(l); body.write(appId.copyOf(16)); body.write(0)
    }

    /** Declares developer field [num] (message 206): its FIT base type, name and units, as apps show them. */
    fun developerField(num: Int, type: T, name: String, units: String) =
        message(206, listOf(F(0, T.UINT8, 0), F(1, T.UINT8, num.toLong()), F(2, T.UINT8, type.id.toLong())),
            listOf(Str(3, 24, name), Str(8, 8, units)))

    fun bytes(): ByteArray {
        val data = body.toByteArray()
        val header = ByteArrayOutputStream()
        header.write(14); header.write(0x20)                 // header size, protocol 2.0
        header.write(2132 and 255); header.write(2132 shr 8)  // profile 21.32
        for (i in 0 until 4) header.write((data.size shr (8 * i)) and 255)
        header.write(".FIT".toByteArray(Charsets.US_ASCII))
        val h = header.toByteArray()
        val hc = crc(h)
        val out = ByteArrayOutputStream()
        out.write(h); out.write(hc and 255); out.write(hc shr 8)
        out.write(data)
        val fc = crc(out.toByteArray())
        out.write(fc and 255); out.write(fc shr 8)
        return out.toByteArray()
    }

    private fun u16(v: Int) { body.write(v and 255); body.write(v shr 8 and 255) }
    private fun put(v: Long, size: Int) { for (i in 0 until size) body.write(((v shr (8 * i)) and 255).toInt()) }

    companion object {
        /** Seconds between the Unix epoch and the FIT epoch (1989-12-31T00:00:00Z). */
        const val FIT_EPOCH = 631065600L
        fun fitTime(epochMs: Long): Long = epochMs / 1000 - FIT_EPOCH

        private val TABLE = intArrayOf(0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400)
        fun crc(bytes: ByteArray): Int {
            var crc = 0
            for (b in bytes) {
                val byte = b.toInt() and 255
                var tmp = TABLE[crc and 0xF]; crc = (crc shr 4) and 0x0FFF; crc = crc xor tmp xor TABLE[byte and 0xF]
                tmp = TABLE[crc and 0xF]; crc = (crc shr 4) and 0x0FFF; crc = crc xor tmp xor TABLE[(byte shr 4) and 0xF]
            }
            return crc
        }
    }
}
