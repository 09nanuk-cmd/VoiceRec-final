package com.example.voicerec

import android.content.Context
import java.io.File

/**
 * Dizionario personale: quando correggi una trascrizione, l'app confronta il testo prima e dopo,
 * ricorda le sostituzioni (parole o brevi frasi) e le applica alle trascrizioni successive.
 * È cifrato come il resto dell'archivio.
 */
object Learned {
    class Rule(var value: String, var count: Int)

    private fun file(c: Context) = File(Storage.dir(c), "learned.txt.enc")

    private val punct = Regex("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$")
    private val stamp = Regex("^\\[\\d+:\\d\\d(:\\d\\d)?\\]$")
    private fun strip(w: String) = w.replace(punct, "")

    fun load(c: Context): LinkedHashMap<String, Rule> {
        val m = LinkedHashMap<String, Rule>()
        val f = file(c)
        if (!f.exists()) return m
        try {
            for (line in Secure.readText(f).lines()) {
                val p = line.split('\t')
                if (p.size == 3) m[p[0]] = Rule(p[1], p[2].toIntOrNull() ?: 1)
            }
        } catch (e: Exception) {
        }
        return m
    }

    private fun save(c: Context, m: Map<String, Rule>) {
        Secure.writeText(file(c), m.entries.joinToString("\n") { it.key + "\t" + it.value.value + "\t" + it.value.count })
    }

    fun count(c: Context) = load(c).size

    /** Una correzione si applica dopo averla fatta 2 volte, o subito se la parola è lunga (per esempio un nome). */
    fun active(m: Map<String, Rule>): Map<String, String> =
        m.filter { (k, r) -> r.count >= 2 || k.length >= 6 }.mapValues { it.value.value }

    /** Ritorna quante correzioni ha imparato o rafforzato, oppure -1 se il numero di righe non coincide. */
    fun learn(c: Context, oldText: String, newText: String): Int {
        val ol = oldText.lines()
        val nl = newText.lines()
        if (ol.size != nl.size) return -1
        val m = load(c)
        var touched = 0
        for (i in ol.indices) {
            if (ol[i] == nl[i]) continue
            val a = ol[i].split(' ').filter { it.isNotBlank() && !stamp.matches(it) }
            val b = nl[i].split(' ').filter { it.isNotBlank() && !stamp.matches(it) }
            for ((oldBlock, newBlock) in replaceBlocks(a, b)) {
                val oldJoined = oldBlock.joinToString(" ") { strip(it) }
                val value = newBlock.joinToString(" ") { strip(it) }
                val key = oldJoined.lowercase()
                if (key.isBlank() || value.isBlank() || oldJoined == value) continue
                val r = m[key]
                if (r == null) {
                    m[key] = Rule(value, 1)
                } else if (r.value == value) {
                    r.count++
                } else {
                    r.value = value
                    r.count = 1
                }
                touched++
            }
        }
        if (touched > 0) save(c, m)
        return touched
    }

    private fun replaceBlocks(a: List<String>, b: List<String>): List<Pair<List<String>, List<String>>> {
        val na = a.map { strip(it) }
        val nb = b.map { strip(it) }
        val n = na.size
        val m = nb.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (na[i] == nb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Pair<List<String>, List<String>>>()
        val oldBlk = ArrayList<String>()
        val newBlk = ArrayList<String>()
        fun flush() {
            if (oldBlk.isNotEmpty() && newBlk.isNotEmpty() && oldBlk.size <= 3 && newBlk.size <= 4) {
                out.add(ArrayList(oldBlk) to ArrayList(newBlk))
            }
            oldBlk.clear()
            newBlk.clear()
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            if (i < n && j < m && na[i] == nb[j]) {
                flush(); i++; j++
            } else if (j < m && (i == n || dp[i][j + 1] >= dp[i + 1][j])) {
                newBlk.add(b[j]); j++
            } else {
                oldBlk.add(a[i]); i++
            }
        }
        flush()
        return out
    }

    fun apply(rules: Map<String, String>, text: String): String {
        if (rules.isEmpty()) return text
        val w = text.split(' ')
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            var replaced = false
            for (len in 3 downTo 1) {
                if (i + len > w.size) continue
                val key = (i until i + len).joinToString(" ") { strip(w[it]) }.lowercase()
                var rep = rules[key] ?: continue
                val first = w[i]
                val last = w[i + len - 1]
                val lead = first.takeWhile { !it.isLetterOrDigit() }
                val trail = last.takeLastWhile { !it.isLetterOrDigit() }
                val core = strip(first)
                if (core.isNotEmpty() && core[0].isUpperCase() && rep.isNotEmpty() && rep[0].isLowerCase()) {
                    rep = rep.replaceFirstChar { it.uppercase() }
                }
                out.add(lead + rep + trail)
                i += len
                replaced = true
                break
            }
            if (!replaced) { out.add(w[i]); i++ }
        }
        return out.joinToString(" ")
    }
}
