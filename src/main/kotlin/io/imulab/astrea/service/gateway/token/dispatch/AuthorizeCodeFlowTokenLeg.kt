package io.imulab.astrea.service.gateway.token.dispatch

import io.grpc.Channel
import io.imulab.astrea.sdk.commons.flow.code.AuthorizeCodeFlowServiceGrpc
import io.imulab.astrea.sdk.commons.toOAuthException
import io.imulab.astrea.sdk.flow.code.toOidcTokenEndpointResponse
import io.imulab.astrea.sdk.flow.code.toTokenRequest
import io.imulab.astrea.sdk.oauth.exactly
import io.imulab.astrea.sdk.oauth.request.OAuthAccessRequest
import io.imulab.astrea.sdk.oauth.reserved.GrantType
import io.imulab.astrea.sdk.oauth.reserved.dot
import io.imulab.astrea.service.gateway.token.ResponseRenderer
import io.vertx.ext.web.RoutingContext

class AuthorizeCodeFlowTokenLeg(
    private val authorizationCodeFlowPrefix: String,
    channel: Channel
) : OAuthDispatcher {

    private val stub = AuthorizeCodeFlowServiceGrpc.newBlockingStub(channel)

    override fun supports(request: OAuthAccessRequest, rc: RoutingContext): Boolean {
        return request.grantTypes.exactly(GrantType.authorizationCode) &&
            request.code.startsWith(authorizationCodeFlowPrefix + dot)
    }

    override suspend fun handle(request: OAuthAccessRequest, rc: RoutingContext) {
        val response = stub.exchange(request.toTokenRequest())

        if (response.success) {
            ResponseRenderer.render(response.toOidcTokenEndpointResponse(), rc)
        } else {
            throw response.failure.toOAuthException()
        }
    }
}