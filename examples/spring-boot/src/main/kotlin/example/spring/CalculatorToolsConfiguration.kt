package example.spring

import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class CalculatorToolsConfiguration {
    @Bean
    fun calculatorTools(): CalculatorTools = CalculatorTools()

    @Bean
    fun calculatorCallbacks(calculator: CalculatorTools): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(calculator).build()
}
