// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** Search normalization is independent of the server's exact-title matching. */
object SearchTitles {
    fun key(value: String): String = java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
    fun family(value: String): String = key(SeriesSeasons.base(value))
    fun matches(query: String,title: String): Boolean {
        val needle=family(query).ifEmpty { key(query) }
        return needle.isNotEmpty() && key(title).contains(needle)
    }
    fun score(query: String,title: String): Int {
        val needle=family(query).ifEmpty { key(query) };val base=family(title)
        if(needle.isEmpty())return 100
        val rank=when { base==needle->0; base.startsWith(needle)->10+minOf(8,base.length-needle.length); base.contains(needle)->20+minOf(8,base.length-needle.length); key(title).contains(key(query))->30;else->100 }
        val requested=SeriesSeasons.parse(query)
        return rank+(if(requested.matched && SeriesSeasons.season(title)!=requested.season)2 else 0)
    }
}
