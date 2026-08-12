package dev.agentworkbench

import android.app.ActivityManager
import android.content.Context

data class LocalGgufRuntimeProfile(
    val contextTokens: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
)

internal object LocalGgufRuntimePlanner {
    private const val GIB = 1_073_741_824L

    fun forDevice(context: Context, model: LocalGgufInfo): LocalGgufRuntimeProfile {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return plan(memory.totalMem, memory.availMem, model.bytes, model.contextLength)
    }

    fun plan(
        totalMemoryBytes: Long,
        availableMemoryBytes: Long,
        modelBytes: Long,
        trainedContextTokens: Int?,
    ): LocalGgufRuntimeProfile {
        require(totalMemoryBytes > 0)
        require(availableMemoryBytes >= 0)
        require(modelBytes > 0)

        val deviceCeiling = when {
            totalMemoryBytes >= 12L * GIB -> 16_384
            totalMemoryBytes >= 8L * GIB -> 8_192
            totalMemoryBytes >= 5L * GIB -> 4_096
            else -> 2_048
        }
        val modelCeiling = when {
            modelBytes * 100L >= totalMemoryBytes * 30L -> 2_048
            modelBytes * 100L >= totalMemoryBytes * 20L -> 4_096
            modelBytes * 100L >= totalMemoryBytes * 12L -> 8_192
            else -> 16_384
        }
        val pressureCeiling = when {
            availableMemoryBytes * 100L < totalMemoryBytes * 20L -> 2_048
            availableMemoryBytes * 100L < totalMemoryBytes * 30L -> 4_096
            else -> 16_384
        }
        val trainedCeiling = trainedContextTokens?.coerceAtLeast(1_024) ?: 65_536
        val selected = minOf(deviceCeiling, modelCeiling, pressureCeiling, trainedCeiling)
            .coerceIn(1_024, 16_384)
        return LocalGgufRuntimeProfile(selected, totalMemoryBytes, availableMemoryBytes)
    }
}
