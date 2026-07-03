package gg.ginco.hytest.runtime.report

import com.hypixel.hytale.logger.backend.HytaleLoggerBackend

object ResultReporter {

    const val PREFIX = "HYTEST "

    fun runStarted(count: Int) {
        emit(mapOf("type" to "run_started", "count" to count))
    }

    fun testStarted(className: String, method: String) {
        emit(linkedMapOf("type" to "test_started", "class" to className, "method" to method))
    }

    fun testFinished(className: String, method: String, passed: Boolean, durationMs: Long, error: Throwable?) {
        val frame = linkedMapOf<String, Any?>(
            "type" to "test_finished",
            "class" to className,
            "method" to method,
            "status" to if (passed) "passed" else "failed",
            "durationMs" to durationMs
        )
        if (!passed && error != null) frame["error"] = errorToString(error)
        emit(frame)
    }

    fun runFinished(total: Int, passed: Int, failed: Int, exitCode: Int) {
        emit(linkedMapOf(
            "type" to "run_finished",
            "total" to total,
            "passed" to passed,
            "failed" to failed,
            "exitCode" to exitCode
        ))
    }

    fun warning(message: String) {
        emit(linkedMapOf("type" to "warning", "message" to message))
    }

    private fun emit(frame: Map<String, *>) {
        HytaleLoggerBackend.REAL_SOUT.println("$PREFIX${Json.stringify(frame)}")
    }

    private fun errorToString(t: Throwable): String = buildString {
        append(t.javaClass.name)
        if (t.message != null) append(": ").append(t.message)
        for (el in t.stackTrace) append("\n\tat ").append(el)
    }
}
