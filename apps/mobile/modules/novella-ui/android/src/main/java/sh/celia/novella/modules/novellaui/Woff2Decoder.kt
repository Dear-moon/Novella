package sh.celia.novella.modules.novellaui

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WOFF2 -> SFNT (TTF/OTF) decoder.
 *
 * Skia's FreeType font manager does not understand the WOFF2 container (it is a
 * Brotli-compressed wrapper around the SFNT tables). This decodes the container,
 * Brotli-decompresses the table stream, and rebuilds a plain SFNT that FreeType
 * can parse.
 *
 * Limitation: WOFF2 compresses TrueType `glyf`/`loca` tables with a transform
 * encoding. Reconstructing it is non-trivial, so when those tables are
 * transformed this returns null and the caller falls back to the system font
 * rather than failing the reader.
 */
object Woff2Decoder {

  private val KNOWN_TAGS = arrayOf(
    "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm",
    "glyf", "loca", "prep", "CFF ", "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern",
    "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC",
    "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar",
    "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty",
    "just", "lcar", "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat",
    "Gloc", "Feat", "Sill"
  )

  private class TableEntry(
    val tag: String,
    val origLength: Long,
    val transformLength: Long,
    val transformed: Boolean
  )

  /** Decodes WOFF2 bytes to SFNT bytes, or null when unsupported. */
  fun decode(woff2: ByteArray): ByteArray? {
    if (woff2.size < 48) return null
    if (woff2[0] != 'w'.code.toByte() || woff2[1] != 'O'.code.toByte() ||
      woff2[2] != 'F'.code.toByte() || woff2[3] != '2'.code.toByte()
    ) return null

    return try {
      val buf = ByteBuffer.wrap(woff2).order(ByteOrder.BIG_ENDIAN)
      buf.position(4) // signature
      val flavor = buf.int
      buf.int // length
      val numTables = buf.short.toInt() and 0xFFFF
      buf.int // reserved 2 + majorVersion 2
      buf.short // minorVersion
      buf.int // metaOffset
      buf.int // metaLength
      buf.int // metaOrigLength
      buf.int // privOffset
      buf.int // privLength

      val tables = ArrayList<TableEntry>(numTables)
      for (i in 0 until numTables) {
        val flag = buf.get().toInt() and 0xFF
        val tagIndex = flag and 0x3F
        val transformed = (flag and 0x40) != 0
        val tag = if (tagIndex == 0x3F) readRawTag(buf) else KNOWN_TAGS[tagIndex]
        val origLength = readUIntBase128(buf)
        val transformLength = if (transformed) readUIntBase128(buf) else 0L
        tables.add(TableEntry(tag, origLength, transformLength, transformed))
      }

      // TrueType glyf/loca transform unsupported in this pass -> graceful fallback.
      if (tables.any { (it.tag == "glyf" || it.tag == "loca") && it.transformed }) return null

      val compressedStart = buf.position()
      val stream = BrotliInputStream(
        ByteArrayInputStream(woff2, compressedStart, woff2.size - compressedStart)
      )
      val tableData = stream.use { it.readBytes() }
      val ts = ByteBuffer.wrap(tableData).order(ByteOrder.BIG_ENDIAN)

      val dataByTag = LinkedHashMap<String, ByteArray>(tables.size)
      for (t in tables) {
        val len = if (t.transformed) t.transformLength else t.origLength
        if (len > Int.MAX_VALUE) return null
        val bytes = ByteArray(len.toInt())
        ts.get(bytes)
        dataByTag[t.tag] = bytes
      }

      buildSfnt(flavor, numTables, dataByTag)
    } catch (_: Throwable) {
      null
    }
  }

  private fun readRawTag(buf: ByteBuffer): String {
    val b = ByteArray(4)
    buf.get(b)
    return String(b, Charsets.ISO_8859_1)
  }

  // Reads the WOFF2 UIntBase128 variable-length integer (max 5 bytes).
  private fun readUIntBase128(buf: ByteBuffer): Long {
    var accum = 0L
    var shift = 0
    while (true) {
      if (shift > 28) return -1
      val b = buf.get().toInt() and 0xFF
      accum = (accum shl 7) or (b and 0x7F).toLong()
      shift += 7
      if ((b and 0x80) == 0) return if (accum < 0) -1 else accum
    }
  }

  private fun buildSfnt(flavor: Int, numTables: Int, dataByTag: Map<String, ByteArray>): ByteArray {
    val sorted = dataByTag.entries.sortedBy { it.key }
    val headerSize = 12 + 16 * numTables

    val offsets = LinkedHashMap<String, Int>()
    var cursor = headerSize
    for (e in sorted) {
      while ((cursor % 4) != 0) cursor++
      offsets[e.key] = cursor
      cursor += e.value.size
    }

    val power = Integer.highestOneBit(numTables)
    val searchRange = power * 16
    val entrySelector = Integer.numberOfTrailingZeros(power)
    val rangeShift = numTables * 16 - searchRange

    val buf = ByteArrayOutputStream()
    fun w(v: Int) {
      buf.write((v ushr 24) and 0xFF); buf.write((v ushr 16) and 0xFF)
      buf.write((v ushr 8) and 0xFF); buf.write(v and 0xFF)
    }
    w(flavor); w(numTables); w(searchRange); w(entrySelector); w(rangeShift)
    for (e in sorted) {
      val tag = ByteArray(4)
      val tb = e.key.toByteArray(Charsets.ISO_8859_1)
      System.arraycopy(tb, 0, tag, 0, minOf(4, tb.size))
      buf.write(tag)
      w(checksum(e.value))
      w(offsets[e.key] ?: 0)
      w(e.value.size)
    }
    for (e in sorted) {
      val off = offsets[e.key] ?: 0
      while (buf.size() < off) buf.write(0)
      buf.write(e.value)
    }
    while ((buf.size() % 4) != 0) buf.write(0)

    val result = buf.toByteArray()
    patchCheckSumAdjustment(result, offsets["head"])
    return result
  }

  private fun checksum(data: ByteArray): Int {
    var sum = 0
    var i = 0
    while (i < data.size) {
      var v = 0
      for (j in 0 until 4) {
        v = (v shl 8) or (if (i + j < data.size) data[i + j].toInt() and 0xFF else 0)
      }
      sum += v
      i += 4
    }
    return sum
  }

  private fun patchCheckSumAdjustment(font: ByteArray, headOffset: Int?) {
    if (headOffset == null || headOffset + 12 > font.size) return
    val adjustPos = headOffset + 8
    font[adjustPos] = 0; font[adjustPos + 1] = 0; font[adjustPos + 2] = 0; font[adjustPos + 3] = 0
    var total = 0
    var i = 0
    val padded = font.size + ((4 - font.size % 4) % 4)
    while (i < padded) {
      var v = 0
      for (j in 0 until 4) {
        v = (v shl 8) or (if (i + j < font.size) font[i + j].toInt() and 0xFF else 0)
      }
      total += v
      i += 4
    }
    val adjustment = 0xB1B0AFBA - total
    font[adjustPos] = (adjustment ushr 24).toByte()
    font[adjustPos + 1] = (adjustment ushr 16).toByte()
    font[adjustPos + 2] = (adjustment ushr 8).toByte()
    font[adjustPos + 3] = adjustment.toByte()
  }
}
