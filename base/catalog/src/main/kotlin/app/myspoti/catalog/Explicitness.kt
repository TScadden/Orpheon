/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Explicitness

/**
 * A source's explicitness word or flag as the tri-state the models carry. Only a stated
 * judgement counts: a missing word or flag is UNKNOWN, never a guess at CLEAN — an
 * unjudged song must stay ordinary everywhere (no badge, no filtering).
 */

/** iTunes' `trackExplicitness`: "explicit", "cleaned" or "notExplicit", absent otherwise. */
fun explicitnessOf(word: String?): Explicitness = when (word) {
    "explicit" -> Explicitness.EXPLICIT
    "cleaned", "notExplicit" -> Explicitness.CLEAN
    else -> Explicitness.UNKNOWN
}

/** A stated boolean: Deezer's `explicit_lyrics`, a script provider's `explicit`. */
fun explicitnessOf(flag: Boolean?): Explicitness = when (flag) {
    true -> Explicitness.EXPLICIT
    false -> Explicitness.CLEAN
    null -> Explicitness.UNKNOWN
}
