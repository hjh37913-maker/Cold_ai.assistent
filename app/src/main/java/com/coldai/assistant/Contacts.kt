package com.coldai.assistant

import android.content.ContentResolver
import android.provider.ContactsContract

object ContactSearch {
    /** Повертає до 6 пар (ім'я, номер). Враховує відмінки: «мамі» → «Мама», «Олексію» → «Олексій». */
    fun find(cr: ContentResolver, spoken: String): List<Pair<String, String>> {
        val all = ArrayList<Pair<String, String>>()
        try {
            val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            cr.query(phone, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val p = c.getString(1) ?: continue
                    all.add(n to p)
                }
            }
        } catch (_: Exception) {
        }
        val qs = norm(spoken).split(' ').filter { it.isNotBlank() }
        if (qs.isEmpty()) return emptyList()
        fun stems(w: String): List<String> =
            listOf(w, w.dropLast(1), w.dropLast(2)).filter { it.length >= 3 }.distinct().ifEmpty { listOf(w) }
        val hits = all.filter { entry ->
            val words = norm(entry.first).split(' ')
            qs.all { q -> stems(q).any { s -> words.any { it.startsWith(s) } } }
        }
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (h in hits) {
            val key = h.second.filter { it.isDigit() }.takeLast(9)
            if (seen.add(key)) out.add(h)
        }
        return out.sortedBy { e -> if (norm(e.first).split(' ').any { it in qs }) 0 else 1 }.take(6)
    }
}
