package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * Pure-MIT SLF4J 2.0 [SLF4JServiceProvider] registered via
 * `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`.
 *
 * Note: SLF4J 2.0.17 invokes [getMDCAdapter] during `LoggerFactory.earlyBindMDCAdapter()`
 * *before* calling [initialize], so [markerFactory] and [mdcAdapter] are initialized eagerly.
 */
class FujiSlf4jServiceProvider : SLF4JServiceProvider {
    private val markerFactory: IMarkerFactory = BasicMarkerFactory()
    private val mdcAdapter: MDCAdapter = BasicMDCAdapter()
    @Volatile private var loggerFactory: FujiLoggerContext? = null

    override fun getLoggerFactory(): ILoggerFactory =
        loggerFactory ?: synchronized(this) {
            loggerFactory ?: FujiLoggerContext().also { loggerFactory = it }
        }

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getRequestedApiVersion(): String = REQUESTED_API_VERSION

    override fun initialize() {
        getLoggerFactory()
    }

    companion object {
        const val REQUESTED_API_VERSION: String = "2.0.99"
    }
}
