package dev.agentworkbench;

import android.os.ParcelFileDescriptor;
import dev.agentworkbench.ILiteLlmCallback;

interface ILiteLlmRuntimeService {
    String status();
    void streamCompletion(String requestId, in ParcelFileDescriptor requestPipe, ILiteLlmCallback callback);
    boolean cancel(String requestId);
    void shutdown();
}
