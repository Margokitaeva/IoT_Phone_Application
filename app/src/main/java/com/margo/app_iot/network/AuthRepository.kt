package com.margo.app_iot.network

import com.margo.app_iot.data.SessionStore
import kotlinx.coroutines.flow.first

class AuthRepository(
    private val api: ApiClient,
    private val session: SessionStore
) {

    /**
     * Выполняет authed вызов:
     * - делает запрос с текущим accessToken
     * - если 401 -> refresh -> setTokens -> retry
     * - если refresh не удался -> logout() локально
     */
    suspend fun <T> call(block: suspend (accessToken: String) -> Result<T>): Result<T> {
        val access = session.accessTokenFlow.first()
        val firstTry = block(access)

        val ex = firstTry.exceptionOrNull()
        val http401 = (ex as? ApiClient.ApiHttpException)?.code == 401

        if (!http401) return firstTry

        // 401 -> пытаемся refresh
        val refreshToken = session.refreshTokenFlow.first()
        if (refreshToken.isBlank()) {
            session.logout()
            return firstTry
        }

        val refreshed = api.refresh(refreshToken)
        if (refreshed.isFailure) {
            session.logout()
            return Result.failure(refreshed.exceptionOrNull() ?: RuntimeException("Refresh failed"))
        }

        val rr = refreshed.getOrNull()!!
        session.setTokens(rr.accessToken, rr.refreshToken)

        // retry with new access token
        return block(rr.accessToken)
    }

    /**
     * Нормальный logout:
     * - пытаемся дернуть серверный logout (если есть refreshToken)
     * - всегда чистим локальную сессию
     */
    suspend fun logout(): Result<Unit> {
        val access = session.accessTokenFlow.first()
        val refresh = session.refreshTokenFlow.first()

        val serverRes =
            if (refresh.isNotBlank()) api.logout(accessToken = access, refreshToken = refresh)
            else Result.success(Unit)

        session.logout()
        return serverRes
    }
}
