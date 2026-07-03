package gg.ginco.hytest.runtime.api

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class HytaleTest(
    val value: String = "",
    val timeoutSeconds: Long = 30
)
