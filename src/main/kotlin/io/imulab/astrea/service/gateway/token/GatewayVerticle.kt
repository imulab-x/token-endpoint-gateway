package io.imulab.astrea.service.gateway.token

import com.typesafe.config.Config
import io.imulab.astrea.sdk.oauth.assertType
import io.imulab.astrea.sdk.oauth.error.ServerError
import io.imulab.astrea.sdk.oauth.request.OAuthAccessRequest
import io.imulab.astrea.sdk.oauth.request.OAuthRequestProducer
import io.imulab.astrea.sdk.oauth.validation.OAuthGrantTypeValidator
import io.imulab.astrea.sdk.oidc.request.OidcRequestForm
import io.imulab.astrea.service.gateway.token.dispatch.OAuthDispatcher
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory

class GatewayVerticle(
    private val appConfig: Config,
    private val healthCheckHandler: HealthCheckHandler,
    private val requestProducer: OAuthRequestProducer,
    private val dispatchers: List<OAuthDispatcher>
) : CoroutineVerticle() {

    private val logger = LoggerFactory.getLogger(GatewayVerticle::class.java)

    override suspend fun start() {
        val router = Router.router(vertx)

        router.post("/").consumes("application/x-www-form-urlencoded")
            .handler(BodyHandler.create())
            .suspendedOAuthHandler { rc ->
                /**
                 * Request parsing, and authentication.
                 */
                val request = requestProducer.produce(
                    OidcRequestForm(
                        rc.request().formAttributes().entries().groupBy(
                            keySelector = { e -> e.key },
                            valueTransform = { e -> e.value }
                        ).toMutableMap()
                    ).apply {
                        authorizationHeader = rc.request().getHeader("Authorization") ?: ""
                    }
                ).assertType<OAuthAccessRequest>()

                /**
                 * Validation
                 */
                OAuthGrantTypeValidator.validate(request)

                rc.setOAuthAccessRequest(request)
                rc.next()
            }
            .suspendedOAuthHandler { rc ->
                /**
                 * Dispatch the request to backend flow service
                 */
                val request = rc.getOAuthAccessRequest()!!
                val dispatcher = dispatchers.find { it.supports(request, rc) }
                    ?: throw ServerError.internal("Cannot find proper handler for request.")
                dispatcher.handle(request, rc)
            }
        router.get("/health").handler(healthCheckHandler)

        vertx.createHttpServer(HttpServerOptions().apply {
            port = appConfig.getInt("service.port")
        }).requestHandler(router).listen()

        healthCheckHandler.register("TokenEndpointGatewayAPI") { h -> h.complete(Status.OK()) }
    }

    private fun Route.suspendedOAuthHandler(block: suspend (RoutingContext) -> Unit): Route {
        handler { rc ->
            CoroutineScope(rc.vertx().dispatcher()).async {
                block(rc)
            }.invokeOnCompletion { e ->
                if (e != null)
                    ResponseRenderer.render(e, rc)
            }
        }
        return this
    }
}

private const val requestKey = "oauthAccessRequest"

internal fun RoutingContext.getOAuthAccessRequest(): OAuthAccessRequest? =
    get(requestKey)

internal fun RoutingContext.setOAuthAccessRequest(r: OAuthAccessRequest) {
    put(requestKey, r)
}