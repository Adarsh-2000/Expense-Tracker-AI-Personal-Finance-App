package com.ledgernoir.tracker.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the bearer token (for data endpoints) and the paired-device key
 * (X-Device-Key, required by /auth/google to mint a session) to outgoing requests.
 * Uses runBlocking because OkHttp interceptors are synchronous; the DataStore
 * read is fast (in-memory after first load).
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenStore.token.first() }
        val deviceKey = runBlocking { tokenStore.deviceKey.first() }
        val builder = chain.request().newBuilder()
        if (token.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $token")
        }
        if (deviceKey.isNotBlank()) {
            builder.addHeader("X-Device-Key", deviceKey)
        }
        val response = chain.proceed(builder.build())
        // Session expired / invalidated server-side → clear the stale token so the UI
        // routes back to sign-in instead of looping on 401s. (Auth endpoints carry no
        // session token, so clearing on their 401s is a harmless no-op.)
        if (response.code == 401 && token.isNotBlank()) {
            runBlocking { tokenStore.clearToken() }
        }
        return response
    }
}
