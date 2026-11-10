package example.spring

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/** A Spring AI tool executed in the application, called by the native Harness through MCP. */
class CalculatorTools {
    @Tool(name = "add", description = "Add two integers and return their sum")
    fun add(
        @ToolParam(description = "First integer") left: Int,
        @ToolParam(description = "Second integer") right: Int,
    ): Int = Math.addExact(left, right)
}
