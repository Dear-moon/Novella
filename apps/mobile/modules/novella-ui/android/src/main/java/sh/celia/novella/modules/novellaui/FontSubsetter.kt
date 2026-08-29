package sh.celia.novella.modules.novellaui

import java.io.ByteArrayOutputStream

/**
 * Subsets a decoded TrueType SFNT to the codepoints of the rendered content.
 *
 * The reader renders one chapter at a time; keeping only that chapter's glyphs
 * (plus the transitive composite components) yields a small font and keeps the
 * registered typeface well within any platform load limit. Composite glyph IDs
 * are remapped to the subset order, and `loca` is emitted in long (format 1)
 * form so it is never bounded by the short-loca size cap. Assembly reuses
 * Woff2Decoder.
 */
object FontSubsetter {

  private const val ARG_1_AND_2_ARE_WORDS = 0x0001
  private const val WE_HAVE_A_SCALE = 0x0008
  private const val MORE_COMPONENTS = 0x0020
  private const val WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
  private const val WE_HAVE_A_TWO_BY_TWO = 0x0080
  private const val WE_HAVE_INSTRUCTIONS = 0x0100

  private class CompEntry(val gidPos: Int, val gid: Int)

  /**
   * Returns a subset SFNT covering [keepCodepoints], or null on any structural
   * issue so the caller can fall back to the (unsubset) font.
   */
  fun subset(sfnt: ByteArray, keepCodepoints: IntArray): ByteArray? {
    if (sfnt.size < 12) return fail("small sfnt ${sfnt.size}")
    if (keepCodepoints.isEmpty()) return fail("empty codepoints")
    return try {
      val flavor = Woff2Decoder.u32(sfnt, 0)
      val numTables = Woff2Decoder.u16(sfnt, 4)
      val offs = HashMap<String, Int>()
      val lens = HashMap<String, Int>()
      val originalTags = ArrayList<String>()
      for (i in 0 until numTables) {
        val rec = 12 + i * 16
        val tag = String(sfnt, rec, 4, Charsets.ISO_8859_1)
        val o = Woff2Decoder.u32(sfnt, rec + 8)
        val l = Woff2Decoder.u32(sfnt, rec + 12)
        if (o < 0 || l < 0 || o + l > sfnt.size) continue
        originalTags.add(tag); offs[tag] = o; lens[tag] = l
      }
      for (req in arrayOf("cmap", "glyf", "loca", "hmtx", "head", "maxp", "hhea")) {
        if (offs[req] == null) return fail("missing table $req; tags=${originalTags.joinToString(",")}")
      }

      val head = copySlice(sfnt, offs["head"]!!, lens["head"]!!)
      val maxp = copySlice(sfnt, offs["maxp"]!!, lens["maxp"]!!)
      val hhea = copySlice(sfnt, offs["hhea"]!!, lens["hhea"]!!)
      if (lens["maxp"]!! < 6 || lens["head"]!! < 52 || lens["hhea"]!! < 36) {
        return fail("short tables maxp=${lens["maxp"]} head=${lens["head"]} hhea=${lens["hhea"]}")
      }

      val numGlyphs = Woff2Decoder.u16(maxp, 4)
      val indexFormat = Woff2Decoder.s16(head, 50)
      val numHMetrics = Woff2Decoder.u16(hhea, 34)
      val glyfOff = offs["glyf"]!!
      val locaOff = offs["loca"]!!
      val locaLen = lens["loca"]!!
      val hmtxOff = offs["hmtx"]!!

      fun gidStart(gid: Int): Int {
        if (gid > numGlyphs) return -1
        val v = if (indexFormat == 0) Woff2Decoder.u16(sfnt, locaOff + gid * 2) * 2
        else Woff2Decoder.u32(sfnt, locaOff + gid * 4)
        return v
      }

      val cpToGid = HashMap<Int, Int>()
      lookupCmap(sfnt, offs["cmap"]!!, lens["cmap"]!!, keepCodepoints, cpToGid)

      // Composites reference other glyphs; the closure retains them transitively.
      val kept = LinkedHashSet<Int>()
      kept.add(0)
      for (cp in keepCodepoints) {
        val gid = cpToGid[cp]
        if (gid != null && gid != 0 && gid < numGlyphs) kept.add(gid)
      }
      val queue = ArrayList<Int>(kept)
      val seen = HashSet<Int>(kept)
      var qi = 0
      while (qi < queue.size) {
        val gid = queue[qi].also { qi++ }
        for (comp in compositeComponents(sfnt, glyfOff, indexFormat, ::gidStart, gid)) {
          if (seen.add(comp.gid)) { kept.add(comp.gid); queue.add(comp.gid) }
        }
      }

      // .notdef stays 0; the rest ascending gives a dense order the cmap reuses.
      val oldToNew = HashMap<Int, Int>()
      oldToNew[0] = 0
      val ordered = ArrayList<Int>()
      ordered.add(0)
      for (gid in kept.sorted()) {
        if (gid == 0) continue
        oldToNew[gid] = ordered.size
        ordered.add(gid)
      }
      val newCount = ordered.size

      val glyfData = ByteArrayOutputStream()
      val locaData = ByteArrayOutputStream()
      var cur = 0
      for (oldGid in ordered) {
        val start = gidStart(oldGid)
        val end = gidStart(oldGid + 1)
        if (start < 0 || end < start) return fail("loca invalid gid=$oldGid start=$start end=$end")
        // Loca records a start offset; an empty glyph must span zero bytes.
        Woff2Decoder.putU32(locaData, cur)
        val len = end - start
        if (len == 0) continue
        val raw = copySlice(sfnt, glyfOff + start, len)
        val glyph = if (isComposite(sfnt, glyfOff + start)) remapComposite(sfnt, glyfOff + start, raw, oldToNew) else raw
        glyfData.write(glyph)
        cur += glyph.size
      }
      Woff2Decoder.putU32(locaData, cur)

      val hmtxData = ByteArrayOutputStream()
      for (oldGid in ordered) {
        val (adv, lsb) = metricFor(sfnt, hmtxOff, numHMetrics, numGlyphs, oldGid)
        Woff2Decoder.putU16(hmtxData, adv)
        Woff2Decoder.putS16(hmtxData, lsb)
      }

      patchU16(maxp, 4, newCount)
      patchU16(hhea, 34, newCount)
      patchU16(head, 50, 1) // long loca

      val dataByTag = LinkedHashMap<String, ByteArray>()
      for (tag in originalTags) {
        if (tag == "vhea" || tag == "vmtx" || tag == "cvt " || tag == "fpgm" || tag == "prep") continue
        dataByTag[tag] = copySlice(sfnt, offs[tag]!!, lens[tag]!!)
      }
      dataByTag["glyf"] = glyfData.toByteArray()
      dataByTag["loca"] = locaData.toByteArray()
      dataByTag["hmtx"] = hmtxData.toByteArray()
      dataByTag["head"] = head
      dataByTag["maxp"] = maxp
      dataByTag["hhea"] = hhea
      dataByTag["cmap"] = buildCmapFormat12(cpToGid, oldToNew, keepCodepoints) ?: return fail("cmap build null (cpToGid=${cpToGid.size})")
      dataByTag.remove("post") // post glyph IDs are stale after remap.

      Woff2Decoder.buildSfnt(flavor, dataByTag.size, dataByTag)
    } catch (e: Throwable) {
      android.util.Log.e("FontSubsetter", "subset failed: ${e.javaClass.simpleName}: ${e.message}", e)
      null
    }
  }

