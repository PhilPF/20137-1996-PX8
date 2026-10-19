package com.philpf.solarsystemwallpaper

/**
 * Text formats for the asteroid's element table. Plain Kotlin, no Android dependencies, so the
 * parsing can be tested off-device.
 */
object EphemerisFormats {

    private val DEFAULT_HORIZONS_COLUMNS = listOf(
        "JDTDB", "Calendar Date (TDB)", "EC", "QR", "IN", "OM", "W", "Tp", "N", "MA", "TA", "A", "AD", "PR",
    )

    /**
     * Parses the `result` text of a JPL Horizons `EPHEM_TYPE=ELEMENTS`, `CSV_FORMAT=YES` request:
     * one row per epoch between `$$SOE` and `$$EOE`. Column positions come from the header line
     * (the last line before `$$SOE` that names `JDTDB`), falling back to Horizons' documented
     * order. Returns rows sorted by epoch; empty if the text holds no ephemeris (e.g. Horizons
     * answered with an error because the requested span is outside its data).
     */
    fun parseHorizonsElements(text: String): List<AsteroidOrbit> {
        val soe = text.indexOf("\$\$SOE")
        val eoe = text.indexOf("\$\$EOE")
        if (soe < 0 || eoe < 0 || eoe < soe) return emptyList()

        val header = text.substring(0, soe).lines().lastOrNull { it.contains("JDTDB") }
        val columns = header?.split(',')?.map { it.trim() }?.takeIf { it.size >= 12 } ?: DEFAULT_HORIZONS_COLUMNS
        fun col(name: String) = columns.indexOf(name)
        val iJd = col("JDTDB"); val iEc = col("EC"); val iIn = col("IN"); val iOm = col("OM")
        val iW = col("W"); val iN = col("N"); val iMa = col("MA"); val iA = col("A")
        if (listOf(iJd, iEc, iIn, iOm, iW, iMa, iA).any { it < 0 }) return emptyList()

        val rows = ArrayList<AsteroidOrbit>()
        for (line in text.substring(soe + 5, eoe).lines()) {
            if (line.isBlank()) continue
            val parts = line.split(',').map { it.trim() }
            fun num(idx: Int): Double? = parts.getOrNull(idx)?.toDoubleOrNull()
            val jd = num(iJd) ?: continue
            val a = num(iA) ?: continue
            val e = num(iEc) ?: continue
            val i = num(iIn) ?: continue
            val om = num(iOm) ?: continue
            val w = num(iW) ?: continue
            val ma = num(iMa) ?: continue
            if (a <= 0.0 || e < 0.0 || e >= 1.0) continue
            rows.add(AsteroidOrbit(jd, a, e, i, om, w, ma, if (iN >= 0) num(iN) else null))
        }
        rows.sortBy { it.epochJd }
        return rows
    }

    /** Cache file: first line `#<source label>`, then `epoch,a,e,i,om,w,ma,n` per row (n may be empty). */
    fun encode(label: String, rows: List<AsteroidOrbit>): String = buildString {
        append('#').append(label.replace('\n', ' ')).append('\n')
        for (r in rows) {
            append(r.epochJd).append(',').append(r.a).append(',').append(r.e).append(',')
            append(r.i).append(',').append(r.om).append(',').append(r.w).append(',').append(r.ma).append(',')
            if (r.n != null) append(r.n)
            append('\n')
        }
    }

    fun decode(text: String): Pair<String, List<AsteroidOrbit>> {
        var label = ""
        val rows = ArrayList<AsteroidOrbit>()
        for (line in text.lines()) {
            if (line.startsWith("#")) {
                label = line.substring(1)
                continue
            }
            val p = line.split(',')
            if (p.size < 7) continue
            val v = p.map { it.trim().toDoubleOrNull() }
            if (v.take(7).any { it == null }) continue
            rows.add(AsteroidOrbit(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!, v[5]!!, v[6]!!, v.getOrNull(7)))
        }
        rows.sortBy { it.epochJd }
        return label to rows
    }
}
