package io.imulab.astrea.service.gateway.token.dispatch

import io.imulab.astrea.sdk.flow.refresh.RefreshFlowService
import io.imulab.astrea.sdk.oauth.exactly
import io.imulab.astrea.sdk.oauth.request.OAuthAccessRequest
import io.imulab.astrea.sdk.oauth.reserved.GrantType
import io.imulab.astrea.service.gateway.token.ResponseRenderer
import io.vertx.ext.web.RoutingContext

class RefreshFlow(private val service: RefreshFlowService) : OAuthDispatcher {

    override fun supports(request: OAuthAccessRequest, rc: RoutingContext): Boolean {
        return request.grantTypes.exactly(GrantType.refreshToken)
    }

    override suspend fun handle(request: OAuthAccessRequest, rc: RoutingContext) {
        ResponseRenderer.render(service.exchange(request), rc)
    }
}