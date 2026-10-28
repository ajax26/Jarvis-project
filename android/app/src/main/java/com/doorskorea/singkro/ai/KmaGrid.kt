package com.doorskorea.singkro.ai

import org.json.JSONArray

/**
 * Region name -> KMA forecast grid (nx, ny) for every 행정구역, from data/kma_grid.json
 * (built by data/build_kma_grid.py). Same matching as resolve_region() in pc-test/assistant.py.
 */
class KmaGrid(json: String) {
    private class Row(val names: List<String>, val nx: Int, val ny: Int)

    private val rows: List<Row> = JSONArray(json).let { arr ->
        (0 until arr.length()).map { i ->
            val r = arr.getJSONArray(i)
            Row((0..2).map { r.getString(it) }.filter { it.isNotEmpty() }.map(::norm), r.getInt(3), r.getInt(4))
        }
    }

    /**
     * "강남구 역삼동" -> (61, 125). Every word must match a different level (exact, else prefix);
     * the most general match wins, so "서울" is 서울특별시 and "강남" is 강남구, not some 강남동.
     */
    fun resolve(location: String): Pair<Int, Int>? {
        val tokens = (ALIASES[location.trim()] ?: location).split(" ").filter { it.isNotBlank() }.map(::norm)
        if (tokens.isEmpty()) return null
        var best: Row? = null
        var bestKey = Pair(Int.MAX_VALUE, 0)
        rows@ for (row in rows) {
            val used = mutableSetOf<Int>()
            var exact = 0
            for (t in tokens) {
                var i = row.names.indices.firstOrNull { it !in used && row.names[it] == t }
                if (i != null) exact++ else i = row.names.indices.firstOrNull { it !in used && row.names[it].startsWith(t) }
                if (i == null) continue@rows
                used += i
            }
            val key = Pair(row.names.size, -exact)
            if (compareValuesBy(key, bestKey, { it.first }, { it.second }) < 0) {
                best = row
                bestKey = key
            }
        }
        return best?.let { it.nx to it.ny }
    }

    companion object {
        private val SUFFIX = Regex("(특별자치시|특별자치도|특별시|광역시|도|시|군|구|동|읍|면)$")

        /** "서울특별시" -> "서울", "역삼1동" -> "역삼1", "제주도" -> "제주". */
        fun norm(name: String): String {
            val s = name.replace(" ", "")
            return SUFFIX.replace(s, "").ifEmpty { s }
        }

        // everyday names missing from the official list (2026 file merged 광주광역시 into 전남광주통합특별시);
        // applied only when the whole location is the alias, so "경기 광주" still means 경기도 광주시
        private val ALIASES = mapOf(
            "광주" to "전남광주통합특별시 서구", "광주광역시" to "전남광주통합특별시 서구",
            "전남" to "전남광주통합특별시", "전라남도" to "전남광주통합특별시", "전북" to "전북특별자치도", "전라북도" to "전북특별자치도",
            "충북" to "충청북도", "충남" to "충청남도", "경북" to "경상북도", "경남" to "경상남도",
        )
    }
}
