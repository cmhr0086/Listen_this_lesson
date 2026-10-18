package com.cmhr.listen

import android.content.Context

/** Release builds ship no demo data; see the debug source set. */
object DemoData {
    suspend fun seedIfEmpty(context: Context) = Unit
}
