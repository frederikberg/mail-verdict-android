package com.frederikberg.mailverdict.kit.api

/** Query parameters of a request: a null value is left out, anything else is sent as its text. */
internal fun query(vararg parameters: Pair<String, Any?>): List<Pair<String, String>> =
    parameters.mapNotNull { (name, value) -> value?.let { name to it.toString() } }

/** A repeated parameter (`folder_ids=a&folder_ids=b`), which is how the server reads a list. */
internal fun repeated(
    name: String,
    values: Collection<String>?,
): List<Pair<String, String>> = values.orEmpty().map { name to it }
