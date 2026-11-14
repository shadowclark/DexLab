package com.dexlab.app.core

import java.io.File

/** Small hand-off object used when navigating from a file entry into a tool screen. */
object ToolTarget {
    var file: File? = null
    var title: String = ""
    var seed: String? = null
    var seedFormat: String? = null
}
