package com.dexlab.app.native

data class NativeFunctionAnalysis(
    val binaryLabel: String,
    val function: ElfFunction,
    val xrefs: FunctionXrefs,
    val graph: NativeCallGraph,
    val cfg: NativeCfg,
    val annotation: NativeAnnotation
) {
    val displayName: String get() = annotation.displayName(function.name)
}
