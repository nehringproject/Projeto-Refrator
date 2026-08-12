package dev.agentworkbench;

oneway interface ILiteLlmCallback {
    void onEvent(String eventJson);
    void onCompleted(String resultJson);
    void onError(String errorJson);
}
