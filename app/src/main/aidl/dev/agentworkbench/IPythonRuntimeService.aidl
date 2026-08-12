package dev.agentworkbench;

interface IPythonRuntimeService {
    String status();
    String execute(String workspaceId, String workspacePath, String source, long timeoutMillis);
    String runFile(String workspaceId, String workspacePath, String path, long timeoutMillis);
    String replOpen(String workspaceId, String workspacePath);
    String replWrite(String workspaceId, String workspacePath, String sessionId, String source);
    String replInterrupt(String sessionId);
    String replClose(String sessionId);
    String packageInstall(String workspaceId, String requirement);
    String packageList(String workspaceId);
    String packageRemove(String workspaceId, String distribution);
    String environmentStatus(String workspaceId);
    String environmentReset(String workspaceId);
    String test(String workspaceId, String workspacePath);
    void shutdown();
}
