package gg.ginco.hytest.runtime.runner

import java.lang.reflect.Method

data class TestDescriptor(
    val testClass: Class<*>,
    val method: Method,
    val displayName: String,
    val timeoutSeconds: Long
)
