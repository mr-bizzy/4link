// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

/**
 * §15a: `icons.*` and `state.*` serve a control surface, not a person's request. A caller that
 * offers functions to a model leaves them out of what the model sees, and refuses to invoke one the
 * model names anyway. [SkillPrompt] does both; a caller building its own list uses these.
 */
object Plumbing {
    private val PREFIXES = listOf("icons.", "state.")

    /** True for a function a model must never be offered or allowed to call. */
    fun isPlumbing(functionId: String): Boolean = PREFIXES.any { functionId.startsWith(it) }

    /** [catalogue] as a model may see it. */
    fun forModel(catalogue: Catalogue): Catalogue =
        catalogue.copy(functions = catalogue.functions.filterNot { isPlumbing(it.id) })

    /** The guard before a model-chosen invoke: null when it may go, else the reason it may not. */
    fun refusalForModel(functionId: String): String? =
        if (isPlumbing(functionId)) "“$functionId” is for control surfaces, not for a model to call" else null
}
