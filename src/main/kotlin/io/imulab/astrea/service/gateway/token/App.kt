package io.imulab.astrea.service.gateway.token

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.ManagedChannelBuilder
import io.imulab.astrea.sdk.client.RemoteClientLookupService
import io.imulab.astrea.sdk.commons.doNotCall
import io.imulab.astrea.sdk.discovery.RemoteDiscoveryService
import io.imulab.astrea.sdk.discovery.SampleDiscovery
import io.imulab.astrea.sdk.event.ClientEvents
import io.imulab.astrea.sdk.flow.hybrid.RemoteHybridFlowTokenLegService
import io.imulab.astrea.sdk.flow.refresh.RemoteRefreshFlowService
import io.imulab.astrea.sdk.oauth.OAuthContext
import io.imulab.astrea.sdk.oauth.client.ClientLookup
import io.imulab.astrea.sdk.oauth.client.OAuthClient
import io.imulab.astrea.sdk.oauth.client.authn.ClientSecretBasicAuthenticator
import io.imulab.astrea.sdk.oauth.client.authn.ClientSecretPostAuthenticator
import io.imulab.astrea.sdk.oauth.client.pwd.BCryptPasswordEncoder
import io.imulab.astrea.sdk.oauth.request.OAuthRequestProducer
import io.imulab.astrea.sdk.oauth.reserved.AuthenticationMethod
import io.imulab.astrea.sdk.oauth.validation.OAuthGrantTypeValidator
import io.imulab.astrea.sdk.oidc.client.OidcClient
import io.imulab.astrea.sdk.oidc.client.authn.OidcClientAuthenticators
import io.imulab.astrea.sdk.oidc.client.authn.PrivateKeyJwtAuthenticator
import io.imulab.astrea.sdk.oidc.discovery.Discovery
import io.imulab.astrea.sdk.oidc.jwk.JsonWebKeySetRepository
import io.imulab.astrea.sdk.oidc.jwk.JsonWebKeySetStrategy
import io.imulab.astrea.sdk.oidc.request.OidcAccessRequestProducer
import io.imulab.astrea.sdk.oidc.spi.HttpResponse
import io.imulab.astrea.sdk.oidc.spi.SimpleHttpClient
import io.imulab.astrea.service.gateway.token.dispatch.AuthorizeCodeFlowTokenLeg
import io.imulab.astrea.service.gateway.token.dispatch.ClientCredentialsFlow
import io.imulab.astrea.service.gateway.token.dispatch.HybridFlowTokenLeg
import io.imulab.astrea.service.gateway.token.dispatch.RefreshFlow
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.kotlin.coroutines.awaitResult
import kotlinx.coroutines.runBlocking
import org.jose4j.jwk.JsonWebKeySet
import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.eagerSingleton
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

private val logger: Logger = LoggerFactory.getLogger("io.imulab.astrea.service.gateway.token.AppKt")

suspend fun main(args: Array<String>) {
    val vertx = Vertx.vertx()
    val config = ConfigFactory.load()
    val components = App(vertx, config).bootstrap()
    val gateway by components.instance<GatewayVerticle>()

    try {
        val deploymentId = awaitResult<String> { vertx.deployVerticle(gateway, it) }
        logger.info("Token endpoint gateway service successfully deployed with id {}", deploymentId)
    } catch (e: Exception) {
        logger.error("Token endpoint gateway service encountered error during deployment.", e)
    }
}

@Suppress("MemberVisibilityCanBePrivate")
open class App(private val vertx: Vertx, private val config: Config) {

    open fun bootstrap(): Kodein {
        return Kodein {
            importOnce(discovery)
            importOnce(client)
            importOnce(authorizeCodeFlow)
            importOnce(clientCredentialsFlow)
            importOnce(hybridFlow)
            importOnce(refreshFlow)
            importOnce(app)

            bind<GatewayVerticle>() with singleton {
                GatewayVerticle(
                    appConfig = config,
                    healthCheckHandler = instance(),
                    requestProducer = instance(),
                    dispatchers = listOf(
                        instance<AuthorizeCodeFlowTokenLeg>(),
                        instance<ClientCredentialsFlow>(),
                        instance<HybridFlowTokenLeg>(),
                        instance<RefreshFlow>()
                    )
                )
            }
        }
    }

    val app = Kodein.Module("app") {
        bind<HealthCheckHandler>() with singleton { HealthCheckHandler.create(vertx) }

        bind<OidcClientAuthenticators>() with singleton {
            OidcClientAuthenticators(
                authenticators = listOf(
                    instance<ClientSecretBasicAuthenticator>(),
                    instance<ClientSecretPostAuthenticator>(),
                    instance<PrivateKeyJwtAuthenticator>()
                ),
                clientLookup = instance()
            )
        }

        bind<OAuthRequestProducer>() with singleton {
            OidcAccessRequestProducer(
                grantTypeValidator = OAuthGrantTypeValidator,
                clientAuthenticators = instance()
            )
        }
    }

    val authorizeCodeFlow = Kodein.Module("authorizeCodeFlow") {
        bind<AuthorizeCodeFlowTokenLeg>() with singleton {
            AuthorizeCodeFlowTokenLeg(
                authorizationCodeFlowPrefix = config.getString("authorizeCodeFlow.serviceId"),
                channel = ManagedChannelBuilder.forAddress(
                    config.getString("authorizeCodeFlow.host"),
                    config.getInt("authorizeCodeFlow.port")
                ).enableRetry().maxRetryAttempts(10).usePlaintext().build()
            )
        }
    }

