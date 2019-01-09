package io.imulab.astrea.service.gateway.token.dispatch

import io.imulab.astrea.sdk.flow.hybrid.HybridFlowTokenLegService
import io.imulab.astrea.sdk.oauth.exactly
import io.imulab.astrea.sdk.oauth.request.OAuthAccessRequest
import io.imulab.astrea.sdk.oauth.reserved.GrantType
import io.imulab.astrea.sdk.oauth.reserved.dot
import io.imulab.astrea.service.gateway.token.ResponseRenderer
import io.vertx.ext.web.RoutingContext

class HybridFlowTokenLeg(
    private val service: HybridFlowTokenLegService,
    private val hybridFlowPrefix: String
) : OAuthDispatcher {

    override fun supports(request: OAuthAccessRequest, rc: RoutingContext): Boolean {
        return request.grantTypes.exactly(GrantType.authorizationCode) &&
            request.code.startsWith(hybridFlowPrefix + dot)
    }

    override suspend fun handle(request: OAuthAccessRequest, rc: RoutingContext) {
        ResponseRenderer.render(service.exchange(request), rc)
    }
}