  private fun fail(reason: String): ByteArray? {
    android.util.Log.e("FontSubsetter", "subset null -> $reason")
    return null
  }

  private fun lookupCmap(sfnt: ByteArray, cmapOff: Int, cmapLen: Int, keep: IntArray, out: MutableMap<Int, Int>) {
    if (cmapLen < 4) return
    val numTables = Woff2Decoder.u16(sfnt, cmapOff + 2)
    // Format-12 first (full Unicode), then format-4 (BMP); a codepoint may only
    // exist in one subtable, so probe each until it resolves.
    val fmt12 = ArrayList<Int>()
    val fmt4 = ArrayList<Int>()
    for (i in 0 until numTables) {
      val rec = cmapOff + 4 + i * 8
      if (rec + 8 > cmapOff + cmapLen) break
      val sub = cmapOff + Woff2Decoder.u32(sfnt, rec + 4)
      if (sub + 2 > cmapOff + cmapLen) continue
      when (Woff2Decoder.u16(sfnt, sub)) {
        12 -> fmt12.add(sub)
        4 -> fmt4.add(sub)
      }
    }
    val subs = ArrayList<Int>(fmt12.size + fmt4.size)
    subs.addAll(fmt12); subs.addAll(fmt4)
    if (subs.isEmpty()) return
    for (cp in keep) {
      for (sub in subs) {
        val gid = if (sub in fmt12) cmap12Lookup(sfnt, sub, cp) else cmap4Lookup(sfnt, sub, cp)
        if (gid != 0) { out[cp] = gid; break }
      }
    }
  }

