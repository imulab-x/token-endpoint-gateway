package io.imulab.astrea.service.gateway.token

import io.imulab.astrea.sdk.oauth.error.OAuthException
import io.imulab.astrea.sdk.oauth.error.ServerError
import io.imulab.astrea.sdk.oauth.response.TokenEndpointResponse
import io.vertx.core.json.Json
import io.vertx.ext.web.RoutingContext

object ResponseRenderer {

    fun render(response: TokenEndpointResponse, rc: RoutingContext) {
        rc.response()
            .setStatusCode(200)
            .apply {
                response.headers.forEach { t, u -> putHeader(t, u) }
                putHeader("Content-Type", "application/json")
            }
            .end(Json.encode(response.data))
    }

    fun render(t: Throwable, rc: RoutingContext) {
        val e = if (t is OAuthException) t else ServerError.wrapped(t)
        rc.response()
            .setStatusCode(e.status)
            .apply {
                e.headers.forEach { t, u -> putHeader(t, u) }
                putHeader("Content-Type", "application/json")
            }
            .end(Json.encode(e.data))
    }
}