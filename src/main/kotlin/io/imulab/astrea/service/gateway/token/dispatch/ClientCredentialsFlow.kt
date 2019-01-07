package io.imulab.astrea.service.gateway.token.dispatch

import io.grpc.Channel
import io.imulab.astrea.sdk.commons.flow.cc.ClientCredentialsFlowServiceGrpc
import io.imulab.astrea.sdk.commons.toOAuthException
import io.imulab.astrea.sdk.flow.cc.toClientCredentialsTokenRequest
import io.imulab.astrea.sdk.flow.cc.toTokenEndpointResponse
import io.imulab.astrea.sdk.oauth.exactly
import io.imulab.astrea.sdk.oauth.request.OAuthAccessRequest
import io.imulab.astrea.sdk.oauth.reserved.GrantType
import io.imulab.astrea.service.gateway.token.ResponseRenderer
import io.vertx.ext.web.RoutingContext

class ClientCredentialsFlow(
    channel: Channel
) : OAuthDispatcher {

    private val stub = ClientCredentialsFlowServiceGrpc.newBlockingStub(channel)

    override fun supports(request: OAuthAccessRequest, rc: RoutingContext): Boolean {
        return request.grantTypes.exactly(GrantType.clientCredentials)
    }

    override suspend fun handle(request: OAuthAccessRequest, rc: RoutingContext) {
        val response = stub.exchange(request.toClientCredentialsTokenRequest())

        if (response.success) {
            ResponseRenderer.render(response.toTokenEndpointResponse(), rc)
        } else {
            throw response.failure.toOAuthException()
        }
    }
}