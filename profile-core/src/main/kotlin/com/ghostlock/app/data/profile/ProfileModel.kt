package com.ghostlock.app.data.profile

/** Typed, path-carrying configuration error. */
data class ConfigError(val fieldPath: String, val message: String) {
    override fun toString(): String = "$fieldPath: $message"
}
