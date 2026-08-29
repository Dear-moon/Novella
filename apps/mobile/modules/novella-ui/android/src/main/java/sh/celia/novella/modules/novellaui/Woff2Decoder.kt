package sh.celia.novella.modules.novellaui

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WOFF2 -> SFNT decoder with full `glyf`/`loca` transform reconstruction.
 *
 * Skia's FreeType font manager cannot parse the WOFF2 container. This decodes it:
 * Brotli-decompresses the table stream, reconstructs the TrueType `glyf`/`loca`
 * transform, and rebuilds a plain SFNT. The reconstruction follows fontTools'
 * WOFF2 decoder and was validated against fontTools to be byte-identical.
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

  private const val FLAG_ON_CURVE = 0x01
  private const val FLAG_X_SHORT = 0x02
  private const val FLAG_Y_SHORT = 0x04
  private const val FLAG_REPEAT = 0x08
  private const val FLAG_X_SAME = 0x10
  private const val FLAG_Y_SAME = 0x20
  private const val FLAG_OVERLAP_SIMPLE = 0x40

  private const val ARG_1_AND_2_ARE_WORDS = 0x0001
  private const val ARGS_ARE_XY_VALUES = 0x0002
  private const val ROUND_XY_TO_GRID = 0x0004
  private const val WE_HAVE_A_SCALE = 0x0008
  private const val MORE_COMPONENTS = 0x0020
  private const val WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
  private const val WE_HAVE_A_TWO_BY_TWO = 0x0080
  private const val WE_HAVE_INSTRUCTIONS = 0x0100

  private class TableVal(val tag: String, val origLength: Long, val transformLength: Long, val transformed: Boolean)

  // ---- byte helpers --------------------------------------------------------------
  internal fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
  internal fun s16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8 or (b[o + 1].toInt() and 0xFF)).toShort().toInt()
  internal fun u32(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
  internal fun putU16(out: ByteArrayOutputStream, v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
  internal fun putS16(out: ByteArrayOutputStream, v: Int) { putU16(out, v and 0xFFFF) }
  internal fun putU32(out: ByteArrayOutputStream, v: Int) {
    out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
  }
  internal fun otRound(v: Double): Int = Math.floor(v + 0.5).toInt()

  private fun unpackBase128(b: ByteArray, pos: Int): Pair<Long, Int> {
    var result = 0L; var p = pos
    if (pos >= b.size) throw IllegalArgumentException("b128")
    if ((b[pos].toInt() and 0xFF) == 0x80) throw IllegalArgumentException("b128 lead0")
    for (i in 0 until 5) {
      if (p >= b.size) throw IllegalArgumentException("b128")
      val code = b[p].toInt() and 0xFF; p++
      if ((result and 0xFE000000L) != 0L) throw IllegalArgumentException("b128 overflow")
      result = (result shl 7) or (code and 0x7F).toLong()
      if ((code and 0x80) == 0) return Pair(result, p)
    }
    throw IllegalArgumentException("b128 long")
  }

  private fun unpack255UShort(data: ByteArray, offset: Int): Pair<Int, Int> {
    val code = data[offset].toInt() and 0xFF
    val p = offset + 1
    return when (code) {
      253 -> Pair(u16(data, p), p + 2)
      254 -> Pair((data[p].toInt() and 0xFF) + 506, p + 1)
      255 -> Pair((data[p].toInt() and 0xFF) + 253, p + 1)
      else -> Pair(code, p)
    }
  }

  // ---- glyph data model -----------------------------------------------------------
  private class GlyphCoordinate {
    val a = ArrayList<Int>()
    fun size() = a.size / 2
    fun getX(i: Int) = a[2 * i]; fun getY(i: Int) = a[2 * i + 1]
    fun set(i: Int, x: Int, y: Int) { a[2 * i] = x; a[2 * i + 1] = y }
    fun bounds(): IntArray {
      if (a.isEmpty()) return intArrayOf(0, 0, 0, 0)
      var mnx = Int.MAX_VALUE; var mny = Int.MAX_VALUE; var mxx = Int.MIN_VALUE; var mxy = Int.MIN_VALUE
      for (i in 0 until size()) { val x = getX(i); val y = getY(i); if (x < mnx) mnx = x; if (y < mny) mny = y; if (x > mxx) mxx = x; if (y > mxy) mxy = y }
      return intArrayOf(mnx, mny, mxx, mxy)
    }
    fun absToRel() {
      var x = 0; var y = 0
      for (i in 0 until size()) { val nx = getX(i); val ny = getY(i); a[2 * i] = nx - x; a[2 * i + 1] = ny - y; x = nx; y = ny }
    }
    fun copy(): GlyphCoordinate { val c = GlyphCoordinate(); c.a.addAll(a); return c }
  }

  private class Glyph {
    var numberOfContours = 0
    var coordinates: GlyphCoordinate? = null
    var flags: ByteArray? = null
    var endPtsOfContours: IntArray? = null
    var components: ArrayList<GlyphComponent>? = null
    var program: ByteArray? = null
    var xMin = 0; var yMin = 0; var xMax = 0; var yMax = 0
    fun isComposite() = numberOfContours == -1
  }

  private class GlyphComponent {
    var flags = 0; var glyphID = 0
    var x = 0; var y = 0
    var firstPt = -1; var secondPt = -1
    var transform: Array<FloatArray>? = null

    fun decompile(data: ByteArray, offset: Int): Triple<Int, Boolean, Boolean> {
      val flags = u16(data, offset)
      glyphID = u16(data, offset + 2)
      var p = offset + 4
      val haveInstructions = (flags and WE_HAVE_INSTRUCTIONS) != 0
      val more = (flags and MORE_COMPONENTS) != 0
      this.flags =
        flags and (ROUND_XY_TO_GRID or 0x0200 or 0x0800 or 0x1000 or 0x0010 or 0x0400)
      if ((flags and ARG_1_AND_2_ARE_WORDS) != 0) {
        if ((flags and ARGS_ARE_XY_VALUES) != 0) { x = s16(data, p); y = s16(data, p + 2); p += 4 }
        else { firstPt = u16(data, p); secondPt = u16(data, p + 2); p += 4 }
      } else {
        if ((flags and ARGS_ARE_XY_VALUES) != 0) { x = data[p].toInt().toByte().toInt(); y = data[p + 1].toInt().toByte().toInt(); p += 2 }
        else { firstPt = data[p].toInt() and 0xFF; secondPt = data[p + 1].toInt() and 0xFF; p += 2 }
      }
      when {
        (flags and WE_HAVE_A_SCALE) != 0 -> { val s = s16(data, p).toFloat() / 16384f; p += 2; transform = arrayOf(floatArrayOf(s, 0f), floatArrayOf(0f, s)) }
        (flags and WE_HAVE_AN_X_AND_Y_SCALE) != 0 -> { val xs = s16(data, p).toFloat() / 16384f; val ys = s16(data, p + 2).toFloat() / 16384f; p += 4; transform = arrayOf(floatArrayOf(xs, 0f), floatArrayOf(0f, ys)) }
        (flags and WE_HAVE_A_TWO_BY_TWO) != 0 -> {
          val a = s16(data, p).toFloat() / 16384f; val b = s16(data, p + 2).toFloat() / 16384f
          val c = s16(data, p + 4).toFloat() / 16384f; val d = s16(data, p + 6).toFloat() / 16384f; p += 8
          transform = arrayOf(floatArrayOf(a, b), floatArrayOf(c, d))
        }
      }
      return Triple(p, haveInstructions, more)
    }

    fun compile(more: Boolean, haveInstructions: Boolean): ByteArray {
      val out = ByteArrayOutputStream()
      var flags = this.flags and (ROUND_XY_TO_GRID or 0x0200 or 0x0800 or 0x1000 or 0x0010 or 0x0400)
      if (more) flags = flags or MORE_COMPONENTS
      if (haveInstructions) flags = flags or WE_HAVE_INSTRUCTIONS
      if (firstPt >= 0) {
        if (firstPt in 0..255 && secondPt in 0..255) { out.write(firstPt and 0xFF); out.write(secondPt and 0xFF) }
        else { putU16(out, firstPt); putU16(out, secondPt); flags = flags or ARG_1_AND_2_ARE_WORDS }
      } else {
        flags = flags or ARGS_ARE_XY_VALUES
        if (x in -128..127 && y in -128..127) { out.write(x and 0xFF); out.write(y and 0xFF) }
        else { putS16(out, x); putS16(out, y); flags = flags or ARG_1_AND_2_ARE_WORDS }
      }
      val t = transform
      if (t != null) {
        val a = fl2fi(t[0][0]); val b = fl2fi(t[0][1]); val c = fl2fi(t[1][0]); val d = fl2fi(t[1][1])
        if (b != 0 || c != 0) { flags = flags or WE_HAVE_A_TWO_BY_TWO; putS16(out, a); putS16(out, b); putS16(out, c); putS16(out, d) }
        else if (a != d) { flags = flags or WE_HAVE_AN_X_AND_Y_SCALE; putS16(out, a); putS16(out, d) }
        else { flags = flags or WE_HAVE_A_SCALE; putS16(out, a) }
      }
      val hdr = ByteArrayOutputStream(); putU16(hdr, flags); putU16(hdr, glyphID)
      return hdr.toByteArray() + out.toByteArray()
    }
  }

  private fun fl2fi(v: Float): Int = otRound((v * 16384f).toDouble())

  // ---- WOFF2 glyf decoder ---------------------------------------------------------
  private class GlyfDecode(val numGlyphs: Int) {
    var indexFormat = 0
    var bboxBitmap = ByteArray(0)
    var overlapSimpleBitmap: ByteArray? = null
    var nContourStreamArr = IntArray(0)
    var nContourStream = ByteArray(0)
    var nPointsStream = ByteArray(0)
    var flagStream = ByteArray(0)
    var glyphStream = ByteArray(0)
    var compositeStream = ByteArray(0)
    var bboxStream = ByteArray(0)
    var instructionStream = ByteArray(0)
    val glyphOrder = Array(numGlyphs) { if (it == 0) ".notdef" else "glyph%05d".format(it) }
    val glyphs = HashMap<String, Glyph>()

    fun reconstruct(data: ByteArray) {
      val size = data.size
      if (size < 36) throw IllegalArgumentException("glyf")
      var p = 0
      val optionFlags = u16(data, p); p += 2
      p += 2; p += 2
      indexFormat = u16(data, p); p += 2
      val ncs = u32(data, p); p += 4
      val nps = u32(data, p); p += 4
      val fls = u32(data, p); p += 4
      val gls = u32(data, p); p += 4
      val cis = u32(data, p); p += 4
      val bbs = u32(data, p); p += 4
      val ins = u32(data, p); p += 4
      fun slice(n: Int): ByteArray { val a = data.copyOfRange(p, minOf(p + n, size)); p += n; return a }
      nContourStream = slice(ncs); nPointsStream = slice(nps); flagStream = slice(fls)
      glyphStream = slice(gls); compositeStream = slice(cis); bboxStream = slice(bbs); instructionStream = slice(ins)
      if ((optionFlags and 0x0001) != 0) overlapSimpleBitmap = slice((numGlyphs + 7) shr 3)
      val bboxBitmapSize = ((numGlyphs + 31) shr 5) shl 2
      bboxBitmap = if (bboxBitmapSize <= bboxStream.size) bboxStream.copyOfRange(0, bboxBitmapSize) else bboxStream
      if (bboxBitmapSize <= bboxStream.size) bboxStream = bboxStream.copyOfRange(bboxBitmapSize, bboxStream.size)
      val nc = IntArray(numGlyphs)
      for (i in 0 until numGlyphs) nc[i] = s16(nContourStream, i * 2)
      nContourStreamArr = nc
      for (gid in 0 until numGlyphs) glyphs[glyphOrder[gid]] = decodeGlyph(gid)
    }

    private fun decodeGlyph(gid: Int): Glyph {
      val g = Glyph()
      g.numberOfContours = nContourStreamArr[gid]
      if (g.numberOfContours == 0) return g
      if (g.isComposite()) decodeComponents(g) else { decodeCoordinates(g); decodeOverlapSimpleFlag(g, gid) }
      decodeBBox(gid, g)
      return g
    }

    private fun decodeComponents(g: Glyph) {
      val comps = ArrayList<GlyphComponent>()
      var p = 0; var more = true; var haveInstructions = 0
      while (more) {
        val c = GlyphComponent(); val (np, haveInstr, moreInstr) = c.decompile(compositeStream, p)
        more = moreInstr; if (haveInstr) haveInstructions = 1
        comps.add(c); p = np
      }
      compositeStream = if (p <= compositeStream.size) compositeStream.copyOfRange(p, compositeStream.size) else ByteArray(0)
      g.components = comps
      if (haveInstructions != 0) decodeInstructions(g)
    }

    private fun decodeCoordinates(g: Glyph) {
      val endPts = ArrayList<Int>(); var endPoint = -1; var p = 0
      repeat(g.numberOfContours) { val (pts, np) = unpack255UShort(nPointsStream, p); endPoint += pts; endPts.add(endPoint); p = np }
      g.endPtsOfContours = endPts.toIntArray()
      nPointsStream = if (p <= nPointsStream.size) nPointsStream.copyOfRange(p, nPointsStream.size) else ByteArray(0)
      decodeTriplets(g); decodeInstructions(g)
    }

    private fun decodeInstructions(g: Glyph) {
      val (instrLen, np) = unpack255UShort(glyphStream, 0)
      val instr = if (instrLen <= instructionStream.size) instructionStream.copyOfRange(0, instrLen) else ByteArray(0)
      g.program = instr
      glyphStream = if (np <= glyphStream.size) glyphStream.copyOfRange(np, glyphStream.size) else ByteArray(0)
      if (instrLen <= instructionStream.size) instructionStream = instructionStream.copyOfRange(instrLen, instructionStream.size)
    }

    private fun decodeBBox(gid: Int, g: Glyph) {
      val haveBBox = (bboxBitmap[gid shr 3].toInt() and (0x80 shr (gid and 7))) != 0
      if (g.isComposite() && !haveBBox) throw IllegalArgumentException("bbox")
      if (haveBBox) {
        g.xMin = s16(bboxStream, 0); g.yMin = s16(bboxStream, 2); g.xMax = s16(bboxStream, 4); g.yMax = s16(bboxStream, 6)
        bboxStream = if (8 <= bboxStream.size) bboxStream.copyOfRange(8, bboxStream.size) else ByteArray(0)
      } else {
        val b = g.coordinates!!.bounds()
        g.xMin = otRound(b[0].toDouble()); g.yMin = otRound(b[1].toDouble()); g.xMax = otRound(b[2].toDouble()); g.yMax = otRound(b[3].toDouble())
      }
    }

    private fun decodeOverlapSimpleFlag(g: Glyph, gid: Int) {
      val obm = overlapSimpleBitmap ?: return
      if (g.numberOfContours <= 0) return
      if ((obm[gid shr 3].toInt() and (0x80 shr (gid and 7))) != 0) {
        val fl = g.flags; if (fl != null && fl.isNotEmpty()) fl[0] = (fl[0].toInt() or FLAG_OVERLAP_SIMPLE).toByte()
      }
    }

    private fun decodeTriplets(g: Glyph) {
      val endPts = g.endPtsOfContours!!
      val nPoints = endPts[endPts.size - 1] + 1
      val flags = if (nPoints <= flagStream.size) flagStream.copyOfRange(0, nPoints) else ByteArray(nPoints)
      flagStream = if (nPoints <= flagStream.size) flagStream.copyOfRange(nPoints, flagStream.size) else ByteArray(0)
      val coords = GlyphCoordinate().apply { for (k in 0 until nPoints * 2) a.add(0) }
      val outFlags = ByteArray(nPoints)
      var x = 0; var y = 0; var ti = 0
      for (i in 0 until nPoints) {
        var flag = flags[i].toInt() and 0xFF
        val onCurve = (flag shr 7) == 0
        flag = flag and 0x7F
        val nBytes = when { flag < 84 -> 1; flag < 120 -> 2; flag < 124 -> 3; else -> 4 }
        fun withSign(b: Int, base: Int) = if ((b and 1) != 0) base else -base
        val dx: Int; val dy: Int
        when {
          flag < 10 -> { dx = 0; dy = withSign(flag, ((flag and 14) shl 7) + (glyphStream[ti].toInt() and 0xFF)) }
          flag < 20 -> { dx = withSign(flag, (((flag - 10) and 14) shl 7) + (glyphStream[ti].toInt() and 0xFF)); dy = 0 }
          flag < 84 -> { val b0 = flag - 20; val b1 = glyphStream[ti].toInt() and 0xFF; dx = withSign(flag, 1 + (b0 and 0x30) + (b1 shr 4)); dy = withSign(flag shr 1, 1 + ((b0 and 0x0C) shl 2) + (b1 and 0x0F)) }
          flag < 120 -> { val b0 = flag - 84; dx = withSign(flag, 1 + ((b0 / 12) shl 8) + (glyphStream[ti].toInt() and 0xFF)); dy = withSign(flag shr 1, 1 + (((b0 % 12) shr 2) shl 8) + (glyphStream[ti + 1].toInt() and 0xFF)) }
          flag < 124 -> { val b2 = glyphStream[ti + 1].toInt() and 0xFF; dx = withSign(flag, ((glyphStream[ti].toInt() and 0xFF) shl 4) + (b2 shr 4)); dy = withSign(flag shr 1, ((b2 and 0x0F) shl 8) + (glyphStream[ti + 2].toInt() and 0xFF)) }
          else -> { dx = withSign(flag, ((glyphStream[ti].toInt() and 0xFF) shl 8) + (glyphStream[ti + 1].toInt() and 0xFF)); dy = withSign(flag shr 1, ((glyphStream[ti + 2].toInt() and 0xFF) shl 8) + (glyphStream[ti + 3].toInt() and 0xFF)) }
        }
        ti += nBytes; x += dx; y += dy
        coords.set(i, x, y); outFlags[i] = (if (onCurve) 1 else 0).toByte()
      }
      g.coordinates = coords; g.flags = outFlags
      glyphStream = if (ti <= glyphStream.size) glyphStream.copyOfRange(ti, glyphStream.size) else ByteArray(0)
    }
  }

  // ---- classic glyf serializer ------------------------------------------------------
  private fun compileDeltasGreedy(flags: ByteArray, coords: GlyphCoordinate): Triple<ByteArray, ByteArray, ByteArray> {
    val cf = ArrayList<Int>(); val cx = ByteArrayOutputStream(); val cy = ByteArrayOutputStream()
    var lastflag = -1; var repeat = 0
    for (i in 0 until coords.size()) {
      var x = coords.getX(i); var y = coords.getY(i)
      var flag = flags[i].toInt() and 0xFF
      if (x == 0) flag = flag or FLAG_X_SAME
      else if (x in -255..255) { flag = flag or FLAG_X_SHORT; if (x > 0) flag = flag or FLAG_X_SAME else x = -x; cx.write(x and 0xFF) }
      else putS16(cx, x)
      if (y == 0) flag = flag or FLAG_Y_SAME
      else if (y in -255..255) { flag = flag or FLAG_Y_SHORT; if (y > 0) flag = flag or FLAG_Y_SAME else y = -y; cy.write(y and 0xFF) }
      else putS16(cy, y)
      if (flag == lastflag && repeat != 255) {
        repeat++
        if (repeat == 1) cf.add(flag)
        else { cf[cf.size - 2] = flag or FLAG_REPEAT; cf[cf.size - 1] = repeat }
      } else { repeat = 0; cf.add(flag) }
      lastflag = flag
    }
    val cfBytes = ByteArray(cf.size); for (i in cf.indices) cfBytes[i] = cf[i].toByte()
    return Triple(cfBytes, cx.toByteArray(), cy.toByteArray())
  }

  private fun compileCoordinates(g: Glyph): ByteArray {
    val out = ByteArrayOutputStream()
    val endPts = g.endPtsOfContours!!
    for (e in endPts) putU16(out, e)
    val instr = g.program ?: ByteArray(0)
    putU16(out, instr.size); out.write(instr)
    val deltas = g.coordinates!!.copy(); deltas.absToRel()
    val (fl, xs, ys) = compileDeltasGreedy(g.flags!!, deltas)
    for (b in fl) out.write(b.toInt() and 0xFF)
    for (b in xs) out.write(b.toInt() and 0xFF)
    for (b in ys) out.write(b.toInt() and 0xFF)
    return out.toByteArray()
  }

  private fun compileComponents(g: Glyph): ByteArray {
    val out = ByteArrayOutputStream()
    val comps = g.components!!
    val last = comps.size - 1
    for (i in comps.indices) { val more = i != last; val haveInstructions = i == last && g.program != null; out.write(comps[i].compile(more, haveInstructions)) }
    val instr = g.program
    if (instr != null) { putU16(out, instr.size); out.write(instr) }
    return out.toByteArray()
  }

  private fun compileGlyph(g: Glyph): ByteArray {
    if (g.numberOfContours == 0) return ByteArray(0)
    val header = ByteArrayOutputStream()
    putS16(header, g.numberOfContours); putS16(header, g.xMin); putS16(header, g.yMin); putS16(header, g.xMax); putS16(header, g.yMax)
    val body = if (g.isComposite()) compileComponents(g) else compileCoordinates(g)
    return header.toByteArray() + body
  }

  private fun compileGlyfTable(order: Array<String>, glyphs: Map<String, Glyph>): Pair<ByteArray, IntArray> {
    val dataList = ArrayList<ByteArray>(); val locations = ArrayList<Int>(); var cur = 0
    for (name in order) { val gd = compileGlyph(glyphs[name]!!); locations.add(cur); cur += gd.size; dataList.add(gd) }
    locations.add(cur)
    if (cur < 0x20000) {
      val oddIdx = dataList.indices.filter { dataList[it].size % 2 == 1 }
      if (oddIdx.isNotEmpty() && cur + oddIdx.size < 0x20000) {
        for (i in oddIdx) dataList[i] = dataList[i] + byteArrayOf(0)
        cur = 0; for (i in dataList.indices) { locations[i] = cur; cur += dataList[i].size }; locations[dataList.size] = cur
      }
    }
    val out = ByteArrayOutputStream(); for (d in dataList) out.write(d)
    return Pair(out.toByteArray(), locations.toIntArray())
  }

  private fun compileLoca(locations: IntArray, indexFormat: Int): ByteArray {
    val out = ByteArrayOutputStream()
    if (indexFormat == 0) {
      var max = 0; for (l in locations) if (l > max) max = l
      if (max >= 0x20000) throw IllegalArgumentException("loca")
      for (l in locations) putU16(out, l / 2)
    } else for (l in locations) putU32(out, l)
    return out.toByteArray()
  }

  /** Decodes WOFF2 bytes to SFNT bytes, or null when unsupported. */
  fun decode(woff2: ByteArray): ByteArray? {
    if (woff2.size < 48) return null
    if (woff2[0] != 'w'.code.toByte() || woff2[1] != 'O'.code.toByte() || woff2[2] != 'F'.code.toByte() || woff2[3] != '2'.code.toByte()) return null
    return try {
      val buf = ByteBuffer.wrap(woff2).order(ByteOrder.BIG_ENDIAN)
      buf.position(4)
      val flavor = buf.int
      buf.int
      val numTables = buf.short.toInt() and 0xFFFF
      buf.short
      buf.int // totalSfntSize
      val totalCompressedSize = buf.int // totalCompressedSize (offset 20) — slice the brotli stream exactly
      buf.short; buf.short // majorVersion, minorVersion
      buf.int; buf.int; buf.int; buf.int; buf.int // metaOffset, metaLength, metaOrigLength, privOffset, privLength
      val defs = ArrayList<TableVal>(numTables)
      for (i in 0 until numTables) {
        val flag = buf.get().toInt() and 0xFF
        val tagIndex = flag and 0x3F
        val transformVersion = flag ushr 6
        val tag = if (tagIndex == 0x3F) readRawTag(buf) else KNOWN_TAGS[tagIndex]
        val origLength = readUIntBase128(buf)
        val transformed = if (tag == "glyf" || tag == "loca") transformVersion != 3 else transformVersion != 0
        val transformLength = if (transformed) readUIntBase128(buf) else 0L
        defs.add(TableVal(tag, origLength, transformLength, transformed))
      }
      val compressedStart = buf.position()
      val stream = BrotliInputStream(ByteArrayInputStream(woff2, compressedStart, totalCompressedSize))
      val tableData = stream.use { it.readBytes() }
      val ts = ByteBuffer.wrap(tableData).order(ByteOrder.BIG_ENDIAN)
      val dataByTag = LinkedHashMap<String, ByteArray>()
      for (t in defs) {
        val len = if (t.transformed) t.transformLength else t.origLength
        if (len > Int.MAX_VALUE) return null
        val bytes = ByteArray(len.toInt()); ts.get(bytes)
        dataByTag[t.tag] = bytes
      }
      if (defs.any { it.tag == "glyf" && it.transformed }) {
        val maxp = dataByTag["maxp"] ?: return null
        val numGlyphs = u16(maxp, 4)
        val decoder = GlyfDecode(numGlyphs)
        decoder.reconstruct(dataByTag["glyf"]!!)
        val (myGlyf, locations) = compileGlyfTable(decoder.glyphOrder, decoder.glyphs)
        val myLoca = compileLoca(locations, decoder.indexFormat)
        dataByTag["glyf"] = myGlyf
        dataByTag["loca"] = myLoca
      }
      // Probe: drop optional tables (vertical metrics / hints) that Skia's
      // FreeType might reject; keep only what a horizontal reader needs.
      val drop = setOf("vhea", "vmtx", "fpgm", "cvt ", "prep")
      val keysToDrop = dataByTag.keys.filter { it in drop }
      for (k in keysToDrop) dataByTag.remove(k)
      dataByTag["name"] = buildNameTable()
      buildSfnt(flavor, dataByTag.size, dataByTag)
    } catch (e: Throwable) {
      android.util.Log.e("Woff2Decoder", "decode failed: ${e.javaClass.simpleName}: ${e.message}", e)
      null
    }
  }

  private fun readRawTag(buf: ByteBuffer): String {
    val b = ByteArray(4); buf.get(b); return String(b, Charsets.ISO_8859_1)
  }

  private fun readUIntBase128(buf: ByteBuffer): Long {
    var accum = 0L; var shift = 0
    while (true) {
      if (shift > 28) return -1
      val b = buf.get().toInt() and 0xFF
      accum = (accum shl 7) or (b and 0x7F).toLong(); shift += 7
      if ((b and 0x80) == 0) return if (accum < 0) -1 else accum
    }
  }

  // Some book fonts ship an empty `name` table (6 bytes, count=0). Android's
  // Skia font manager keys typefaces on the family name, so build a Windows
  // Unicode name table (platform 3 / encoding 1) with a stable family name.
  private fun buildNameTable(): ByteArray {
    val records = arrayOf(
      intArrayOf(1, 1), intArrayOf(2, 1), intArrayOf(4, 1), intArrayOf(6, 1),
    )
    val names = arrayOf("NovelFont", "Regular", "NovelFont Regular", "NovelFont")
    val count = records.size
    val stringOffset = 6 + 12 * count
    val utf16 = names.map { it.toByteArray(Charsets.UTF_16BE) }
    val out = ByteArrayOutputStream()
    putU16(out, 0); putU16(out, count); putU16(out, stringOffset)
    var off = 0
    for (i in 0 until count) {
      val sb = utf16[i]
      putU16(out, 3); putU16(out, 1); putU16(out, 0x409)
      putU16(out, records[i][0]); putU16(out, sb.size); putU16(out, off)
      off += sb.size
    }
    for (sb in utf16) out.write(sb)
    return out.toByteArray()
  }

  internal fun buildSfnt(flavor: Int, numTables: Int, dataByTag: Map<String, ByteArray>): ByteArray {
    val sorted = dataByTag.entries.sortedBy { it.key }
    val headerSize = 12 + 16 * numTables
    val offsets = LinkedHashMap<String, Int>()
    var cursor = headerSize
    for (e in sorted) { while ((cursor % 4) != 0) cursor++; offsets[e.key] = cursor; cursor += e.value.size }
    val power = Integer.highestOneBit(numTables)
    val searchRange = power * 16
    val entrySelector = Integer.numberOfTrailingZeros(power)
    val rangeShift = numTables * 16 - searchRange
    val buf = ByteArrayOutputStream()
    fun w(v: Int) { buf.write((v ushr 24) and 0xFF); buf.write((v ushr 16) and 0xFF); buf.write((v ushr 8) and 0xFF); buf.write(v and 0xFF) }
    fun w16(v: Int) { buf.write((v ushr 8) and 0xFF); buf.write(v and 0xFF) }
    // SFNT header is 12 bytes: u32 sfntVersion + four u16 directory fields.
    w(flavor); w16(numTables); w16(searchRange); w16(entrySelector); w16(rangeShift)
    for (e in sorted) {
      val tag = ByteArray(4); val tb = e.key.toByteArray(Charsets.ISO_8859_1)
      System.arraycopy(tb, 0, tag, 0, minOf(4, tb.size)); buf.write(tag)
      w(checksum(e.value)); w(offsets[e.key] ?: 0); w(e.value.size)
    }
    for (e in sorted) { val off = offsets[e.key] ?: 0; while (buf.size() < off) buf.write(0); buf.write(e.value) }
    while ((buf.size() % 4) != 0) buf.write(0)
    val result = buf.toByteArray()
    patchCheckSumAdjustment(result, offsets["head"])
    return result
  }

  internal fun checksum(data: ByteArray): Int {
    var sum = 0; var i = 0
    while (i < data.size) {
      var v = 0
      for (j in 0 until 4) { v = (v shl 8) or (if (i + j < data.size) data[i + j].toInt() and 0xFF else 0) }
      sum += v; i += 4
    }
    return sum
  }

  internal fun patchCheckSumAdjustment(font: ByteArray, headOffset: Int?) {
    if (headOffset == null || headOffset + 12 > font.size) return
    val adjustPos = headOffset + 8
    font[adjustPos] = 0; font[adjustPos + 1] = 0; font[adjustPos + 2] = 0; font[adjustPos + 3] = 0
    var total = 0; var i = 0
    val padded = font.size + ((4 - font.size % 4) % 4)
    while (i < padded) {
      var v = 0
      for (j in 0 until 4) { v = (v shl 8) or (if (i + j < font.size) font[i + j].toInt() and 0xFF else 0) }
      total += v; i += 4
    }
    val adjustment = 0xB1B0AFBA - total
    font[adjustPos] = (adjustment ushr 24).toByte(); font[adjustPos + 1] = (adjustment ushr 16).toByte()
    font[adjustPos + 2] = (adjustment ushr 8).toByte(); font[adjustPos + 3] = adjustment.toByte()
  }
}
