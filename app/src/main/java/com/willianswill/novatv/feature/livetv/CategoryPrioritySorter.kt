package com.willianswill.novatv.feature.livetv

import com.willianswill.novatv.core.database.entity.CategoryEntity
import java.text.Normalizer

object CategoryPrioritySorter {

    private val combiningMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")

    private fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
        return combiningMarksRegex.replace(decomposed, "")
    }

    private fun priorityOf(categoryName: String): Int {
        val n = normalize(categoryName)
        return when {
            n.contains("aberto") || n.contains("aberta") -> 1
            n.contains("globo") -> 2
            n.contains("record") -> 3
            n.contains("sbt") -> 4
            n.contains("filme") || n.contains("serie") || n.contains("telecine") ||
                n.contains("hbo") || n.contains("cinema") || n.contains("megapix") -> 5
            n.contains("futebol") || n.contains("esporte") || n.contains("sport") ||
                n.contains("premiere") || n.contains("espn") || n.contains("caze") ||
                n.contains("dazn") || n.contains("jogo") -> 6
            else -> 100
        }
    }

    fun sortLiveCategories(categories: List<CategoryEntity>): List<CategoryEntity> {
        return categories.sortedWith(
            compareBy<CategoryEntity> { priorityOf(it.name) }
                .thenBy { it.sortOrder }
                .thenBy { normalize(it.name) }
        )
    }
}