  private fun cmap12Lookup(sfnt: ByteArray, sub: Int, cp: Int): Int {
    val n = Woff2Decoder.u32(sfnt, sub + 12)
    if (n < 0) return 0
    for (i in 0 until n) {
      val g = sub + 16 + i * 12
      val startCp = Woff2Decoder.u32(sfnt, g)
      val endCp = Woff2Decoder.u32(sfnt, g + 4)
      val startGid = Woff2Decoder.u32(sfnt, g + 8)
      if (cp in startCp..endCp) return startGid + (cp - startCp)
    }
    return 0
  }

  private fun cmap4Lookup(sfnt: ByteArray, sub: Int, cp: Int): Int {
    val segCountX2 = Woff2Decoder.u16(sfnt, sub + 6)
    val segCount = segCountX2 / 2
    if (segCount == 0) return 0
    val endCodeOff = 14
    val startCodeOff = endCodeOff + segCount * 2 + 2
    val idDeltaOff = startCodeOff + segCount * 2
    val idRangeOffsetOff = idDeltaOff + segCount * 2
    var lo = 0
    var hi = segCount - 1
    var seg = -1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      if (cp <= Woff2Decoder.u16(sfnt, sub + endCodeOff + mid * 2)) { seg = mid; hi = mid - 1 } else lo = mid + 1
    }
    if (seg == -1) return 0
    if (cp < Woff2Decoder.u16(sfnt, sub + startCodeOff + seg * 2)) return 0
    val delta = Woff2Decoder.s16(sfnt, sub + idDeltaOff + seg * 2)
    val rangeOff = Woff2Decoder.u16(sfnt, sub + idRangeOffsetOff + seg * 2)
    if (rangeOff == 0) return (cp + delta) and 0xFFFF
    // idRangeOffset is relative to this segment's idRangeOffset element address.
    val gidOff = sub + idRangeOffsetOff + seg * 2 + rangeOff + (cp - Woff2Decoder.u16(sfnt, sub + startCodeOff + seg * 2)) * 2
    val gid = Woff2Decoder.u16(sfnt, gidOff)
    return if (gid == 0) 0 else (gid + delta) and 0xFFFF
  }

  private fun buildCmapFormat12(cpToGid: Map<Int, Int>, oldToNew: Map<Int, Int>, keepCodepoints: IntArray): ByteArray? {
    // Merge consecutive codepoints whose glyph IDs are also consecutive into runs.
    data class Group(var startCp: Int, var endCp: Int, var startGid: Int)
    val groups = ArrayList<Group>()
    val cps = keepCodepoints.sorted()
    for (cp in cps) {
      val oldGid = cpToGid[cp] ?: continue
      if (oldGid == 0) continue
      val newGid = oldToNew[oldGid] ?: continue
      if (groups.isNotEmpty()) {
        val g = groups[groups.size - 1]
        if (cp == g.endCp + 1 && newGid == g.startGid + (cp - g.startCp)) {
          g.endCp = cp
          continue
        }
      }
      groups.add(Group(cp, cp, newGid))
    }
    val n = groups.size
    if (n == 0) return null
    val out = ByteArrayOutputStream()
    Woff2Decoder.putU16(out, 0); Woff2Decoder.putU16(out, 1) // cmap header
    Woff2Decoder.putU16(out, 3); Woff2Decoder.putU16(out, 10); Woff2Decoder.putU32(out, 12) // platform 3, enc 10
    Woff2Decoder.putU16(out, 12); Woff2Decoder.putU16(out, 0); Woff2Decoder.putU32(out, 16 + 12 * n)
    Woff2Decoder.putU32(out, 0); Woff2Decoder.putU32(out, n)
    for (g in groups) {
      Woff2Decoder.putU32(out, g.startCp); Woff2Decoder.putU32(out, g.endCp); Woff2Decoder.putU32(out, g.startGid)
    }
    return out.toByteArray()
  }

  private fun isComposite(sfnt: ByteArray, glyphStart: Int): Boolean {
    if (glyphStart + 2 > sfnt.size) return false
    return Woff2Decoder.s16(sfnt, glyphStart) == -1
  }

  private fun compositeComponents(
    sfnt: ByteArray,
    glyfOff: Int,
    indexFormat: Int,
    gidStart: (Int) -> Int,
    gid: Int,
  ): List<CompEntry> {
    val start = gidStart(gid)
    val end = gidStart(gid + 1)
    if (start < 0 || end <= start) return emptyList()
    if (!isComposite(sfnt, glyfOff + start)) return emptyList()
    val list = ArrayList<CompEntry>()
    var p = start + 10
    var more = true
    while (more) {
      if (p + 4 > end) return emptyList()
      val flags = Woff2Decoder.u16(sfnt, glyfOff + p)
      val compGid = Woff2Decoder.u16(sfnt, glyfOff + p + 2)
      val gidPos = p + 2
      p += 4
      if ((flags and ARG_1_AND_2_ARE_WORDS) != 0) p += 4 else p += 2
      if ((flags and WE_HAVE_A_SCALE) != 0) p += 2
      else if ((flags and WE_HAVE_AN_X_AND_Y_SCALE) != 0) p += 4
      else if ((flags and WE_HAVE_A_TWO_BY_TWO) != 0) p += 8
      if (p > end) return emptyList()
      list.add(CompEntry(gidPos, compGid))
      more = (flags and MORE_COMPONENTS) != 0
    }
    return list
  }

  private fun remapComposite(sfnt: ByteArray, glyphStart: Int, raw: ByteArray, oldToNew: Map<Int, Int>): ByteArray {
    val end = raw.size
    val out = raw.copyOf()
    var p = 10 // relative
    var more = true
    while (more) {
      if (p + 4 > end) return raw
      val flags = ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
      val gidPos = p + 2
      val mapped = oldToNew[Woff2Decoder.u16(raw, gidPos)]
      if (mapped != null) {
        out[gidPos] = (mapped shr 8).toByte()
        out[gidPos + 1] = mapped.toByte()
      } else {
        return raw // component unexpectedly dropped; keep the original bytes.
      }
      p += 4
      if ((flags and ARG_1_AND_2_ARE_WORDS) != 0) p += 4 else p += 2
      if ((flags and WE_HAVE_A_SCALE) != 0) p += 2
      else if ((flags and WE_HAVE_AN_X_AND_Y_SCALE) != 0) p += 4
      else if ((flags and WE_HAVE_A_TWO_BY_TWO) != 0) p += 8
      if (p > end) return raw
      more = (flags and MORE_COMPONENTS) != 0
    }
    return out
  }

  private fun metricFor(sfnt: ByteArray, hmtxOff: Int, numHMetrics: Int, numGlyphs: Int, gid: Int): Pair<Int, Int> {
    if (gid < numHMetrics) {
      val o = hmtxOff + gid * 4
      return Pair(Woff2Decoder.u16(sfnt, o), Woff2Decoder.s16(sfnt, o + 2))
    }
    // Trailing glyphs inherit the last advance; each still carries its own lsb.
    val lastAdv = if (numHMetrics >= 1) Woff2Decoder.u16(sfnt, hmtxOff + (numHMetrics - 1) * 4) else 0
    val lsbOff = hmtxOff + numHMetrics * 4 + (gid - numHMetrics) * 2
    return Pair(lastAdv, Woff2Decoder.s16(sfnt, lsbOff))
  }

  private fun patchU16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v shr 8).toByte(); b[off + 1] = v.toByte()
  }

  private fun copySlice(sfnt: ByteArray, off: Int, len: Int): ByteArray {
    if (off < 0 || len < 0 || off + len > sfnt.size) throw IllegalArgumentException("slice")
    return sfnt.copyOfRange(off, off + len)
  }
}
