package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.configuration.HarnessConfig

/** Applied after properties and callbacks, in Spring's ordered bean sequence. */
fun interface HarnessConfigCustomizer {
    fun customize(builder: HarnessConfig.Builder)
}