    val clientCredentialsFlow = Kodein.Module("clientCredentialsFlow") {
        bind<ClientCredentialsFlow>() with singleton {
            ClientCredentialsFlow(
                channel = ManagedChannelBuilder.forAddress(
                    config.getString("clientCredentialsFlow.host"),
                    config.getInt("clientCredentialsFlow.port")
                ).enableRetry().maxRetryAttempts(10).usePlaintext().build()
            )
        }
    }

    val hybridFlow = Kodein.Module("hybridFlow") {
        bind<HybridFlowTokenLeg>() with singleton {
            HybridFlowTokenLeg(
                service = RemoteHybridFlowTokenLegService(
                    ManagedChannelBuilder.forAddress(
                        config.getString("hybridFlow.host"),
                        config.getInt("hybridFlow.port")
                    ).enableRetry().maxRetryAttempts(10).usePlaintext().build()
                ),
                hybridFlowPrefix = config.getString("hybridFlow.serviceId")
            )
        }
    }

    val refreshFlow = Kodein.Module("refreshFlow") {
        bind<RefreshFlow>() with singleton {
            RefreshFlow(
                RemoteRefreshFlowService(
                    ManagedChannelBuilder.forAddress(
                        config.getString("refreshFlow.host"),
                        config.getInt("refreshFlow.port")
                    ).enableRetry().maxRetryAttempts(10).usePlaintext().build()
                )
            )
        }
    }

    val client = Kodein.Module("client") {
        bind<Cache<String, OAuthClient>>() with singleton {
            val cache = Caffeine.newBuilder()
                .maximumSize(500)
                .build<String, OAuthClient>()

            vertx.eventBus().consumer<JsonObject>(ClientEvents.updateClientEvent) { m ->
                cache.invalidate(m.body().getString("id"))
            }
            vertx.eventBus().consumer<JsonObject>(ClientEvents.deleteClientEvent) { m ->
                cache.invalidate(m.body().getString("id"))
            }

            cache
        }

        bind<ClientLookup>() with singleton {
            RemoteClientLookupService(
                channel = ManagedChannelBuilder.forAddress(
                    config.getString("client.host"),
                    config.getInt("client.port")
                ).enableRetry().maxRetryAttempts(10).usePlaintext().build(),
                cache = instance()
            )
        }

        bind<ClientSecretBasicAuthenticator>() with singleton {
            ClientSecretBasicAuthenticator(lookup = instance(), passwordEncoder = BCryptPasswordEncoder())
        }

        bind<ClientSecretPostAuthenticator>() with singleton {
            ClientSecretPostAuthenticator(lookup = instance(), passwordEncoder = BCryptPasswordEncoder())
        }

        bind<PrivateKeyJwtAuthenticator>() with singleton {
            PrivateKeyJwtAuthenticator(
                clientLookup = instance(),
                oauthContext = object : OAuthContext {
                    override val issuerUrl: String by lazy { instance<Discovery>().issuer }
                    override val authorizeEndpointUrl: String by lazy { instance<Discovery>().authorizationEndpoint }
                    override val tokenEndpointUrl: String by lazy { instance<Discovery>().tokenEndpoint }
                    override val defaultTokenEndpointAuthenticationMethod: String = AuthenticationMethod.clientSecretBasic
                    override val authorizeCodeLifespan: Duration by lazy { doNotCall() }
                    override val accessTokenLifespan: Duration by lazy { doNotCall() }
                    override val refreshTokenLifespan: Duration by lazy { doNotCall() }
                    override val stateEntropy: Int by lazy { doNotCall() }
                },
                clientJwksStrategy = object : JsonWebKeySetStrategy(
                    httpClient = object : SimpleHttpClient {
                        override suspend fun get(url: String): HttpResponse = doNotCall()
                    },
                    jsonWebKeySetRepository = object : JsonWebKeySetRepository {
                        override suspend fun getServerJsonWebKeySet(): JsonWebKeySet = doNotCall()
                        override suspend fun getClientJsonWebKeySet(jwksUri: String): JsonWebKeySet? = doNotCall()
                        override suspend fun writeClientJsonWebKeySet(jwksUri: String, keySet: JsonWebKeySet) = doNotCall()
                    }
                ) {
                    override suspend fun resolveKeySet(client: OidcClient): JsonWebKeySet {
                        if (client.jwks.isEmpty())
                            return JsonWebKeySet()
                        return JsonWebKeySet(client.jwks)
                    }
                }
            )
        }
    }

    val discovery = Kodein.Module("discovery") {
        bind<Discovery>() with eagerSingleton {
            if (config.getBoolean("discovery.useSample")) {
                logger.info("Using default discovery instead of remote.")
                SampleDiscovery.default()
            } else {
                val channel = ManagedChannelBuilder.forAddress(
                    config.getString("discovery.host"),
                    config.getInt("discovery.port")
                ).enableRetry().maxRetryAttempts(10).usePlaintext().build()

                runBlocking {
                    RemoteDiscoveryService(channel).getDiscovery()
                }.also { logger.info("Acquired discovery remote remote") }
            }
        }
    }
}