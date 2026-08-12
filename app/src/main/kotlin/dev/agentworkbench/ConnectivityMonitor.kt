package dev.agentworkbench

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Diferencia "o aparelho está sem rede" de "o provider recusou".
 *
 * Sem isso os dois caem no mesmo caminho de erro, e o motor gasta a lista inteira de rotas
 * tentando um provider atrás do outro quando nenhum deles tem como responder. Exige a
 * permissão ACCESS_NETWORK_STATE, declarada no manifesto principal.
 */
object ConnectivityMonitor {

    /**
     * Texto único usado quando um turno para por falta de rede. É constante (e não literal
     * repetido) justamente pra interface poder reconhecer esse caso e desenhar em tom calmo,
     * em vez do vermelho de erro real do provider.
     */
    const val OFFLINE_MESSAGE = "Sem conexão. Retomo sozinho quando a rede voltar."

    /** Vale como online só se a rede também tiver sido validada — Wi-Fi de portal cativo não conta. */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.activeNetwork
            ?.let(manager::getNetworkCapabilities)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Emite o estado atual e cada mudança depois dele. */
    fun onlineFlow(context: Context): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline(context))
            }

            override fun onLost(network: Network) {
                trySend(isOnline(context))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(isOnline(context))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        trySend(isOnline(context))
        runCatching { manager.registerNetworkCallback(request, callback) }
        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
