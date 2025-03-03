/*
 * Copyright (c) 2017-present Robert Jaros
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.kvision.remote

import com.google.inject.Injector
import io.javalin.Javalin
import io.javalin.core.security.Role
import io.javalin.http.Context
import io.javalin.websocket.WsHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

typealias RequestHandler = (Context) -> Unit
typealias WebsocketHandler = (WsHandler) -> Unit

/**
 * Multiplatform service manager for Javalin.
 */
actual open class KVServiceManager<T : Any> actual constructor(val serviceClass: KClass<T>) : KVServiceMgr<T>,
    KVServiceBinder<T, RequestHandler, WebsocketHandler>() {

    companion object {
        val LOG: Logger = LoggerFactory.getLogger(KVServiceManager::class.java.name)
        const val KV_WS_INCOMING_KEY = "io.kvision.ws.incoming.key"
        const val KV_WS_OUTGOING_KEY = "io.kvision.ws.outgoing.key"
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun <RET> createRequestHandler(
        method: HttpMethod,
        function: suspend T.(params: List<String?>) -> RET,
        serializer: KSerializer<RET>
    ): RequestHandler =
        { ctx ->
            val jsonRpcRequest = if (method == HttpMethod.GET) {
                JsonRpcRequest(ctx.queryParam<Int>("id").get(), "", listOf())
            } else {
                ctx.body<JsonRpcRequest>()
            }
            val injector = ctx.attribute<Injector>(KV_INJECTOR_KEY)!!
            val service = injector.getInstance(serviceClass.java)
            val future = applicationScope.future {
                try {
                    val result = function.invoke(service, jsonRpcRequest.params)
                    JsonRpcResponse(
                        id = jsonRpcRequest.id,
                        result = deSerializer.serializeNullableToString(result, serializer)
                    )
                } catch (e: IllegalParameterCountException) {
                    JsonRpcResponse(id = jsonRpcRequest.id, error = "Invalid parameters")
                } catch (e: Exception) {
                    if (e !is ServiceException) LOG.error(e.message, e)
                    JsonRpcResponse(
                        id = jsonRpcRequest.id, error = e.message ?: "Error",
                        exceptionType = e.javaClass.canonicalName
                    )
                }
            }
            ctx.json(future)
        }

    override fun <REQ, RES> createWebsocketHandler(
        function: suspend T.(ReceiveChannel<REQ>, SendChannel<RES>) -> Unit,
        requestSerializer: KSerializer<REQ>,
        responseSerializer: KSerializer<RES>,
    ): WebsocketHandler =
        { ws ->
            ws.onConnect { ctx ->
                val incoming = Channel<String>()
                val outgoing = Channel<String>()
                ctx.attribute(KV_WS_INCOMING_KEY, incoming)
                ctx.attribute(KV_WS_OUTGOING_KEY, outgoing)
                val injector = ctx.attribute<Injector>(KV_INJECTOR_KEY)!!
                val service = injector.getInstance(serviceClass.java)
                applicationScope.launch {
                    coroutineScope {
                        launch {
                            outgoing.consumeEach { ctx.send(it) }
                            ctx.session.close()
                        }
                        launch {
                            handleWebsocketConnection(
                                deSerializer = deSerializer,
                                rawIn = incoming,
                                rawOut = outgoing,
                                serializerIn = requestSerializer,
                                serializerOut = responseSerializer,
                                service = service,
                                function = function
                            )
                        }
                    }
                }
            }
            ws.onClose { ctx ->
                applicationScope.launch {
                    val outgoing = ctx.attribute<Channel<String>>(KV_WS_OUTGOING_KEY)!!
                    val incoming = ctx.attribute<Channel<String>>(KV_WS_INCOMING_KEY)!!
                    outgoing.close()
                    incoming.close()
                }
            }
            ws.onMessage { ctx ->
                applicationScope.launch {
                    val incoming = ctx.attribute<Channel<String>>(KV_WS_INCOMING_KEY)!!
                    incoming.send(ctx.message())
                }
            }
        }
}

/**
 * A function to generate routes based on definitions from the service manager.
 */
fun <T : Any> Javalin.applyRoutes(serviceManager: KVServiceManager<T>, roles: Set<Role> = setOf()) {
    serviceManager.routeMapRegistry.asSequence().forEach { (method, path, handler) ->
        when (method) {
            HttpMethod.GET -> get(path, handler, roles)
            HttpMethod.POST -> post(path, handler, roles)
            HttpMethod.PUT -> put(path, handler, roles)
            HttpMethod.DELETE -> delete(path, handler, roles)
            HttpMethod.OPTIONS -> options(path, handler, roles)
        }
    }
    serviceManager.webSocketRequests.forEach { (path, handler) ->
        ws(path, handler, roles)
    }
}
